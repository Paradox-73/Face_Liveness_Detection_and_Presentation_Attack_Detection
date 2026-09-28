package io.mosip.liveness.onnx.tools;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.onnx.model.Hex;
import io.mosip.liveness.onnx.model.ManifestSignature;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelManifest;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.model.ModelTrustAnchors;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line tool for the model bundle: create a signing key pair, refresh the hashes in
 * {@code manifest.json}, sign it into {@code manifest.jws}, and verify a bundle.
 *
 * <pre>
 * java -cp liveness-core.jar:liveness-onnx.jar io.mosip.liveness.onnx.tools.ModelSigner &lt;command&gt; [options]
 *   keygen --private &lt;key.pem&gt; --public &lt;pub.pem&gt; [--bits 3072] [--allow-in-repo]
 *   update --bundle &lt;dir&gt;                      recompute sha256 and bytes of every model in manifest.json
 *   sign   --bundle &lt;dir&gt; --private &lt;key.pem&gt;   write manifest.jws (RS256, detached)
 *   verify --bundle &lt;dir&gt; [--public &lt;pub.pem&gt;] [--min-version 1.0.0]
 *   digest &lt;file&gt;...                          print SHA-256 and size
 * </pre>
 *
 * <p>Key handling: {@code keygen} refuses to write a private key inside a git working tree unless
 * {@code --allow-in-repo} is given, so the key does not get committed by accident. The private key is an
 * unencrypted PKCS#8 PEM file; keep it on removable or access-controlled storage.
 */
public final class ModelSigner {

    private final PrintStream out;
    private final PrintStream err;

    private ModelSigner(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Runs the tool and exits with its status.
     *
     * @param args command and options
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the tool.
     *
     * @param args command and options
     * @param out  standard output
     * @param err  error output
     * @return 0 on success, 1 on a verification or signing failure, 2 on a usage error
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        ModelSigner signer = new ModelSigner(out, err);
        if (args.length == 0) {
            return signer.usage("missing command");
        }
        try {
            List<String> positional = new ArrayList<>();
            Map<String, String> options = parseOptions(args, positional);
            switch (args[0]) {
                case "keygen":
                    return signer.keygen(options);
                case "update":
                    return signer.update(options);
                case "sign":
                    return signer.sign(options);
                case "verify":
                    return signer.verify(options);
                case "digest":
                    return signer.digest(positional);
                default:
                    return signer.usage("unknown command " + args[0]);
            }
        } catch (IllegalArgumentException e) {
            return signer.usage(e.getMessage());
        } catch (IOException | GeneralSecurityException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static Map<String, String> parseOptions(String[] args, List<String> positional) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--allow-in-repo".equals(a)) {
                options.put(a, "true");
            } else if (a.startsWith("--")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + a);
                }
                options.put(a, args[++i]);
            } else {
                positional.add(a);
            }
        }
        return options;
    }

    private int keygen(Map<String, String> options) throws IOException, GeneralSecurityException {
        File privateFile = new File(required(options, "--private"));
        File publicFile = new File(required(options, "--public"));
        int bits = Integer.parseInt(options.getOrDefault("--bits", "3072"));
        if (bits < 2048) {
            throw new IllegalArgumentException("--bits must be at least 2048");
        }
        if (insideGitWorkTree(privateFile) && !options.containsKey("--allow-in-repo")) {
            err.println("refusing to write a private key inside a git working tree: " + privateFile.getPath());
            return 1;
        }
        if (privateFile.exists() || publicFile.exists()) {
            err.println("refusing to overwrite an existing key file");
            return 1;
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        String privatePem = ModelTrustAnchors.pemBlock("PRIVATE KEY", pair.getPrivate().getEncoded());
        write(privateFile, privatePem.getBytes(StandardCharsets.US_ASCII));
        write(publicFile, ModelTrustAnchors.toPem(pair.getPublic()).getBytes(StandardCharsets.US_ASCII));
        out.println("key id " + ManifestSignature.keyId(pair.getPublic()));
        out.println("private key: " + privateFile.getPath() + " (keep it out of version control)");
        out.println("public key:  " + publicFile.getPath());
        return 0;
    }

    private int update(Map<String, String> options) throws IOException {
        File bundle = new File(required(options, "--bundle"));
        File manifestFile = new File(bundle, ModelRegistry.MANIFEST_FILE);
        ModelManifest manifest = ModelManifest.parse(read(manifestFile));
        List<ModelEntry> updated = new ArrayList<>();
        for (ModelEntry e : manifest.models()) {
            byte[] data = read(new File(bundle, e.file()));
            updated.add(e.withDigest(Hex.sha256(data), data.length));
            out.println(e.id() + "  " + Hex.sha256(data) + "  " + data.length);
        }
        write(manifestFile, manifest.withModels(updated).toJson().getBytes(StandardCharsets.UTF_8));
        out.println("updated " + manifestFile.getPath() + "; sign it again");
        return 0;
    }

    private int sign(Map<String, String> options) throws IOException, GeneralSecurityException {
        File bundle = new File(required(options, "--bundle"));
        PrivateKey privateKey = readPrivateKey(new File(required(options, "--private")));
        PublicKey publicKey = publicKeyOf(privateKey);
        byte[] manifest = read(new File(bundle, ModelRegistry.MANIFEST_FILE));
        ModelManifest.parse(manifest);
        String jws = ManifestSignature.sign(manifest, privateKey, publicKey);
        write(new File(bundle, ModelRegistry.SIGNATURE_FILE), (jws + "\n").getBytes(StandardCharsets.US_ASCII));
        out.println("signed " + ModelRegistry.MANIFEST_FILE + " with key " + ManifestSignature.keyId(publicKey));
        return 0;
    }

    private int verify(Map<String, String> options) throws IOException {
        File bundle = new File(required(options, "--bundle"));
        ModelRegistry.Builder builder = ModelRegistry.builder(ModelSources.directory(bundle));
        if (options.containsKey("--public")) {
            builder.trustAnchor(ModelTrustAnchors.parsePem(new String(read(new File(options.get("--public"))),
                    StandardCharsets.US_ASCII)));
        }
        if (options.containsKey("--min-version")) {
            builder.minimumVersion(options.get("--min-version"));
        }
        try {
            ModelRegistry registry = builder.open();
            registry.verifyAll();
            out.println("OK " + registry.bundleName() + " " + registry.bundleVersion());
            for (ModelInfo m : registry.models()) {
                out.println("  " + m.id() + "@" + m.version() + "  " + m.sha256());
            }
            return 0;
        } catch (EngineException e) {
            err.println("FAILED (" + e.getErrorCode() + "): " + e.getMessage());
            return 1;
        }
    }

    private int digest(List<String> files) throws IOException {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("digest needs at least one file");
        }
        for (String f : files) {
            byte[] data = read(new File(f));
            out.println(Hex.sha256(data) + "  " + data.length + "  " + f);
        }
        return 0;
    }

    private int usage(String problem) {
        err.println("error: " + problem);
        err.println("usage: ModelSigner keygen --private <key.pem> --public <pub.pem> [--bits 3072] [--allow-in-repo]");
        err.println("       ModelSigner update --bundle <dir>");
        err.println("       ModelSigner sign --bundle <dir> --private <key.pem>");
        err.println("       ModelSigner verify --bundle <dir> [--public <pub.pem>] [--min-version <x.y.z>]");
        err.println("       ModelSigner digest <file>...");
        return 2;
    }

    private static String required(Map<String, String> options, String name) {
        String v = options.get(name);
        if (v == null) {
            throw new IllegalArgumentException("missing option " + name);
        }
        return v;
    }

    static boolean insideGitWorkTree(File file) {
        File dir = file.getAbsoluteFile().getParentFile();
        while (dir != null) {
            if (new File(dir, ".git").exists()) {
                return true;
            }
            dir = dir.getParentFile();
        }
        return false;
    }

    private static PrivateKey readPrivateKey(File file) throws IOException, GeneralSecurityException {
        String text = new String(read(file), StandardCharsets.US_ASCII);
        String body = text.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static PublicKey publicKeyOf(PrivateKey key) throws GeneralSecurityException {
        if (!(key instanceof RSAPrivateCrtKey)) {
            throw new GeneralSecurityException("RSA private key with CRT parameters expected");
        }
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) key;
        RSAPublicKeySpec spec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }


    private static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create directory " + parent.getPath());
        }
        try (OutputStream o = new FileOutputStream(file)) {
            o.write(data);
        }
    }
}
