package io.mosip.liveness.onnx.tools;

import io.mosip.liveness.onnx.model.Hex;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelManifest;
import io.mosip.liveness.onnx.model.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSignerTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        out.reset();
        err.reset();
        return ModelSigner.run(args, new PrintStream(out, true), new PrintStream(err, true));
    }

    @Test
    void keygenUpdateSignVerifyRoundTrip() throws Exception {
        String priv = dir.resolve("keys/dev.private.pem").toString();
        String pub = dir.resolve("keys/dev.pub.pem").toString();
        assertEquals(0, run("keygen", "--private", priv, "--public", pub, "--bits", "2048", "--allow-in-repo"));
        assertTrue(new String(Files.readAllBytes(dir.resolve("keys/dev.private.pem")), StandardCharsets.US_ASCII)
                .startsWith("-----BEGIN PRIVATE KEY-----"));
        assertEquals(1, run("keygen", "--private", priv, "--public", pub, "--allow-in-repo"), "no overwrite");

        Path bundle = dir.resolve("bundle");
        Files.createDirectories(bundle);
        Files.write(bundle.resolve("m.onnx"), new byte[] {1, 2, 3, 4});
        ModelManifest draft = ModelManifest.of("b", "1.0.0", Collections.singletonList(ModelEntry.of("m", "m.onnx", "1",
                Hex.sha256(new byte[1]), 1, "MIT", "test", Collections.<String, String>emptyMap())));
        Files.write(bundle.resolve(ModelRegistry.MANIFEST_FILE), draft.toJson().getBytes(StandardCharsets.UTF_8));

        assertEquals(0, run("update", "--bundle", bundle.toString()));
        assertEquals(0, run("sign", "--bundle", bundle.toString(), "--private", priv));
        assertEquals(0, run("verify", "--bundle", bundle.toString(), "--public", pub), err.toString());
        assertTrue(out.toString().contains("OK b 1.0.0"));
        assertEquals(1, run("verify", "--bundle", bundle.toString()), "the built-in key must not accept this bundle");
        assertEquals(1, run("verify", "--bundle", bundle.toString(), "--public", pub, "--min-version", "2.0.0"));

        Files.write(bundle.resolve("m.onnx"), new byte[] {1, 2, 3, 5});
        assertEquals(1, run("verify", "--bundle", bundle.toString(), "--public", pub));
        assertTrue(err.toString().contains("MODEL_INTEGRITY"));

        assertEquals(0, run("digest", bundle.resolve("m.onnx").toString()));
        assertTrue(out.toString().startsWith(Hex.sha256(new byte[] {1, 2, 3, 5})));
    }

    @Test
    void refusesPrivateKeyInsideAGitWorkTree() throws Exception {
        Files.createDirectories(dir.resolve("repo/.git"));
        String priv = dir.resolve("repo/key.pem").toString();
        String pub = dir.resolve("pub.pem").toString();
        assertEquals(1, run("keygen", "--private", priv, "--public", pub));
        assertTrue(err.toString().contains("refusing"));
        assertTrue(!new File(priv).exists());
        assertTrue(ModelSigner.insideGitWorkTree(new File(priv)));
    }

    @Test
    void usageErrorsReturnTwo() {
        assertEquals(2, run());
        assertEquals(2, run("frobnicate"));
        assertEquals(2, run("sign", "--bundle"));
        assertEquals(2, run("verify"));
        assertEquals(2, run("digest"));
        assertEquals(2, run("keygen", "--private", "a", "--public", "b", "--bits", "1024"));
    }

    @Test
    void repositoryBundleVerifiesFromTheCommandLine() {
        String models = System.getProperty("liveness.models.dir", "../../models");
        assertEquals(0, run("verify", "--bundle", models), err.toString());
        assertTrue(out.toString().contains("OK mosip-liveness-models 1.0.1"));
        assertTrue(out.toString().contains("minifasnet-v2@2.7_80x80"));
    }
}
