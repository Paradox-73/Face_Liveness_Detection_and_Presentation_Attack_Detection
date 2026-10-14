/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter.Result;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter.Status;
import io.mosip.liveness.onnx.model.ManifestSignature;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelManifest;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;

/**
 * Online model update ({@link ModelBundleImporter#updateFromServer}): bundles signed by a key generated for the test
 * are served by a local HTTP server on a random loopback port, and the download goes through the offline import
 * checks.
 */
class ModelBundleUpdateTest {

    private static final List<String> SUPERVISOR = Collections.singletonList("REGISTRATION_SUPERVISOR");
    private static KeyPair trusted;
    private static KeyPair untrusted;

    @TempDir
    Path tmp;

    private final Map<String, String> config = new HashMap<>();
    private final List<LivenessAuditEvent> audits = new ArrayList<>();
    private final List<ModelSource> switched = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile byte[] served;
    private volatile int servedStatus = 200;
    private HttpServer server;
    private ModelBundleStore store;
    private ModelBundleImporter importer;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        trusted = g.generateKeyPair();
        untrusted = g.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/bundle.zip", exchange -> {
            requests.incrementAndGet();
            byte[] body = served == null ? new byte[0] : served;
            exchange.sendResponseHeaders(servedStatus, servedStatus == 200 ? body.length : -1);
            try (OutputStream out = exchange.getResponseBody()) {
                if (servedStatus == 200) {
                    out.write(body);
                }
            }
        });
        server.createContext("/moved.zip", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", url());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        config.put(LivenessConfigKeys.MODEL_MIN_VERSION, "1.0.0");
        config.put(LivenessConfigKeys.MODEL_UPDATE_URL, url());
        store = new ModelBundleStore(tmp.resolve("store"));
        ModelSource shipped = ModelSources.directory(bundle("builtin", "1.0.0", trusted).toFile());
        importer = new ModelBundleImporter(key -> Optional.ofNullable(config.get(key)), () -> store, () -> shipped,
                new ModelBundleImporter.Engines() {
                    @Override
                    public boolean supportsModelImport() {
                        return true;
                    }

                    @Override
                    public Optional<String> bundleVersion() {
                        return Optional.empty();
                    }

                    @Override
                    public void switchModels(ModelSource source) {
                        switched.add(source);
                    }
                }, audits::add, new Clock() {
                    @Override
                    public long monotonicNanos() {
                        return 0;
                    }

                    @Override
                    public Instant now() {
                        return Instant.EPOCH;
                    }
                }, new ModelBundleImporter.Log() {
                    @Override
                    public void info(String message) {
                    }

                    @Override
                    public void error(String message) {
                    }
                });
        importer.setTrustAnchors(Collections.singletonList(trusted.getPublic()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void signedBundleServedOverHttpIsInstalled() throws Exception {
        served = zip(bundle("v110", "1.1.0", trusted));

        Result r = importer.updateFromServer("supervisor1", SUPERVISOR);

        assertEquals(Status.IMPORTED, r.status());
        assertEquals("1.1.0", r.version());
        assertEquals("1.0.0", r.previousVersion());
        assertEquals("1.1.0", store.active().get().version());
        assertEquals(1, switched.size());
        assertTrue(audited(AuditEventId.LIVENESS_CONFIG_APPLIED), "LV15 like an offline import");
    }

    @Test
    void tamperedBundleIsRefusedAndTheModelsStay() throws Exception {
        Path bundle = bundle("tampered", "1.1.0", trusted);
        byte[] a = Files.readAllBytes(bundle.resolve("a.onnx"));
        a[0] ^= 1;
        Files.write(bundle.resolve("a.onnx"), a);
        served = zip(bundle);

        assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.INVALID);
        assertTrue(audited(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED), "LV14 like an offline import");
    }

    @Test
    void unsignedOrForeignBundleIsRefused() throws Exception {
        Path unsigned = bundle("unsigned", "1.1.0", trusted);
        Files.delete(unsigned.resolve(ModelRegistry.SIGNATURE_FILE));
        served = zip(unsigned);
        assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.INVALID);

        served = zip(bundle("foreign", "1.1.0", untrusted));
        assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.INVALID);
    }

    @Test
    void lowerVersionIsRefusedAsRollback() throws Exception {
        served = zip(bundle("v120", "1.2.0", trusted));
        assertEquals(Status.IMPORTED, importer.updateFromServer("supervisor1", SUPERVISOR).status());

        served = zip(bundle("v110", "1.1.0", trusted));
        Result r = importer.updateFromServer("supervisor1", SUPERVISOR);

        assertEquals(Status.TOO_OLD, r.status());
        assertEquals("1.2.0", r.previousVersion());
        assertEquals("1.2.0", store.active().get().version());
        assertEquals(1, switched.size());
    }

    @Test
    void emptyUrlTurnsTheUpdateOff() {
        config.put(LivenessConfigKeys.MODEL_UPDATE_URL, " ");
        assertFalse(importer.updateUrl().isPresent());
        assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.NOT_FOUND);
        config.remove(LivenessConfigKeys.MODEL_UPDATE_URL);
        assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.NOT_FOUND);
        assertEquals(0, requests.get(), "nothing is downloaded");
    }

    @Test
    void officerCannotStartADownload() throws Exception {
        served = zip(bundle("v110", "1.1.0", trusted));
        assertRefused(importer.updateFromServer("officer1", Collections.singletonList("REGISTRATION_OFFICER")),
                Status.NOT_ALLOWED);
        assertEquals(0, requests.get());
    }

    @Test
    void plainHttpToAnotherHostIsRefusedBeforeConnecting() {
        Path target = tmp.resolve("download.zip");
        for (String url : Arrays.asList("http://example.invalid/bundle.zip", "ftp://127.0.0.1/bundle.zip")) {
            IOException e = assertThrows(IOException.class, () -> ModelBundleImporter.download(url, target));
            assertTrue(e.getMessage().contains("https"), e.getMessage());
            config.put(LivenessConfigKeys.MODEL_UPDATE_URL, url);
            assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.NOT_FOUND);
        }
    }

    @Test
    void errorAnswerIsNotFound() throws Exception {
        served = zip(bundle("v110", "1.1.0", trusted));
        servedStatus = 404;
        assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.NOT_FOUND);
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        served = zip(bundle("v110", "1.1.0", trusted));
        config.put(LivenessConfigKeys.MODEL_UPDATE_URL, url().replace("/bundle.zip", "/moved.zip"));
        assertRefused(importer.updateFromServer("supervisor1", SUPERVISOR), Status.NOT_FOUND);
        assertEquals(1, requests.get(), "only the redirect was requested");
    }

    @Test
    void onlyLoopbackHostsMayUsePlainHttp() {
        assertTrue(ModelBundleImporter.isLoopback("localhost"));
        assertTrue(ModelBundleImporter.isLoopback("127.0.0.1"));
        assertTrue(ModelBundleImporter.isLoopback("[::1]"));
        assertFalse(ModelBundleImporter.isLoopback("10.0.2.2"));
        assertFalse(ModelBundleImporter.isLoopback("127.0.0.1.nip.io"), "a host name is never resolved");
        assertFalse(ModelBundleImporter.isLoopback("mosip.example.org"));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/bundle.zip";
    }

    private boolean audited(AuditEventId id) {
        return audits.stream().anyMatch(e -> e.eventId() == id);
    }

    private void assertRefused(Result r, Status expected) {
        assertEquals(expected, r.status());
        assertTrue(switched.isEmpty(), "the engine is not switched");
        assertFalse(store.active().isPresent(), "the shipped models stay in use");
    }

    /** Writes a two-model bundle with a signed manifest. */
    private Path bundle(String name, String version, KeyPair signer) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        byte[] a = ("model-a-" + version).getBytes(StandardCharsets.UTF_8);
        byte[] b = ("model-b-" + version).getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("a.onnx"), a);
        Files.write(dir.resolve("b.onnx"), b);
        ModelManifest manifest = ModelManifest.of("test-bundle", version, Arrays.asList(
                ModelEntry.of("a", "a.onnx", "1", sha256(a), a.length, "MIT", "test",
                        Collections.<String, String>emptyMap()),
                ModelEntry.of("b", "b.onnx", "2", sha256(b), b.length, "MIT", "test",
                        Collections.<String, String>emptyMap())));
        byte[] json = manifest.toJson().getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve(ModelRegistry.MANIFEST_FILE), json);
        Files.write(dir.resolve(ModelRegistry.SIGNATURE_FILE), ManifestSignature
                .sign(json, signer.getPrivate(), signer.getPublic()).getBytes(StandardCharsets.US_ASCII));
        return dir;
    }

    private static byte[] zip(Path bundle) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String file : Arrays.asList(ModelRegistry.MANIFEST_FILE, ModelRegistry.SIGNATURE_FILE, "a.onnx",
                    "b.onnx")) {
                if (Files.exists(bundle.resolve(file))) {
                    out.putNextEntry(new ZipEntry(file));
                    out.write(Files.readAllBytes(bundle.resolve(file)));
                    out.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
