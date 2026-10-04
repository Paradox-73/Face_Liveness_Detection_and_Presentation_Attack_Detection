/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.onnx.model.ManifestSignature;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelManifest;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.registration.livenessmanager.engine.ModelBundleImporter.Result;
import io.mosip.registration.livenessmanager.engine.ModelBundleImporter.Status;

/**
 * Secure offline model update on Android (bonus B6). The bundles are built the way
 * {@code scripts/make-sample-model-bundles.sh} builds its samples: the model files of the repository's
 * {@code models/} folder with the manifest re-signed by a key generated for this run (no private key is stored),
 * a tampered copy with one byte of {@code MiniFASNetV2.onnx} changed at offset 4096, and bundles at other versions.
 * The engine switch is scripted (ONNX Runtime does not run in a JVM unit test); the signature, hash and version
 * checks are the engine's own {@link ModelRegistry}.
 */
@RunWith(RobolectricTestRunner.class)
public class ModelBundleImporterTest {

    private static final String TAMPER_FILE = "MiniFASNetV2.onnx";
    private static final long TAMPER_OFFSET = 4096;
    private static final List<String> SUPERVISOR = Arrays.asList("REGISTRATION_OFFICER", "REGISTRATION_SUPERVISOR");

    /** Sample bundles shared by the tests (a class rule runs too late under Robolectric). */
    private static Path bundles;
    private static KeyPair trusted;
    private static KeyPair untrusted;
    private static Path shipped;
    private static Path valid;
    private static Path newer;
    private static Path tampered;
    private static Path wrongKey;
    private static Path older;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, String> values = new HashMap<>();
    private final List<LivenessAuditEvent> audits = new ArrayList<>();
    private final List<ModelSource> switched = new ArrayList<>();
    private EngineException switchFailure;
    private boolean captureOpen;
    private String runningVersion;
    private ModelBundleStore store;
    private ModelBundleImporter importer;

    @BeforeClass
    public static void sampleBundles() throws Exception {
        File models = repositoryModels();
        Assume.assumeTrue("repository models/ folder not found", models != null);
        bundles = Files.createTempDirectory("liveness-sample-bundles");
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        trusted = g.generateKeyPair();
        untrusted = g.generateKeyPair();
        shipped = bundle(models, "shipped", "1.3.0", trusted);
        valid = bundle(models, "valid", "1.4.0", trusted);
        newer = bundle(models, "newer", "1.5.0", trusted);
        tampered = bundle(models, "tampered", "1.4.0", trusted);
        flipByte(tampered.resolve(TAMPER_FILE), TAMPER_OFFSET);
        wrongKey = bundle(models, "wrong-key", "1.4.0", untrusted);
        older = bundle(models, "older", "1.2.0", trusted);
    }

    @AfterClass
    public static void deleteSampleBundles() {
        if (bundles != null) {
            ModelBundleStore.deleteTree(bundles);
        }
    }

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("repository models/ folder not found", shipped != null);
        runningVersion = "1.3.0";
        store = new ModelBundleStore(tmp.newFolder("store").toPath());
        ModelSource builtIn = ModelSources.directory(shipped.toFile());
        importer = new ModelBundleImporter(ConfigSource.of(values), store, () -> builtIn,
                new ModelBundleImporter.Engines() {
                    @Override
                    public boolean supportsModelImport() {
                        return true;
                    }

                    @Override
                    public boolean captureInProgress() {
                        return captureOpen;
                    }

                    @Override
                    public Optional<String> bundleVersion() {
                        return Optional.ofNullable(runningVersion);
                    }

                    @Override
                    public void switchModels(ModelSource source) throws EngineException {
                        if (switchFailure != null) {
                            throw switchFailure;
                        }
                        switched.add(source);
                        runningVersion = ModelRegistry.builder(source).trustAnchor(trusted.getPublic()).open()
                                .bundleVersion();
                    }
                }, audits::add, Clock.system());
        importer.setTrustAnchors(Collections.singletonList(trusted.getPublic()));
    }

    @Test
    public void validBundleIsVerifiedStoredAndActivated() throws Exception {
        Result r = importer.importBundle(valid, "supervisor1", SUPERVISOR);

        assertEquals(Status.IMPORTED, r.status());
        assertTrue(r.isImported());
        assertEquals("1.4.0", r.version());
        assertEquals("1.3.0", r.previousVersion());
        assertEquals(1, switched.size());
        assertEquals("1.4.0", store.active().get().version());
        assertEquals("the floor follows the active version", "1.4.0", store.floor().get());
        ModelRegistry stored = ModelRegistry.builder(ModelSources.directory(store.active().get().folder().toFile()))
                .trustAnchor(trusted.getPublic()).open();
        stored.verifyAll();
        assertFalse("only the manifest, its signature and the listed models are copied",
                Files.exists(store.active().get().folder().resolve("SHA256SUMS")));
        assertEquals("REG-EVT-LV15", lastAudit().eventId().id());
        assertTrue(lastAudit().attributes().values().toString().contains("1.3.0->1.4.0"));
        assertEquals("1.4.0", importer.current().get().version());
        assertTrue(importer.current().get().isFromStore());
    }

    @Test
    public void previousBundleIsKeptForRollbackAndOlderImportsArePruned() throws Exception {
        assertEquals(Status.IMPORTED, importer.importBundle(valid, "supervisor1", SUPERVISOR).status());
        Path first = store.active().get().folder();
        assertEquals(Status.IMPORTED, importer.importBundle(newer, "supervisor1", SUPERVISOR).status());

        assertEquals("1.5.0", store.active().get().version());
        assertEquals(first, store.previous().get().folder());
        assertTrue(Files.isDirectory(first));
        assertEquals(2, bundleFolders());
    }

    @Test
    public void importIsRefusedAsBusyWhileACaptureIsInProgress() throws Exception {
        captureOpen = true;

        Result r = importer.importBundle(valid, "supervisor1", SUPERVISOR);

        assertEquals(Status.BUSY, r.status());
        assertTrue("nothing was switched or stored", switched.isEmpty() && !store.active().isPresent());
        assertEquals("1.3.0", runningVersion);

        captureOpen = false;
        assertEquals("the same bundle imports once the capture has ended", Status.IMPORTED,
                importer.importBundle(valid, "supervisor1", SUPERVISOR).status());
    }

    @Test
    public void zipFileIsImported() throws Exception {
        Result r = importer.importBundle(zip(valid, "bundle/"), "supervisor1", SUPERVISOR);
        assertEquals(Status.IMPORTED, r.status());
        assertEquals("1.4.0", store.active().get().version());
    }

    @Test
    public void tamperedModelFileIsRefused() throws Exception {
        Result r = importer.importBundle(tampered, "supervisor1", SUPERVISOR);

        assertRefused(r, Status.INVALID);
        assertEquals("REG-EVT-LV14", lastAudit().eventId().id());
    }

    @Test
    public void bundleSignedByAnotherKeyIsRefused() throws Exception {
        Result r = importer.importBundle(wrongKey, "supervisor1", SUPERVISOR);

        assertRefused(r, Status.INVALID);
        assertEquals("", r.version());
        assertEquals("REG-EVT-LV14", lastAudit().eventId().id());
    }

    @Test
    public void olderVersionIsRefused() throws Exception {
        Result r = importer.importBundle(older, "supervisor1", SUPERVISOR);

        assertRefused(r, Status.TOO_OLD);
        assertEquals("1.2.0", r.version());
        assertEquals("below model.min_version", "1.3.0", r.previousVersion());
        assertEquals("REG-EVT-LV14", lastAudit().eventId().id());
    }

    @Test
    public void versionNotNewerThanTheActiveBundleIsRefused() throws Exception {
        values.put("mosip.registration.liveness.model.min_version", "1.0.0");
        assertRefused(importer.importBundle(shipped, "supervisor1", SUPERVISOR), Status.TOO_OLD);
        assertEquals("older than the running bundle, though above model.min_version", Status.TOO_OLD,
                importer.importBundle(older, "supervisor1", SUPERVISOR).status());

        assertEquals(Status.IMPORTED, importer.importBundle(newer, "supervisor1", SUPERVISOR).status());
        runningVersion = null; // the floor alone still refuses a rollback
        Result back = importer.importBundle(valid, "supervisor1", SUPERVISOR);
        assertEquals(Status.TOO_OLD, back.status());
        assertEquals("1.5.0", back.previousVersion());
        assertEquals("1.5.0", store.active().get().version());
    }

    @Test
    public void failedSelfTestLeavesTheOldBundleActive() throws Exception {
        assertEquals(Status.IMPORTED, importer.importBundle(valid, "supervisor1", SUPERVISOR).status());
        Path active = store.active().get().folder();
        switchFailure = new EngineException(EngineErrorCode.MODEL_LOAD, "self-test failed");

        Result r = importer.importBundle(newer, "supervisor1", SUPERVISOR);

        assertEquals(Status.LOAD_FAILED, r.status());
        assertEquals("1.4.0", store.active().get().version());
        assertEquals(active, store.active().get().folder());
        assertEquals("1.4.0", store.floor().get());
        assertEquals("the new bundle's folder is removed", 1, bundleFolders());
        assertEquals("1.4.0", runningVersion);
        assertEquals("REG-EVT-LV13", lastAudit().eventId().id());
    }

    @Test
    public void failedSelfTestOfTheFirstImportKeepsTheApkBundle() throws Exception {
        switchFailure = new EngineException(EngineErrorCode.NOT_READY, "engine reports not ready");

        Result r = importer.importBundle(valid, "supervisor1", SUPERVISOR);

        assertEquals(Status.LOAD_FAILED, r.status());
        assertFalse(store.active().isPresent());
        assertFalse("no floor is raised", store.floor().isPresent());
        assertEquals(0, bundleFolders());
    }

    @Test
    public void officerMayNotImport() throws Exception {
        Result r = importer.importBundle(valid, "officer1", Collections.singletonList("REGISTRATION_OFFICER"));
        assertRefused(r, Status.NOT_ALLOWED);
        assertTrue(audits.isEmpty());
    }

    @Test
    public void folderWithoutManifestIsNotFound() throws Exception {
        assertRefused(importer.importBundle(tmp.newFolder("empty").toPath(), "supervisor1", SUPERVISOR),
                Status.NOT_FOUND);
    }

    @Test
    public void engineLoaderSwitchKeepsTheRunningEngineWhenTheSelfTestFails() throws Exception {
        LivenessEngine first = new StubEngine(true);
        LivenessEngine broken = new StubEngine(false);
        LivenessEngine second = new StubEngine(true);
        List<LivenessEngine> builds = new ArrayList<>(Arrays.asList(broken, second));
        EngineLoader loader = new EngineLoader(() -> first, source -> builds.remove(0));
        assertSame(first, loader.await(5_000));
        assertTrue(loader.supportsModelImport());

        try {
            loader.switchModels(ModelSources.directory(valid.toFile()));
            fail("expected EngineException");
        } catch (EngineException e) {
            assertEquals(EngineErrorCode.NOT_READY, e.getErrorCode());
        }
        assertSame("self-test failed: old engine still runs", first, loader.await(5_000));
        assertTrue(((StubEngine) broken).closed);

        loader.switchModels(ModelSources.directory(valid.toFile()));
        assertSame(second, loader.await(5_000));
        assertTrue("old engine closed after the switch", ((StubEngine) first).closed);
    }

    @Test
    public void loaderWithoutModelBuilderRefusesTheSwitch() throws Exception {
        EngineLoader loader = new EngineLoader(() -> new StubEngine(true));
        assertFalse(loader.supportsModelImport());
        try {
            loader.switchModels(ModelSources.directory(valid.toFile()));
            fail("expected EngineException");
        } catch (EngineException e) {
            assertEquals(EngineErrorCode.CONFIG_INVALID, e.getErrorCode());
        }
    }

    @Test
    public void onlyTheBuiltInEngineSupportsModelImport() {
        EngineLoader builtIn = new EngineLoader(() -> new StubEngine(true), source -> new StubEngine(true));
        Map<String, String> vendor = new HashMap<>();
        vendor.put(LivenessConfigKeys.ENGINE, "vendor-engine");
        assertFalse(EngineLoader.select(ConfigSource.of(vendor), builtIn,
                () -> Collections.<LivenessEngineProvider>emptyList(), () -> null).supportsModelImport());
        assertTrue(EngineLoader.select(ConfigSource.of(values), builtIn,
                () -> Collections.<LivenessEngineProvider>emptyList(), () -> null).supportsModelImport());
    }

    @Test
    public void floorRaisesTheMinimumVersionOfTheEngine() throws Exception {
        ConfigSource config = ConfigSource.of(values);
        assertEquals("1.3.0", EngineLoader.minimumModelVersion(config, null));
        assertEquals("1.3.0", EngineLoader.minimumModelVersion(config, store));
        assertEquals(Status.IMPORTED, importer.importBundle(valid, "supervisor1", SUPERVISOR).status());
        assertEquals("1.4.0", EngineLoader.minimumModelVersion(config, store));
    }

    private void assertRefused(Result r, Status expected) {
        assertEquals(expected, r.status());
        assertFalse(r.isImported());
        assertTrue("the engine is not switched", switched.isEmpty());
        assertFalse("nothing becomes active", store.active().isPresent());
    }

    private LivenessAuditEvent lastAudit() {
        assertFalse("an audit event was written", audits.isEmpty());
        return audits.get(audits.size() - 1);
    }

    private int bundleFolders() throws IOException {
        if (!Files.isDirectory(store.root())) {
            return 0;
        }
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

    private Path zip(Path bundle, String prefix) throws IOException {
        Path zip = tmp.getRoot().toPath().resolve(bundle.getFileName() + ".zip");
        List<String> files = new ArrayList<>(Arrays.asList(ModelRegistry.MANIFEST_FILE,
                ModelRegistry.SIGNATURE_FILE));
        for (ModelEntry e : ModelManifest.parse(Files.readAllBytes(bundle.resolve(ModelRegistry.MANIFEST_FILE)))
                .models()) {
            files.add(e.file());
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String file : files) {
                out.putNextEntry(new ZipEntry(prefix + file));
                out.write(Files.readAllBytes(bundle.resolve(file)));
                out.closeEntry();
            }
        }
        return zip;
    }

    /** The repository's {@code models/} folder, found upwards from the test's working directory. */
    private static File repositoryModels() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File models = new File(dir, "models");
            if (new File(models, ModelRegistry.MANIFEST_FILE).isFile() && new File(dir, "liveness").isDirectory()) {
                return models;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /** The model files of {@code models/} with the manifest at another version, signed by {@code signer}. */
    private static Path bundle(File models, String name, String version, KeyPair signer) throws Exception {
        Path dir = Files.createDirectory(bundles.resolve(name));
        ModelManifest shippedManifest = ModelManifest.parse(Files.readAllBytes(
                new File(models, ModelRegistry.MANIFEST_FILE).toPath()));
        for (ModelEntry e : shippedManifest.models()) {
            Path to = dir.resolve(e.file());
            Files.createDirectories(to.getParent());
            Files.copy(new File(models, e.file()).toPath(), to);
        }
        Files.write(dir.resolve("SHA256SUMS"), "not part of the signed bundle\n".getBytes(StandardCharsets.UTF_8));
        ModelManifest manifest = ModelManifest.of(shippedManifest.bundle(), version, shippedManifest.models(),
                shippedManifest.engineParams());
        byte[] json = manifest.toJson().getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve(ModelRegistry.MANIFEST_FILE), json);
        Files.write(dir.resolve(ModelRegistry.SIGNATURE_FILE), ManifestSignature
                .sign(json, signer.getPrivate(), signer.getPublic()).getBytes(StandardCharsets.US_ASCII));
        return dir;
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.seek(offset);
            int b = f.read();
            f.seek(offset);
            f.write(b ^ 0xFF);
        }
    }

    private static final class StubEngine implements LivenessEngine {

        private final boolean ready;
        private boolean closed;

        StubEngine(boolean ready) {
            this.ready = ready;
        }

        @Override
        public EngineInfo info() {
            return EngineInfo.builder("stub", "1").build();
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public LivenessSession open(SessionRequest request, LivenessListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
