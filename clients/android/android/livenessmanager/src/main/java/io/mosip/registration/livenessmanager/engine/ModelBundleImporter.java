/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.engine;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.model.ModelTrustAnchors;
import io.mosip.liveness.onnx.model.SemanticVersion;

/**
 * Secure offline model update (bonus B6) on Android: imports a signed model bundle from a folder or a {@code .zip}
 * file the app can read (for example copied from a USB drive into the app's import folder) and switches the
 * running liveness engine to it, without an app update. Same checks, store layout and audits as the desktop
 * client's {@code LivenessModelImporter}; signature, hash and version checks are the engine's own
 * {@link ModelRegistry}.
 *
 * <p>Checks, in order; the first failure stops the import and the running models stay active (fail closed):
 * <ol>
 * <li>the user holds {@code REGISTRATION_SUPERVISOR} or {@code REGISTRATION_ADMIN};</li>
 * <li>no liveness capture screen is open (result {@code BUSY});</li>
 * <li>the configured engine is the built-in ONNX engine;</li>
 * <li>the manifest signature verifies against the built-in trust anchors (RS256 JWS);</li>
 * <li>the bundle version is at least {@code model.min_version} and <em>newer</em> than the running bundle and the
 * store's version floor (anti-rollback);</li>
 * <li>every model file listed in the manifest has the size and SHA-256 the manifest states;</li>
 * <li>the files copied into the model store verify again (the store copy is what the engine loads);</li>
 * <li>a new engine built from the store copy passes its self-test; only then is it swapped in. The bundle that
 * ran before stays in the store, and the pointer goes back to it if the switch fails.</li>
 * </ol>
 *
 * <p>Audits: a refused bundle writes {@code REG-EVT-LV14} (signature, hash or version failure) or
 * {@code REG-EVT-LV13} (the models could not start); a successful switch writes {@code REG-EVT-LV15} with the old
 * and new bundle versions. ARC's audit service adds the user ID. No file content is logged.
 *
 * <p>Thread-safe: imports are serialised.
 */
public final class ModelBundleImporter {

    private static final String TAG = "Liveness";

    /** Roles allowed to import models. */
    public static final List<String> ALLOWED_ROLES = Collections
            .unmodifiableList(Arrays.asList("REGISTRATION_SUPERVISOR", "REGISTRATION_ADMIN"));
    static final int MAX_ZIP_ENTRIES = 64;
    static final long MAX_ZIP_BYTES = 512L * 1024 * 1024;
    private static final String ANY_VERSION = "0.0.0";
    private static final Pattern SAFE_ENTRY = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

    /** Result of an import. */
    public enum Status {
        /** Verified, stored and running. */
        IMPORTED,
        /** The user may not import models. */
        NOT_ALLOWED,
        /** A liveness capture is in progress; try again when it has ended. */
        BUSY,
        /** A vendor engine is configured; its models are not managed by the app. */
        NOT_SUPPORTED,
        /** Nothing readable at the location, or no manifest in it. */
        NOT_FOUND,
        /** Signature, manifest or model hash check failed. */
        INVALID,
        /** Older than or equal to the running bundle, below the floor or below {@code model.min_version}. */
        TOO_OLD,
        /** The verified models could not start (runtime or self-test failure). */
        LOAD_FAILED
    }

    /** The running engine, as the importer needs it ({@link #over(EngineLoader)} for the app's loader). */
    public interface Engines {

        /**
         * Tells whether the configured engine is the built-in ONNX engine.
         *
         * @return {@code true} when its models can be imported
         */
        boolean supportsModelImport();

        /**
         * Tells whether a liveness capture screen is open.
         *
         * @return {@code true} while a capture is in progress; models are not swapped then
         */
        default boolean captureInProgress() {
            return false;
        }

        /**
         * Returns the version of the bundle the loaded engine runs.
         *
         * @return the version; empty before the engine is loaded
         */
        Optional<String> bundleVersion();

        /**
         * Builds and self-tests an engine over a verified bundle and swaps it in.
         *
         * @param source the bundle in the store
         * @throws EngineException the load or self-test error; the running engine is unchanged
         */
        void switchModels(ModelSource source) throws EngineException;
    }

    private final ConfigSource config;
    private final ModelBundleStore store;
    private final Supplier<ModelSource> shipped;
    private final Engines engines;
    private final AuditSink audit;
    private final Clock clock;
    private List<PublicKey> trustAnchors = Collections.emptyList();

    /**
     * Creates the importer.
     *
     * @param config  liveness configuration ({@code model.min_version})
     * @param store   store of imported bundles, the one the engine loader reads
     * @param shipped the bundle in the APK, used while nothing was imported
     * @param engines the running engine
     * @param audit   audit sink
     * @param clock   clock of the audit events
     */
    public ModelBundleImporter(ConfigSource config, ModelBundleStore store, Supplier<ModelSource> shipped,
                               Engines engines, AuditSink audit, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.shipped = Objects.requireNonNull(shipped, "shipped");
        this.engines = Objects.requireNonNull(engines, "engines");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Adapts the app's engine loader.
     *
     * @param loader the loader
     * @return the adapter
     */
    public static Engines over(EngineLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return new Engines() {
            @Override
            public boolean supportsModelImport() {
                return loader.supportsModelImport();
            }

            @Override
            public boolean captureInProgress() {
                return loader.captureInProgress();
            }

            @Override
            public Optional<String> bundleVersion() {
                return loader.bundleVersion();
            }

            @Override
            public void switchModels(ModelSource source) throws EngineException {
                loader.switchModels(source);
            }
        };
    }

    /**
     * Replaces the trust anchors (tests sign bundles with a key generated at run time).
     *
     * @param anchors accepted signing keys; empty means the configured keys ({@code model.trust_anchor_pem})
     */
    void setTrustAnchors(List<PublicKey> anchors) {
        this.trustAnchors = new ArrayList<>(anchors);
    }

    /**
     * Returns the store of imported bundles.
     *
     * @return the store
     */
    public ModelBundleStore store() {
        return store;
    }

    /**
     * Describes the bundle the engine runs or would load.
     *
     * @return name, version and whether it was imported; empty when no bundle can be read
     */
    public Optional<Result> current() {
        Optional<ModelBundleStore.Installed> imported = store.active();
        try {
            ModelRegistry reg = open(activeSource(), ANY_VERSION);
            return Optional.of(new Result(Status.IMPORTED, reg.bundleName(), reg.bundleVersion(), "", reg.models(),
                    imported.isPresent()));
        } catch (EngineException e) {
            return Optional.empty();
        }
    }

    /**
     * Verifies a bundle, stores it and switches the engine to it. Blocks for the engine build (seconds); call it
     * off the main thread.
     *
     * @param location folder with {@code manifest.json}, {@code manifest.jws} and the model files, or a {@code .zip}
     *                 file with the same content
     * @param userId   user who imports (for Logcat; ARC's audit service records the session user)
     * @param roles    roles of that user
     * @return the result; never throws for a bad bundle
     */
    public synchronized Result importBundle(Path location, String userId, Collection<String> roles) {
        Objects.requireNonNull(location, "location");
        if (roles == null || Collections.disjoint(roles, ALLOWED_ROLES)) {
            Log.i(TAG, "Liveness model import refused for " + userId + ": role not allowed");
            return Result.of(Status.NOT_ALLOWED);
        }
        if (engines.captureInProgress()) {
            Log.i(TAG, "Liveness model import refused for " + userId + ": a capture is in progress");
            return Result.of(Status.BUSY);
        }
        if (!engines.supportsModelImport()) {
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
            Log.e(TAG, "Liveness model import: bundle could not be read (" + e.getClass().getSimpleName() + ")");
            return Result.of(Status.NOT_FOUND);
        } finally {
            if (staging != null) {
                ModelBundleStore.deleteTree(staging);
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
        String floor = highest(previous, store.floor().orElse(null));
        SemanticVersion version = SemanticVersion.parse(incoming.bundleVersion());
        boolean belowMinimum = !version.isAtLeast(minimumVersion());
        if (belowMinimum || (floor != null && version.compareTo(SemanticVersion.parse(floor)) <= 0)) {
            floor = belowMinimum ? configuredMinimum() : floor;
            Log.e(TAG, "Liveness model import refused: bundle " + incoming.bundleVersion() + " is not newer than "
                    + floor);
            auditIntegrity(incoming.bundleName());
            return new Result(Status.TOO_OLD, incoming.bundleName(), incoming.bundleVersion(), floor,
                    incoming.models(), false);
        }
        try {
            incoming.verifyAll();
        } catch (EngineException e) {
            return refused(e, incoming.bundleName());
        }
        Path folder = store.newBundleFolder(incoming.bundleName(), incoming.bundleVersion());
        Properties before = null;
        boolean switched = false;
        try {
            copyBundle(source, folder, incoming);
            ModelRegistry stored = open(ModelSources.directory(folder.toFile()), configuredMinimum());
            stored.verifyAll();
            before = store.activate(new ModelBundleStore.Installed(folder, stored.bundleName(),
                    stored.bundleVersion()));
            engines.switchModels(ModelSources.directory(folder.toFile()));
            switched = true;
            store.prune();
            audit.emit(new AuditPayloadBuilder(Workflow.REGISTRATION, "", clock).configApplied("model.bundle="
                    + stored.bundleName() + ":" + (previous == null ? "none" : previous) + "->"
                    + stored.bundleVersion()));
            Log.i(TAG, "Liveness models " + stored.bundleName() + " " + stored.bundleVersion() + " imported by "
                    + userId + " (previous " + previous + ")");
            return new Result(Status.IMPORTED, stored.bundleName(), stored.bundleVersion(),
                    previous == null ? "" : previous, stored.models(), true);
        } catch (EngineException e) {
            if (e.getErrorCode().isModelIntegrityFailure()) {
                return refused(e, incoming.bundleName());
            }
            Log.e(TAG, "Liveness model import: verified models could not start (" + e.getErrorCode() + ")");
            audit.emit(new AuditPayloadBuilder(Workflow.REGISTRATION, "", clock).engineError(e.getErrorCode(),
                    e.getClass().getSimpleName()));
            return new Result(Status.LOAD_FAILED, incoming.bundleName(), incoming.bundleVersion(),
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
        Log.e(TAG, "Liveness model import refused: " + e.getErrorCode() + " (" + e.getMessage() + ")");
        auditIntegrity(bundle);
        if (e.getErrorCode() == EngineErrorCode.MODEL_VERSION) {
            return Result.of(Status.TOO_OLD);
        }
        return Result.of(Status.INVALID);
    }

    private void auditIntegrity(String bundle) {
        audit.emit(new AuditPayloadBuilder(Workflow.REGISTRATION, "", clock).modelIntegrityFailed(bundle));
    }

    private String configuredMinimum() {
        return new PolicyResolver(config).resolve(Workflow.REGISTRATION).policy().modelMinVersion();
    }

    private SemanticVersion minimumVersion() {
        try {
            return SemanticVersion.parse(configuredMinimum());
        } catch (IllegalArgumentException e) {
            return SemanticVersion.parse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION);
        }
    }

    private ModelSource activeSource() {
        Optional<ModelBundleStore.Installed> imported = store.active();
        return imported.isPresent() ? ModelSources.directory(imported.get().folder().toFile()) : shipped.get();
    }

    private ModelRegistry open(ModelSource source, String minimum) throws EngineException {
        ModelRegistry.Builder b = ModelRegistry.builder(source).minimumVersion(minimum);
        for (PublicKey key : trustAnchors.isEmpty() ? ModelTrustAnchors.configured(config) : trustAnchors) {
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
            return open(activeSource(), ANY_VERSION).bundleVersion();
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
                try (OutputStream out = Files.newOutputStream(to)) {
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
        try (DirectoryStream<Path> children = Files.newDirectoryStream(extracted)) {
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

        Result(Status status, String bundle, String version, String previousVersion, List<ModelInfo> models,
               boolean imported) {
            this.status = Objects.requireNonNull(status, "status");
            this.bundle = bundle;
            this.version = version;
            this.previousVersion = previousVersion;
            this.models = Collections.unmodifiableList(new ArrayList<>(models));
            this.imported = imported;
        }

        /**
         * Creates a result without bundle details.
         *
         * @param status the status
         * @return the result
         */
        public static Result of(Status status) {
            return new Result(status, "", "", "", Collections.<ModelInfo>emptyList(), false);
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
         * Tells whether the bundle comes from the model store rather than the APK.
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
