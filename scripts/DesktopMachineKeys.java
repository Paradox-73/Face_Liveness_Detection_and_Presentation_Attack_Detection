import io.mosip.kernel.clientcrypto.service.spi.ClientCryptoService;
import io.mosip.kernel.core.util.CryptoUtil;

import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Prints the machine identity that a server (MOSIP admin, or the local MockSync)
 * needs before the desktop Registration Client can log in for the first time:
 * machine name, encryption and signing public keys, and key index.
 *
 * <p>The client itself writes this information to {@code .mosipkeys/readme.txt}
 * only when it falls back to local (software) keys. On a machine with a TPM 2.0
 * it uses TPM keys and writes no readme, so this program reads the same TPM keys
 * the client uses, through the keymanager library in the run layout's {@code lib/}.
 *
 * <p>Run from the run layout folder with a JDK 11 (source-file mode):
 * {@code java -cp "lib/*" <repo>/scripts/DesktopMachineKeys.java}.
 * {@code scripts/desktop-machine-keys.ps1} does this.
 */
public class DesktopMachineKeys {

	private static final String TPM_SERVICE =
			"io.mosip.kernel.clientcrypto.service.impl.TPMClientCryptoServiceImpl";

	private static final Path README = Paths.get(".mosipkeys", "readme.txt");

	/**
	 * Prints the TPM key details, or the local-key readme when no TPM is usable.
	 *
	 * @param args not used
	 * @throws Exception when the TPM keys cannot be read or the readme cannot be read
	 */
	public static void main(String[] args) throws Exception {
		ClientCryptoService tpm = openTpm();
		if (tpm != null) {
			try {
				printTpmKeys(tpm);
			} finally {
				tpm.closeSecurityInstance();
			}
			return;
		}
		System.out.println("KeyType: LOCAL (no usable TPM)");
		if (Files.exists(README)) {
			System.out.print(new String(Files.readAllBytes(README), StandardCharsets.UTF_8));
		} else {
			System.out.println("No " + README.toAbsolutePath()
					+ " yet. Start the client once; it creates local keys and this file.");
		}
	}

	/**
	 * Opens the platform TPM the same way the client's ClientCryptoFacade does.
	 *
	 * @return the TPM crypto service, or {@code null} when the TPM cannot be used
	 */
	private static ClientCryptoService openTpm() {
		try {
			Constructor<?> constructor = Class.forName(TPM_SERVICE).getDeclaredConstructor();
			constructor.setAccessible(true);
			return (ClientCryptoService) constructor.newInstance();
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	/**
	 * Prints the TPM public keys in the layout of the client's local-key readme.
	 *
	 * @param tpm open TPM crypto service
	 * @throws Exception when the machine name cannot be resolved
	 */
	private static void printTpmKeys(ClientCryptoService tpm) throws Exception {
		byte[] encryptionKey = tpm.getEncryptionPublicPart();
		byte[] signingKey = tpm.getSigningPublicPart();
		System.out.println("KeyType: TPM");
		System.out.println("MachineName: " + InetAddress.getLocalHost().getHostName().toLowerCase());
		System.out.println("PublicKey: " + CryptoUtil.encodeToURLSafeBase64(encryptionKey));
		System.out.println("SignPublicKey: " + CryptoUtil.encodeToURLSafeBase64(signingKey));
		System.out.println("KeyIndex: " + CryptoUtil.computeFingerPrint(encryptionKey, null));
	}
}
