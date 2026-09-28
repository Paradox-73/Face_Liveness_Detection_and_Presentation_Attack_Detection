package io.mosip.mocksync.crypto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.mosip.mocksync.config.MockSyncProperties;

/**
 * Keys MockSync plays the server side with. Each key is generated on first use into {@code <work>/keys/} and
 * reused afterwards, so a client that stored a certificate keeps trusting it across MockSync restarts.
 * <ul>
 * <li>{@code KERNEL/SIGN}: signs every signed response, the script files and the auth tokens.</li>
 * <li>{@code IDA/INTERNAL}: certificate the client encrypts operator onboarding requests to.</li>
 * <li>{@code REGISTRATION/<center>_<machine>}: packet encryption key of one machine.</li>
 * <li>{@code DEVICE CA}: trust anchor for devices whose certificates MockSync issues (Android built-in camera).</li>
 * </ul>
 */
@Component
public class ServerKeys {

	/** File name of the TLS key store inside {@code <work>/keys}. */
	public static final String TLS_STORE = "tls.p12";

	private static final Logger LOGGER = LoggerFactory.getLogger(ServerKeys.class);
	private static final Duration LONG_VALIDITY = Duration.ofDays(5 * 365);

	private final Path keysDir;
	private final Map<String, CertifiedKey> cache = new ConcurrentHashMap<>();
	private volatile JwsSigner signer;

	/**
	 * Creates the key holder.
	 *
	 * @param properties MockSync settings (work directory)
	 */
	public ServerKeys(MockSyncProperties properties) {
		this.keysDir = Path.of(properties.getWorkDir()).resolve("keys");
	}

	/**
	 * Makes sure the HTTPS key store exists and returns its path. Called before the web server starts.
	 *
	 * @param workDir MockSync work directory
	 * @return path of {@value #TLS_STORE}
	 * @throws IOException if the key store cannot be written
	 */
	public static Path ensureTlsKeyStore(Path workDir) throws IOException {
		Path keys = workDir.resolve("keys");
		Path store = keys.resolve(TLS_STORE);
		if (Files.exists(store)) {
			return store;
		}
		KeyPair pair = KeyMaterial.newRsaKeyPair();
		java.security.cert.X509Certificate cert = KeyMaterial.certificate(pair, "CN=localhost,OU=MockSync,O=MOSIP Liveness",
				LONG_VALIDITY, false, KeyMaterial.localDnsNames(), List.of("127.0.0.1", "10.0.2.2"), null);
		KeyMaterial.savePkcs12(store, "tls", pair.getPrivate(), cert);
		Files.write(keys.resolve("tls-cert.pem"), KeyMaterial.toPem(cert).getBytes(StandardCharsets.US_ASCII));
		LOGGER.info("Generated HTTPS certificate {} (import it into the client JRE trust store)",
				keys.resolve("tls-cert.pem").toAbsolutePath());
		return store;
	}

	/**
	 * Returns the KERNEL SIGN key.
	 *
	 * @return key and self-signed certificate
	 */
	public CertifiedKey kernelSign() {
		return getOrCreate("kernel-sign", "CN=MockSync KERNEL SIGN,OU=MockSync,O=MOSIP Liveness", false, null);
	}

	/**
	 * Returns the signer that uses the KERNEL SIGN key.
	 *
	 * @return signer
	 */
	public JwsSigner signer() {
		if (signer == null) {
			synchronized (this) {
				if (signer == null) {
					signer = new JwsSigner(kernelSign());
				}
			}
		}
		return signer;
	}

	/**
	 * Returns the IDA INTERNAL key used for operator onboarding.
	 *
	 * @return key and certificate
	 */
	public CertifiedKey ida() {
		return getOrCreate("ida-internal", "CN=MockSync IDA INTERNAL,OU=MockSync,O=MOSIP Liveness", false, null);
	}

	/**
	 * Returns the DEVICE CA that can issue device certificates.
	 *
	 * @return CA key and certificate
	 */
	public CertifiedKey deviceCa() {
		return getOrCreate("device-ca", "CN=MockSync DEVICE CA,OU=MockSync,O=MOSIP Liveness", true, null);
	}

	/**
	 * Returns the packet encryption key of one centre and machine.
	 *
	 * @param referenceId {@code <centerId>_<machineId>}
	 * @return key and certificate
	 */
	public CertifiedKey registration(String referenceId) {
		String safe = referenceId.replaceAll("[^A-Za-z0-9_-]", "_");
		return getOrCreate("registration-" + safe, "CN=MockSync REGISTRATION " + safe + ",OU=MockSync,O=MOSIP Liveness",
				false, null);
	}

	/**
	 * Returns the directory holding the generated keys.
	 *
	 * @return keys directory
	 */
	public Path getKeysDir() {
		return keysDir;
	}

	private CertifiedKey getOrCreate(String name, String subject, boolean ca, CertifiedKey issuer) {
		return cache.computeIfAbsent(name, key -> {
			Path file = keysDir.resolve(key + ".p12");
			try {
				if (Files.exists(file)) {
					return KeyMaterial.loadPkcs12(file);
				}
				KeyPair pair = KeyMaterial.newRsaKeyPair();
				java.security.cert.X509Certificate cert = KeyMaterial.certificate(pair, subject, LONG_VALIDITY, ca,
						List.of(), List.of(), issuer);
				KeyMaterial.savePkcs12(file, key, pair.getPrivate(), cert);
				LOGGER.info("Generated key {} ({})", key, subject);
				return new CertifiedKey(pair.getPrivate(), cert);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}
}
