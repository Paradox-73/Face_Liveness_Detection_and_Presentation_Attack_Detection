import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Prints the machine keys of a desktop Registration Client as the JSON body of MockSync's {@code POST /admin/machines}.
 * <p>
 * Run it with the client's own libraries on the class path and the client's working directory as the current
 * directory, so it reads the same keys the client uses:
 * <pre>
 * cd &lt;client dir&gt;
 * java -cp "lib/*" &lt;path&gt;/MachineKeyTool.java
 * </pre>
 * It asks keymanager's {@code ClientCryptoFacade} for the client security instance, which uses the TPM when one is
 * available and otherwise the local keys in {@code .mosipkeys}, exactly as the client does.
 */
public class MachineKeyTool {

	public static void main(String[] args) throws Exception {
		Class<?> facadeClass = Class.forName("io.mosip.kernel.clientcrypto.service.impl.ClientCryptoFacade");
		Object facade = facadeClass.getDeclaredConstructor().newInstance();
		setField(facade, "useResidentServiceModuleKey", Boolean.FALSE);
		setField(facade, "residentServiceAppId", "RESIDENT");
		setField(facade, "cryptoCore", newCryptoCore());
		Object security = facadeClass.getMethod("getClientSecurity").invoke(facade);
		Class<?> spi = Class.forName("io.mosip.kernel.clientcrypto.service.spi.ClientCryptoService");
		boolean tpm = (Boolean) spi.getMethod("isTPMInstance").invoke(security);
		byte[] encryptionKey = (byte[]) spi.getMethod("getEncryptionPublicPart").invoke(security);
		byte[] signingKey = (byte[]) spi.getMethod("getSigningPublicPart").invoke(security);

		String name = InetAddress.getLocalHost().getHostName().toLowerCase();
		Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
		String json = String.format("{\"name\":\"%s\",\"clientType\":\"%s\",\"publicKey\":\"%s\",\"signPublicKey\":\"%s\","
				+ "\"keyIndex\":\"%s\"}", name, tpm ? "TPM" : "LOCAL", url.encodeToString(encryptionKey),
				url.encodeToString(signingKey), fingerprint(encryptionKey));
		if (args.length > 0) {
			Files.writeString(Path.of(args[0]), json);
		}
		System.out.println(json);
		spi.getMethod("closeSecurityInstance").invoke(security);
		System.exit(0);
	}

	private static Object newCryptoCore() throws Exception {
		Object core = Class.forName("io.mosip.kernel.crypto.jce.core.CryptoCore").getDeclaredConstructor().newInstance();
		setField(core, "asymmetricAlgorithm", "RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
		setField(core, "symmetricAlgorithm", "AES/GCM/PKCS5Padding");
		setField(core, "keystoreType", "OFFLINE");
		setField(core, "tagLength", 128);
		return core;
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static String fingerprint(byte[] key) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < digest.length; i++) {
			builder.append(i == 0 ? "" : ":").append(String.format("%02x", digest[i]));
		}
		return builder.toString();
	}
}
