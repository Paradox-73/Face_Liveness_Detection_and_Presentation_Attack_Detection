/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpServer;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter.Result;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter.Status;
import io.mosip.liveness.onnx.bundle.ModelBundleStore;
import io.mosip.liveness.onnx.model.ManifestSignature;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelManifest;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.model.ModelTrustAnchors;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingAudit;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * Tests {@link LivenessModelImporter} and {@link ModelBundleStore} (secure offline model update, B6) with small
 * synthetic bundles signed by a key generated for the test: signature, hash and version checks, anti-rollback,
 * zip handling, atomic switch and roll back when the new engine cannot start, and the audits.
 */
public class LivenessModelImporterTest {

	private static final List<String> SUPERVISOR = Collections.singletonList("REGISTRATION_SUPERVISOR");
	private static KeyPair trusted;
	private static KeyPair untrusted;

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final Map<String, Object> values = new HashMap<>();
	private final RecordingAudit audit = new RecordingAudit();
	private final List<ModelSource> switched = new ArrayList<>();
	private LivenessEngineRegistry registry;
	private ModelBundleStore store;
	private LivenessModelImporter importer;
	private EngineException switchFailure;

	@BeforeClass
	public static void keys() throws NoSuchAlgorithmException {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		trusted = g.generateKeyPair();
		untrusted = g.generateKeyPair();
	}

	@Before
	public void setUp() throws Exception {
		// the import rules are tested against a configured minimum of 1.0.0, below the shipped default
		values.put("mosip.registration.liveness.model.min_version", "1.0.0");
		LivenessConfig config = LivenessConfig.of(values);
		registry = new LivenessEngineRegistry(config, new DesktopImageDecoder(),
				new DesktopAuditSink(Mockito.mock(AuditManagerService.class), Runnable::run),
				new DesktopDiagnosticSink());
		store = new ModelBundleStore(tmp.newFolder("store").toPath());
		registry.setModelStore(store);
		registry.setModelSource(ModelSources.directory(bundle("builtin", "1.0.0", trusted).toFile()));
		importer = new LivenessModelImporter(config, registry, null, audit, new TestClock(), source -> {
			if (switchFailure != null) {
				throw switchFailure;
			}
			switched.add(source);
		});
		importer.setTrustAnchors(Collections.singletonList(trusted.getPublic()));
	}

	@Test
	public void configuredProductionKeyIsTheOnlyTrustedKey() throws Exception {
		values.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM, ModelTrustAnchors.toPem(trusted.getPublic()));
		importer.setTrustAnchors(Collections.emptyList());

		assertEquals(Status.INVALID, importer.importBundle(bundle("other", "1.1.0", untrusted), "supervisor1",
				SUPERVISOR).status());
		assertEquals(Status.IMPORTED, importer.importBundle(bundle("v110", "1.1.0", trusted), "supervisor1",
				SUPERVISOR).status());
	}

	@Test
	public void newerSignedBundleIsVerifiedStoredAndSwitchedTo() throws Exception {
		Path bundle = bundle("v110", "1.1.0", trusted);
		Files.write(bundle.resolve("notes.txt"), "not listed".getBytes(StandardCharsets.UTF_8));

		Result r = importer.importBundle(bundle, "supervisor1", SUPERVISOR);

		assertEquals(Status.IMPORTED, r.status());
		assertEquals("1.1.0", r.version());
		assertEquals("1.0.0", r.previousVersion());
		assertEquals(2, r.models().size());
		assertEquals(1, switched.size());
		assertEquals("1.1.0", store.active().get().version());
		assertEquals("1.1.0", store.floor().get());
		Path installed = store.active().get().folder();
		assertTrue(Files.isRegularFile(installed.resolve("sub/b.onnx")));
		assertFalse("files not listed in the manifest are not copied", Files.exists(installed.resolve("notes.txt")));
		assertTrue(audit.has(AuditEventId.LIVENESS_CONFIG_APPLIED.id()));
		assertTrue(audit.events.stream().anyMatch(e -> e.eventId() == AuditEventId.LIVENESS_CONFIG_APPLIED
				&& e.descriptionJson().contains("1.0.0->1.1.0")));
		assertEquals("the engine now loads the stored bundle", "1.1.0",
				ModelRegistry.builder(registry.activeModelSource()).trustAnchor(trusted.getPublic())
						.minimumVersion(registry.minimumModelVersion()).open()
						.bundleVersion());
		assertEquals("the floor becomes the minimum version", "1.1.0", registry.minimumModelVersion());
	}

	@Test
	public void sameVersionIsRefusedAsRollback() throws Exception {
		Result r = importer.importBundle(bundle("same", "1.0.0", trusted), "supervisor1", SUPERVISOR);
		assertRefused(r, Status.TOO_OLD);
		assertEquals("1.0.0", r.previousVersion());
		assertTrue(audit.has(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED.id()));
	}

	@Test
	public void olderBundleIsRefusedAfterANewerOneWasImported() throws Exception {
		assertEquals(Status.IMPORTED,
				importer.importBundle(bundle("v120", "1.2.0", trusted), "supervisor1", SUPERVISOR).status());
		Result r = importer.importBundle(bundle("v110", "1.1.0", trusted), "supervisor1", SUPERVISOR);
		assertEquals(Status.TOO_OLD, r.status());
		assertEquals("1.2.0", r.previousVersion());
		assertEquals("1.2.0", store.active().get().version());
		assertEquals(1, switched.size());
	}

	@Test
	public void bundleBelowTheConfiguredMinimumIsRefused() throws Exception {
		values.put("mosip.registration.liveness.model.min_version", "2.0.0");
		Result r = importer.importBundle(bundle("v150", "1.5.0", trusted), "supervisor1", SUPERVISOR);
		assertRefused(r, Status.TOO_OLD);
		assertEquals("2.0.0", r.previousVersion());
	}

	@Test
	public void modifiedModelFileIsRefused() throws Exception {
		Path bundle = bundle("tampered", "1.1.0", trusted);
		byte[] a = Files.readAllBytes(bundle.resolve("a.onnx"));
		a[0] ^= 1;
		Files.write(bundle.resolve("a.onnx"), a);
		assertRefused(importer.importBundle(bundle, "supervisor1", SUPERVISOR), Status.INVALID);
		assertTrue(audit.has(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED.id()));
		assertEquals("no bundle folder is left in the store", 0, bundleFolders());
	}

	@Test
	public void bundleSignedByAnUnknownKeyIsRefused() throws Exception {
		assertRefused(importer.importBundle(bundle("foreign", "1.1.0", untrusted), "supervisor1", SUPERVISOR),
				Status.INVALID);
	}

	@Test
	public void manifestWithoutSignatureIsRefused() throws Exception {
		Path bundle = bundle("unsigned", "1.1.0", trusted);
		Files.delete(bundle.resolve(ModelRegistry.SIGNATURE_FILE));
		assertRefused(importer.importBundle(bundle, "supervisor1", SUPERVISOR), Status.INVALID);
	}

	@Test
	public void zipFileIsImported() throws Exception {
		Path zip = zip(bundle("zipped", "1.3.0", trusted), "bundle-1.3.0/");
		Result r = importer.importBundle(zip, "supervisor1", SUPERVISOR);
		assertEquals(Status.IMPORTED, r.status());
		assertEquals("1.3.0", store.active().get().version());
	}

	@Test
	public void zipEntryLeavingTheFolderIsRefused() throws Exception {
		Path zip = tmp.getRoot().toPath().resolve("evil.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry("../outside.txt"));
			out.write(1);
			out.closeEntry();
		}
		assertRefused(importer.importBundle(zip, "supervisor1", SUPERVISOR), Status.NOT_FOUND);
		assertFalse(Files.exists(tmp.getRoot().toPath().resolve("outside.txt")));
	}

	@Test
	public void folderWithoutManifestIsNotFound() throws Exception {
		assertRefused(importer.importBundle(tmp.newFolder("empty").toPath(), "supervisor1", SUPERVISOR),
				Status.NOT_FOUND);
	}

	@Test
	public void engineThatCannotStartKeepsThePreviousBundle() throws Exception {
		assertEquals(Status.IMPORTED,
				importer.importBundle(bundle("v110", "1.1.0", trusted), "supervisor1", SUPERVISOR).status());
		switchFailure = new EngineException(EngineErrorCode.MODEL_LOAD, "runtime failed");

		Result r = importer.importBundle(bundle("v120", "1.2.0", trusted), "supervisor1", SUPERVISOR);

		assertEquals(Status.LOAD_FAILED, r.status());
		assertEquals("1.1.0", store.active().get().version());
		assertEquals("1.1.0", store.floor().get());
		assertEquals("the folder of the refused bundle is removed", 1, bundleFolders());
		assertTrue(audit.has(AuditEventId.LIVENESS_ENGINE_ERROR.id()));
	}

	@Test
	public void officerMayNotImport() throws Exception {
		Result r = importer.importBundle(bundle("v110", "1.1.0", trusted), "officer1",
				Collections.singletonList("REGISTRATION_OFFICER"));
		assertRefused(r, Status.NOT_ALLOWED);
	}

	@Test
	public void vendorEngineIsNotSupported() throws Exception {
		values.put("mosip.registration.liveness.engine", "vendor-x");
		assertRefused(importer.importBundle(bundle("v110", "1.1.0", trusted), "supervisor1", SUPERVISOR),
				Status.NOT_SUPPORTED);
	}

	@Test
	public void onlineUpdateDownloadsAndImportsTheSyncedBundle() throws Exception {
		assertFalse("off while the key is not synced", importer.updateUrl().isPresent());
		assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.NOT_FOUND);

		Path tampered = bundle("tampered", "1.2.0", trusted);
		byte[] a = Files.readAllBytes(tampered.resolve("a.onnx"));
		a[0] ^= 1;
		Files.write(tampered.resolve("a.onnx"), a);
		byte[][] served = { Files.readAllBytes(zip(tampered, "")) };
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/v1/syncdata/liveness/model-bundle", exchange -> {
			exchange.sendResponseHeaders(200, served[0].length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(served[0]);
			}
		});
		server.start();
		try {
			values.put(LivenessConfigKeys.MODEL_UPDATE_URL,
					"http://127.0.0.1:" + server.getAddress().getPort() + "/v1/syncdata/liveness/model-bundle");
			assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.INVALID);
			assertTrue(audit.has(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED.id()));

			served[0] = Files.readAllBytes(zip(bundle("online", "1.3.0", trusted), "bundle-1.3.0/"));
			Result r = importer.updateFromServer("supervisor1", SUPERVISOR);
			assertEquals(Status.IMPORTED, r.status());
			assertEquals("1.3.0", store.active().get().version());
			assertTrue(audit.events.stream().anyMatch(e -> e.eventId() == AuditEventId.LIVENESS_CONFIG_APPLIED
					&& e.descriptionJson().contains("1.0.0->1.3.0")));
		} finally {
			server.stop(0);
		}
	}

	@Test
	public void currentDescribesTheRunningBundle() throws Exception {
		Result before = importer.current().get();
		assertEquals("1.0.0", before.version());
		assertFalse(before.isFromStore());
		importer.importBundle(bundle("v110", "1.1.0", trusted), "supervisor1", SUPERVISOR);
		Result after = importer.current().get();
		assertEquals("1.1.0", after.version());
		assertTrue(after.isFromStore());
	}

	@Test
	public void unreadablePointerIsIgnoredAndRestoreRemovesIt() throws Exception {
		Files.write(store.root().resolve(ModelBundleStore.ACTIVE_FILE),
				"directory=../../elsewhere\nversion=9.9.9\nfloor=bad".getBytes(StandardCharsets.UTF_8));
		assertFalse("a pointer outside the store is ignored", store.active().isPresent());
		assertFalse("an invalid floor is ignored", store.floor().isPresent());
		store.restore(new java.util.Properties());
		assertFalse(Files.exists(store.root().resolve(ModelBundleStore.ACTIVE_FILE)));
	}

	private void assertRefused(Result r, Status expected) {
		assertEquals(expected, r.status());
		assertFalse(r.isImported());
		assertTrue("the engine is not switched", switched.isEmpty());
		assertFalse("nothing becomes active", store.active().isPresent());
	}

	private int bundleFolders() throws IOException {
		int n = 0;
		try (DirectoryStream<Path> children = Files.newDirectoryStream(store.root())) {
			for (Path child : children) {
				if (Files.isDirectory(child)) {
					n++;
				}
			}
		}
		return n;
	}

	/** Writes a two-model bundle with a signed manifest. */
	private Path bundle(String name, String version, KeyPair signer) throws Exception {
		Path dir = tmp.newFolder(name).toPath();
		byte[] a = ("model-a-" + version).getBytes(StandardCharsets.UTF_8);
		byte[] b = ("model-b-" + version).getBytes(StandardCharsets.UTF_8);
		Files.write(dir.resolve("a.onnx"), a);
		Files.createDirectories(dir.resolve("sub"));
		Files.write(dir.resolve("sub/b.onnx"), b);
		ModelManifest manifest = ModelManifest.of("test-bundle", version, Arrays.asList(
				ModelEntry.of("a", "a.onnx", "1", sha256(a), a.length, "MIT", "test",
						Collections.<String, String>emptyMap()),
				ModelEntry.of("b", "sub/b.onnx", "2", sha256(b), b.length, "MIT", "test",
						Collections.<String, String>emptyMap())));
		byte[] json = manifest.toJson().getBytes(StandardCharsets.UTF_8);
		Files.write(dir.resolve(ModelRegistry.MANIFEST_FILE), json);
		Files.write(dir.resolve(ModelRegistry.SIGNATURE_FILE), ManifestSignature
				.sign(json, signer.getPrivate(), signer.getPublic()).getBytes(StandardCharsets.US_ASCII));
		return dir;
	}

	private Path zip(Path bundle, String prefix) throws IOException {
		Path zip = tmp.getRoot().toPath().resolve(bundle.getFileName() + ".zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String file : Arrays.asList(ModelRegistry.MANIFEST_FILE, ModelRegistry.SIGNATURE_FILE, "a.onnx",
					"sub/b.onnx")) {
				out.putNextEntry(new ZipEntry(prefix + file));
				out.write(Files.readAllBytes(bundle.resolve(file)));
				out.closeEntry();
			}
		}
		return zip;
	}

	private static String sha256(byte[] data) throws NoSuchAlgorithmException {
		StringBuilder sb = new StringBuilder();
		for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}
}
