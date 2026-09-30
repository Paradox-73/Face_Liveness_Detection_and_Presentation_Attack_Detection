package io.mosip.registration.service.bio.liveness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.model.SemanticVersion;
import io.mosip.registration.config.AppConfig;

/**
 * Secure offline model update (bonus B6): imports a signed model bundle from a folder or a {@code .zip} file (for
 * example on a USB stick) and switches the running liveness engine to it, without a client upgrade.
 *
 * <p>Checks, in order; the first failure stops the import and the running models stay active (fail closed):
 * <ol>
 * <li>the user holds {@code REGISTRATION_SUPERVISOR} or {@code REGISTRATION_ADMIN};</li>
 * <li>no face verification is running, and the configured engine is the built-in ONNX engine;</li>
 * <li>the manifest signature verifies against the built-in trust anchors (RS256 JWS);</li>
 * <li>the bundle version is at least {@code model.min_version} and <em>newer</em> than the running bundle and the
 * store's version floor (anti-rollback);</li>
 * <li>every model file listed in the manifest has the size and SHA-256 the manifest states;</li>
 * <li>the files copied into the model store verify again (the store copy is what the engine loads);</li>
 * <li>a new engine built from the store copy passes its self-test; only then is it swapped in.</li>
 * </ol>
 *
 * <p>Audits: a refused bundle writes {@code REG-EVT-LV14} (signature, hash or version failure) or
 * {@code REG-EVT-LV13} (the models could not start); a successful switch writes {@code REG-EVT-LV15} with the old
 * and new bundle versions. The client's audit service adds the user ID. No file content is logged.
 *
 * <p>Thread-safe: imports are serialised.
 */
@Component
public class LivenessModelImporter {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessModelImporter.class);

	/** Roles allowed to import models. */
	static final List<String> ALLOWED_ROLES = Collections
			.unmodifiableList(java.util.Arrays.asList("REGISTRATION_SUPERVISOR", "REGISTRATION_ADMIN"));
	static final int MAX_ZIP_ENTRIES = 64;
	static final long MAX_ZIP_BYTES = 512L * 1024 * 1024;
	private static final String ANY_VERSION = "0.0.0";
	private static final Pattern SAFE_ENTRY = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

	/** Result of an import. */
	public enum Status {
		/** Verified, stored and running. */
		IMPORTED("liveness.models.result.imported"),
		/** The user may not import models. */
		NOT_ALLOWED("liveness.models.result.not_allowed"),
		/** A face verification is running. */
		BUSY("liveness.models.result.busy"),
		/** A vendor engine is configured; its models are not managed by the client. */
		NOT_SUPPORTED("liveness.models.result.not_supported"),
		/** Nothing readable at the location, or no manifest in it. */
		NOT_FOUND("liveness.models.result.not_found"),
		/** Signature, manifest or model hash check failed. */
		INVALID("liveness.models.result.invalid"),
		/** Older than or equal to the running bundle, below the floor or below {@code model.min_version}. */
		TOO_OLD("liveness.models.result.too_old"),
		/** The verified models could not start (runtime or self-test failure). */
		LOAD_FAILED("liveness.models.result.load_failed");

		private final String messageKey;

		Status(String messageKey) {
			this.messageKey = messageKey;
		}

		/**
		 * Returns the key of the text shown to the user (labels bundle).
		 *
		 * @return message key
		 */
		public String messageKey() {
			return messageKey;
		}
	}

	/** Builds and swaps in an engine for a verified bundle; {@link LivenessEngineRegistry#switchModels} by default. */
	@FunctionalInterface
	interface EngineSwitch {
		void switchTo(ModelSource source) throws EngineException;
	}

	private final LivenessConfig config;
	private final LivenessEngineRegistry engines;
	private final LivenessOrchestrator orchestrator;
	private final AuditSink audit;
	private final Clock clock;
	private final EngineSwitch engineSwitch;
	private List<PublicKey> trustAnchors = Collections.emptyList();

	/**
	 * Creates the importer.
	 *
	 * @param config       liveness configuration ({@code model.min_version})
	 * @param engines      the engine registry that loads and switches the models
	 * @param orchestrator tells whether a face verification is running
	 * @param audit        audit sink
	 */
	@Autowired
	public LivenessModelImporter(LivenessConfig config, LivenessEngineRegistry engines,
			LivenessOrchestrator orchestrator, DesktopAuditSink audit) {
		this(config, engines, orchestrator, audit, Clock.system(), source -> engines.switchModels(source));
	}

	LivenessModelImporter(LivenessConfig config, LivenessEngineRegistry engines, LivenessOrchestrator orchestrator,
			AuditSink audit, Clock clock, EngineSwitch engineSwitch) {
		this.config = Objects.requireNonNull(config, "config");
		this.engines = Objects.requireNonNull(engines, "engines");
		this.orchestrator = orchestrator;
		this.audit = Objects.requireNonNull(audit, "audit");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.engineSwitch = Objects.requireNonNull(engineSwitch, "engineSwitch");
	}

	/**
	 * Replaces the trust anchors (tests sign bundles with a generated key).
	 *
	 * @param anchors accepted signing keys; empty means the built-in keys
	 */
	void setTrustAnchors(List<PublicKey> anchors) {
		this.trustAnchors = new ArrayList<>(anchors);
	}

	/**
	 * Describes the running model bundle, for the import screen.
	 *
	 * @return name, version and whether it was imported; empty when no bundle can be read
	 */
	public Optional<Result> current() {
		Optional<LivenessModelStore.Installed> imported = engines.modelStore().active();
		try {
			ModelRegistry reg = open(engines.activeModelSource(), ANY_VERSION);
			return Optional.of(Result.of(Status.IMPORTED, reg.bundleName(), reg.bundleVersion(), "", reg.models(),
					imported.isPresent()));
		} catch (EngineException e) {
			return Optional.empty();
		}
	}

	/**
	 * Verifies a bundle, stores it and switches the engine to it.
	 *
	 * @param location folder with {@code manifest.json}, {@code manifest.jws} and the model files, or a {@code .zip}
	 *                 file with the same content
	 * @param userId   user who imports (for the log; the audit service records the session user)
	 * @param roles    roles of that user
	 * @return the result; never throws for a bad bundle
	 */
	public synchronized Result importBundle(Path location, String userId, Collection<String> roles) {
		Objects.requireNonNull(location, "location");
		if (roles == null || Collections.disjoint(roles, ALLOWED_ROLES)) {
			LOGGER.info("Liveness model import refused for {}: role not allowed", userId);
			return Result.of(Status.NOT_ALLOWED);
		}
		if (orchestrator != null && orchestrator.current().map(LivenessRun::isSessionActive).orElse(false)) {
			return Result.of(Status.BUSY);
		}
		if (!engines.isDefaultEngine()) {
			return Result.of(Status.NOT_SUPPORTED);
		}
		String previous = runningVersion();
		Path staging = null;
		try {
			Path source = location;
			if (Files.isRegularFile(location) && location.getFileName().toString().toLowerCase(Locale.ROOT)
					.endsWith(".zip")) {
				staging = Files.createTempDirectory("liveness-models-");
				unzip(location, staging);
				source = bundleRoot(staging);
			}
			if (!Files.isRegularFile(source.resolve(ModelRegistry.MANIFEST_FILE))) {
				return Result.of(Status.NOT_FOUND);
			}
			return install(source, previous, userId);
		} catch (IOException e) {
			LOGGER.error("Liveness model import: bundle could not be read ({})", e.getClass().getSimpleName());
			return Result.of(Status.NOT_FOUND);
		} finally {
			if (staging != null) {
				LivenessModelStore.deleteTree(staging);
			}
		}
	}

	private Result install(Path source, String previous, String userId) throws IOException {
		ModelRegistry incoming;
		try {
			incoming = open(ModelSources.directory(source.toFile()), ANY_VERSION);
		} catch (EngineException e) {
			return refused(e, "bundle");
		}
		String floor = highest(previous, engines.modelStore().floor().orElse(null));
		SemanticVersion version = SemanticVersion.parse(incoming.bundleVersion());
		boolean belowMinimum = !version.isAtLeast(minimumVersion());
		if (belowMinimum || (floor != null && version.compareTo(SemanticVersion.parse(floor)) <= 0)) {
			floor = belowMinimum ? config.modelMinVersion() : floor;
			LOGGER.error("Liveness model import refused: bundle {} is not newer than {}", incoming.bundleVersion(),
					floor);
			auditIntegrity(incoming.bundleName());
			return Result.of(Status.TOO_OLD, incoming.bundleName(), incoming.bundleVersion(), floor,
					incoming.models(), false);
		}
		try {
			incoming.verifyAll();
		} catch (EngineException e) {
			return refused(e, incoming.bundleName());
		}
		LivenessModelStore store = engines.modelStore();
		Path folder = store.newBundleFolder(incoming.bundleName(), incoming.bundleVersion());
		Properties before = null;
		boolean switched = false;
		try {
			copyBundle(source, folder, incoming);
			ModelRegistry stored = open(ModelSources.directory(folder.toFile()), config.modelMinVersion());
			stored.verifyAll();
			before = store.activate(new LivenessModelStore.Installed(folder, stored.bundleName(),
					stored.bundleVersion()));
			engineSwitch.switchTo(ModelSources.directory(folder.toFile()));
			switched = true;
			audit.emit(new AuditPayloadBuilder(Workflow.REGISTRATION, "", clock).configApplied("model.bundle="
					+ stored.bundleName() + ":" + (previous == null ? "none" : previous) + "->"
					+ stored.bundleVersion()));
			LOGGER.info("Liveness models {} {} imported by {} (previous {})", stored.bundleName(),
					stored.bundleVersion(), userId, previous);
			return Result.of(Status.IMPORTED, stored.bundleName(), stored.bundleVersion(),
					previous == null ? "" : previous, stored.models(), true);
		} catch (EngineException e) {
			if (e.getErrorCode().isModelIntegrityFailure()) {
				return refused(e, incoming.bundleName());
			}
			LOGGER.error("Liveness model import: verified models could not start ({})", e.getErrorCode());
			audit.emit(new AuditPayloadBuilder(Workflow.REGISTRATION, "", clock).engineError(e.getErrorCode(),
					e.getClass().getSimpleName()));
			return Result.of(Status.LOAD_FAILED, incoming.bundleName(), incoming.bundleVersion(),
					previous == null ? "" : previous, incoming.models(), false);
		} finally {
			if (!switched) {
				if (before != null) {
					store.restore(before);
				}
				store.discard(folder);
			}
		}
	}

	private Result refused(EngineException e, String bundle) {
		LOGGER.error("Liveness model import refused: {} ({})", e.getErrorCode(), e.getMessage());
		auditIntegrity(bundle);
		if (e.getErrorCode() == EngineErrorCode.MODEL_VERSION) {
			return Result.of(Status.TOO_OLD);
		}
		return Result.of(Status.INVALID);
	}

	private void auditIntegrity(String bundle) {
		audit.emit(new AuditPayloadBuilder(Workflow.REGISTRATION, "", clock).modelIntegrityFailed(bundle));
	}

	private SemanticVersion minimumVersion() {
		try {
			return SemanticVersion.parse(config.modelMinVersion());
		} catch (IllegalArgumentException e) {
			return SemanticVersion.parse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION);
		}
	}

	private ModelRegistry open(ModelSource source, String minimum) throws EngineException {
		ModelRegistry.Builder b = ModelRegistry.builder(source).minimumVersion(minimum);
		for (PublicKey key : trustAnchors) {
			b.trustAnchor(key);
		}
		return b.open();
	}

	/** The version the engine runs now, or of the bundle it would load. */
	private String runningVersion() {
		Optional<String> loaded = engines.bundleVersion();
		if (loaded.isPresent()) {
			return loaded.get();
		}
		try {
			return open(engines.activeModelSource(), ANY_VERSION).bundleVersion();
		} catch (EngineException e) {
			return null;
		}
	}

	private static String highest(String a, String b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return SemanticVersion.parse(a).compareTo(SemanticVersion.parse(b)) >= 0 ? a : b;
	}

	/** Copies the manifest, its signature and the listed model files; nothing else. */
	private static void copyBundle(Path source, Path target, ModelRegistry registry) throws IOException {
		List<String> files = new ArrayList<>();
		files.add(ModelRegistry.MANIFEST_FILE);
		files.add(ModelRegistry.SIGNATURE_FILE);
		for (ModelEntry e : registry.manifest().models()) {
			files.add(e.file());
		}
		for (String file : files) {
			if (!SAFE_ENTRY.matcher(file).matches() || file.contains("..")) {
				throw new IOException("unsafe file name in manifest");
			}
			Path to = target.resolve(file).normalize();
			if (!to.startsWith(target)) {
				throw new IOException("unsafe file name in manifest");
			}
			Files.createDirectories(to.getParent());
			Files.copy(source.resolve(file), to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Extracts a zip file, refusing absolute paths, {@code ..}, too many entries and too many bytes. */
	static void unzip(Path zip, Path target) throws IOException {
		int entries = 0;
		long total = 0;
		byte[] buffer = new byte[64 * 1024];
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (++entries > MAX_ZIP_ENTRIES) {
					throw new IOException("too many entries in model bundle zip");
				}
				String name = entry.getName();
				if (!SAFE_ENTRY.matcher(name.endsWith("/") ? name.substring(0, name.length() - 1) : name).matches()
						|| name.contains("..")) {
					throw new IOException("unsafe entry name in model bundle zip");
				}
				Path to = target.resolve(name).normalize();
				if (!to.startsWith(target)) {
					throw new IOException("unsafe entry name in model bundle zip");
				}
				if (entry.isDirectory()) {
					Files.createDirectories(to);
					continue;
				}
				Files.createDirectories(to.getParent());
				try (java.io.OutputStream out = Files.newOutputStream(to)) {
					int n;
					while ((n = in.read(buffer)) > 0) {
						total += n;
						if (total > MAX_ZIP_BYTES) {
							throw new IOException("model bundle zip larger than " + MAX_ZIP_BYTES + " bytes");
						}
						out.write(buffer, 0, n);
					}
				}
			}
		}
	}

	/** The folder of an extracted zip that holds the manifest: the root, or its only sub-folder. */
	private static Path bundleRoot(Path extracted) throws IOException {
		if (Files.isRegularFile(extracted.resolve(ModelRegistry.MANIFEST_FILE))) {
			return extracted;
		}
		List<Path> dirs = new ArrayList<>();
		try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(extracted)) {
			for (Path child : children) {
				if (Files.isDirectory(child)) {
					dirs.add(child);
				}
			}
		}
		return dirs.size() == 1 ? dirs.get(0) : extracted;
	}

	/** Outcome of an import, or the description of the running bundle. */
	public static final class Result {

		private final Status status;
		private final String bundle;
		private final String version;
		private final String previousVersion;
		private final List<ModelInfo> models;
		private final boolean imported;

		private Result(Status status, String bundle, String version, String previousVersion, List<ModelInfo> models,
				boolean imported) {
			this.status = status;
			this.bundle = bundle;
			this.version = version;
			this.previousVersion = previousVersion;
			this.models = Collections.unmodifiableList(new ArrayList<>(models));
			this.imported = imported;
		}

		static Result of(Status status) {
			return new Result(status, "", "", "", Collections.<ModelInfo>emptyList(), false);
		}

		static Result of(Status status, String bundle, String version, String previousVersion,
				List<ModelInfo> models, boolean imported) {
			return new Result(status, bundle, version, previousVersion, models, imported);
		}

		/**
		 * Returns the status.
		 *
		 * @return the status
		 */
		public Status status() {
			return status;
		}

		/**
		 * Tells whether the new bundle is running.
		 *
		 * @return {@code true} for {@link Status#IMPORTED}
		 */
		public boolean isImported() {
			return status == Status.IMPORTED;
		}

		/**
		 * Returns the bundle name.
		 *
		 * @return name from the manifest; empty when the manifest could not be verified
		 */
		public String bundle() {
			return bundle;
		}

		/**
		 * Returns the bundle version.
		 *
		 * @return version from the manifest; empty when the manifest could not be verified
		 */
		public String version() {
			return version;
		}

		/**
		 * Returns the version that ran before the import, or the version the bundle had to exceed.
		 *
		 * @return the version; empty when unknown
		 */
		public String previousVersion() {
			return previousVersion;
		}

		/**
		 * Returns the models of the bundle (identifier, version, SHA-256).
		 *
		 * @return unmodifiable list, empty when the manifest could not be verified
		 */
		public List<ModelInfo> models() {
			return models;
		}

		/**
		 * Tells whether the bundle comes from the model store rather than the client installation.
		 *
		 * @return {@code true} for an imported bundle
		 */
		public boolean isFromStore() {
			return imported;
		}

		@Override
		public String toString() {
			return "Result[" + status + ", " + bundle + " " + version + ", previous " + previousVersion + "]";
		}
	}
}
