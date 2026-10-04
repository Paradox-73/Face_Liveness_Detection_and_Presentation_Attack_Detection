package io.mosip.liveness.onnx.model;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.onnx.TestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed loading of the signed model bundle (T-CORE model integrity tests).
 */
class ModelRegistryTest {

    private static KeyPair signing;
    private static KeyPair other;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        signing = g.generateKeyPair();
        other = g.generateKeyPair();
    }

    /** In-memory bundle: two small "model" files plus a manifest signed with {@link #signing}. */
    private static Map<String, byte[]> bundle(String version) throws Exception {
        byte[] a = "model-a".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "model-b-bytes".getBytes(StandardCharsets.US_ASCII);
        ModelManifest m = ModelManifest.of("test-bundle", version, Arrays.asList(
                ModelEntry.of("a", "a.onnx", "1", Hex.sha256(a), a.length, "MIT", "test", Collections.<String, String>emptyMap()),
                ModelEntry.of("b", "sub/b.onnx", "2", Hex.sha256(b), b.length, "MIT", "test",
                        Collections.<String, String>emptyMap())));
        byte[] json = m.toJson().getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new HashMap<>();
        files.put("a.onnx", a);
        files.put("sub/b.onnx", b);
        files.put(ModelRegistry.MANIFEST_FILE, json);
        files.put(ModelRegistry.SIGNATURE_FILE, ManifestSignature.sign(json, signing.getPrivate(), signing.getPublic())
                .getBytes(StandardCharsets.US_ASCII));
        return files;
    }

    private static ModelSource source(Map<String, byte[]> files) {
        return path -> {
            byte[] data = files.get(path);
            if (data == null) {
                throw new FileNotFoundException(path);
            }
            return data.clone();
        };
    }

    private static ModelRegistry open(Map<String, byte[]> files) throws EngineException {
        return ModelRegistry.builder(source(files)).trustAnchor(signing.getPublic()).open();
    }

    private static EngineErrorCode failure(Map<String, byte[]> files) {
        return assertThrows(EngineException.class, () -> open(files).verifyAll()).getErrorCode();
    }

    @Test
    void validBundleLoadsVerifiedBytes() throws Exception {
        ModelRegistry r = open(bundle("1.3.0"));
        assertEquals("test-bundle", r.bundleName());
        assertEquals("1.3.0", r.bundleVersion());
        assertArrayEquals("model-b-bytes".getBytes(StandardCharsets.US_ASCII), r.load("b"));
        List<ModelInfo> infos = r.models();
        assertEquals(2, infos.size());
        assertEquals("a", infos.get(0).id());
        assertEquals("2", infos.get(1).version());
        assertTrue(r.contains("a"));
        r.verifyAll();
    }

    @Test
    void tamperedModelFailsClosed() throws Exception {
        Map<String, byte[]> files = bundle("1.3.0");
        files.put("a.onnx", "model-A".getBytes(StandardCharsets.US_ASCII));
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, failure(files));
        files.put("a.onnx", "model-a-longer".getBytes(StandardCharsets.US_ASCII));
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, failure(files));
        assertTrue(EngineErrorCode.MODEL_INTEGRITY.isModelIntegrityFailure());
    }

    @Test
    void tamperedManifestFailsClosed() throws Exception {
        Map<String, byte[]> files = bundle("1.3.0");
        String json = new String(files.get(ModelRegistry.MANIFEST_FILE), StandardCharsets.UTF_8);
        files.put(ModelRegistry.MANIFEST_FILE, json.replace("\"1.3.0\"", "\"9.0.0\"").getBytes(StandardCharsets.UTF_8));
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, failure(files));
    }

    @Test
    void missingOrForeignSignatureFailsClosed() throws Exception {
        Map<String, byte[]> files = bundle("1.3.0");
        files.remove(ModelRegistry.SIGNATURE_FILE);
        assertEquals(EngineErrorCode.MODEL_MISSING, failure(files));

        Map<String, byte[]> foreign = bundle("1.3.0");
        byte[] json = foreign.get(ModelRegistry.MANIFEST_FILE);
        foreign.put(ModelRegistry.SIGNATURE_FILE, ManifestSignature.sign(json, other.getPrivate(), other.getPublic())
                .getBytes(StandardCharsets.US_ASCII));
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, failure(foreign));
        // The built-in development key does not accept a bundle signed by another key either.
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, assertThrows(EngineException.class,
                () -> ModelRegistry.open(source(bundle("1.3.0")))).getErrorCode());
    }

    @Test
    void unsignedOrWeakJwsVariantsAreRejected() throws Exception {
        Map<String, byte[]> files = bundle("1.3.0");
        String jws = new String(files.get(ModelRegistry.SIGNATURE_FILE), StandardCharsets.US_ASCII);
        String[] parts = jws.split("\\.", -1);
        String none = b64("{\"alg\":\"none\"}") + ".." + parts[2];
        String hs = b64("{\"alg\":\"HS256\"}") + ".." + parts[2];
        String crit = b64("{\"alg\":\"RS256\",\"crit\":[\"x\"],\"x\":1}") + ".." + parts[2];
        String attached = parts[0] + "." + b64("{}") + "." + parts[2];
        for (String bad : Arrays.asList(none, hs, crit, attached, "garbage", parts[0] + "..", "a.b.c.d", parts[0] + "..!!")) {
            files.put(ModelRegistry.SIGNATURE_FILE, bad.getBytes(StandardCharsets.US_ASCII));
            assertEquals(EngineErrorCode.MODEL_INTEGRITY, failure(files), bad);
        }
        // Trailing line break (as the signer writes it) is fine.
        files.put(ModelRegistry.SIGNATURE_FILE, (jws + "\n").getBytes(StandardCharsets.US_ASCII));
        open(files).verifyAll();
    }

    @Test
    void rollbackBelowMinimumVersionIsBlocked() throws Exception {
        Map<String, byte[]> files = bundle("1.1.0");
        ModelRegistry.builder(source(files)).trustAnchor(signing.getPublic()).minimumVersion("1.1.0").open();
        EngineException e = assertThrows(EngineException.class, () -> ModelRegistry.builder(source(files))
                .trustAnchor(signing.getPublic()).minimumVersion("1.2.0").open());
        assertEquals(EngineErrorCode.MODEL_VERSION, e.getErrorCode());
        assertEquals(EngineErrorCode.CONFIG_INVALID, assertThrows(EngineException.class, () -> ModelRegistry
                .builder(source(files)).trustAnchor(signing.getPublic()).minimumVersion("latest").open()).getErrorCode());
    }

    @Test
    void bundlesSignedBeforeTheDefaultMinimumAreBlocked() throws Exception {
        assertEquals("1.3.0", LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION);
        for (String old : Arrays.asList("1.0.0", "1.0.1", "1.1.0", "1.2.0")) {
            assertEquals(EngineErrorCode.MODEL_VERSION, failure(bundle(old)), old);
        }
        open(bundle("1.3.0")).verifyAll();
        open(bundle("1.3.1")).verifyAll();
    }

    @Test
    void missingModelsAndUnsafePathsFail() throws Exception {
        Map<String, byte[]> files = bundle("1.3.0");
        files.remove("sub/b.onnx");
        ModelRegistry r = open(files);
        assertEquals(EngineErrorCode.MODEL_MISSING, assertThrows(EngineException.class, () -> r.load("b")).getErrorCode());
        assertEquals(EngineErrorCode.MODEL_MISSING, assertThrows(EngineException.class, () -> r.load("zzz")).getErrorCode());
        Map<String, byte[]> none = new HashMap<>();
        assertEquals(EngineErrorCode.MODEL_MISSING, failure(none));
        assertThrows(java.io.IOException.class, () -> ModelSources.checkPath("../x"));
        assertThrows(java.io.IOException.class, () -> ModelSources.checkPath("/x"));
        assertThrows(java.io.IOException.class, () -> ModelSources.directory(new File(".")).read("..\\x"));
    }

    @Test
    void repositoryBundleVerifiesWithTheBuiltInKey() throws Exception {
        ModelRegistry r = TestSupport.registry();
        r.verifyAll();
        assertEquals("mosip-liveness-models", r.bundleName());
        assertEquals(Arrays.asList(ModelIds.YUNET, ModelIds.FACE_MESH, ModelIds.MINIFASNET_V2, ModelIds.MINIFASNET_V1SE,
                ModelIds.SFACE), idsOf(r.models()));
        assertEquals("sha256:99538367853153c2", ManifestSignature.keyId(ModelTrustAnchors.development()));
        assertEquals(2.7, r.entry(ModelIds.MINIFASNET_V2).doubleParam("cropScale", 0), 1e-12);
        assertEquals(4.0, r.entry(ModelIds.MINIFASNET_V1SE).doubleParam("cropScale", 0), 1e-12);
        long total = 0;
        for (ModelEntry e : r.manifest().models()) {
            total += e.bytes();
        }
        assertTrue(total < 26_000_000L, "bundle size " + total);
    }

    @Test
    void bundleLoadsFromTheClassPathAsOnTheDesktop() throws Exception {
        // The build copies models/ to the class-path folder that the models jar holds.
        ModelRegistry r = ModelRegistry.open(ModelSources.classpath(getClass().getClassLoader()));
        r.verifyAll();
        assertEquals(TestSupport.registry().models(), r.models());
    }

    @Test
    void publicKeyFileMatchesTheEmbeddedKey() throws Exception {
        byte[] pem = java.nio.file.Files.readAllBytes(new File(TestSupport.modelsDir(), "model-signing-dev.pub.pem").toPath());
        assertEquals(ModelTrustAnchors.development(), ModelTrustAnchors.parsePem(new String(pem, StandardCharsets.US_ASCII)));
        assertEquals(ModelTrustAnchors.DEVELOPMENT_KEY_PEM, ModelTrustAnchors.toPem(ModelTrustAnchors.development()));
        assertThrows(IllegalArgumentException.class, () -> ModelTrustAnchors.parsePem("-----BEGIN PUBLIC KEY-----\nAAAA\n"));
    }

    @Test
    void classpathSourceReadsResources() throws Exception {
        ModelSource cp = ModelSources.classpath(getClass().getClassLoader(), "faces/");
        assertTrue(cp.read("README.md").length > 100);
        assertThrows(FileNotFoundException.class, () -> cp.read("absent.bin"));
        assertThrows(java.io.IOException.class, () -> cp.read("../x"));
    }

    private static List<String> idsOf(List<ModelInfo> infos) {
        String[] ids = new String[infos.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = infos.get(i).id();
        }
        return Arrays.asList(ids);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
