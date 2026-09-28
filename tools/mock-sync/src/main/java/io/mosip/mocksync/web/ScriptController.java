package io.mosip.mocksync.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.mosip.mocksync.crypto.ServerKeys;
import io.mosip.mocksync.data.ClientSettingsBuilder;
import io.mosip.mocksync.data.ReferenceData;

/**
 * Serves rule scripts (for example {@code applicanttype.mvel}) that the client downloads during client settings sync.
 * The {@code file-signature} header is a detached JWS over {@code {"hash":"<SHA-256 upper-case hex>"}}; the client
 * stores it and checks it every time it loads the script ({@code RequiredFieldValidator.getScript}).
 */
@RestController
public class ScriptController {

	/** Header that carries the script signature. */
	public static final String FILE_SIGNATURE_HEADER = "file-signature";

	private final ReferenceData data;
	private final ServerKeys keys;

	/**
	 * Creates the controller.
	 *
	 * @param data reference data
	 * @param keys server keys
	 */
	public ScriptController(ReferenceData data, ServerKeys keys) {
		this.data = data;
		this.keys = keys;
	}

	/**
	 * Downloads one script.
	 *
	 * @param name script file name
	 * @return file bytes with Content-Length and {@value #FILE_SIGNATURE_HEADER}
	 */
	@GetMapping("/v1/syncdata/scripts/{name:.+}")
	public ResponseEntity<byte[]> script(@PathVariable String name) {
		if (!ClientSettingsBuilder.SCRIPTS.contains(name)) {
			return ResponseEntity.notFound().build();
		}
		byte[] content = data.bytes("upstream/mosip-config/" + name);
		String signedData = String.format("{\"hash\":\"%s\"}", sha256UpperHex(content));
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(content.length)
				.header(FILE_SIGNATURE_HEADER, keys.signer().signDetached(signedData.getBytes(StandardCharsets.UTF_8)))
				.body(content);
	}

	/**
	 * SHA-256 as upper-case hex, as {@code HMACUtils2.digestAsPlainText}.
	 *
	 * @param content bytes
	 * @return hex digest
	 */
	public static String sha256UpperHex(byte[] content) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(String.format(Locale.ROOT, "%02X", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
