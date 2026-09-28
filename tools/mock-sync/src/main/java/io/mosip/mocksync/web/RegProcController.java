package io.mosip.mocksync.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.ClientCrypto;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.crypto.ServerCrypto;
import io.mosip.mocksync.crypto.ServerKeys;

/**
 * Registration processor and IDA endpoints ({@code /registrationprocessor/v1/...}): the IDA certificate and auth
 * call used for operator onboarding, and stand-ins for packet sync, packet upload and status queries.
 */
@RestController
@RequestMapping("/registrationprocessor/v1")
public class RegProcController {

	private static final Logger LOGGER = LoggerFactory.getLogger(RegProcController.class);

	private final ServerKeys keys;
	private final ResponseFactory responses;
	private final MockSyncProperties properties;
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Creates the controller.
	 *
	 * @param keys       server keys
	 * @param responses  response factory
	 * @param properties MockSync settings
	 */
	public RegProcController(ServerKeys keys, ResponseFactory responses, MockSyncProperties properties) {
		this.keys = keys;
		this.responses = responses;
		this.properties = properties;
	}

	/**
	 * IDA certificate the client encrypts onboarding requests to.
	 *
	 * @param applicationId expected {@code IDA}
	 * @param referenceId   expected {@code INTERNAL}
	 * @return envelope with {@code certificate}
	 */
	@GetMapping("/registrationstatus/getCertificate")
	public ResponseEntity<byte[]> idaCertificate(@RequestParam(required = false) String applicationId,
			@RequestParam(required = false) String referenceId) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("certificate", KeyMaterial.toPem(keys.ida().getCertificate()));
		response.put("certSignRequest", null);
		response.put("issuedAt", keys.ida().getCertificate().getNotBefore().toInstant().toString());
		response.put("expiryAt", keys.ida().getCertificate().getNotAfter().toInstant().toString());
		response.put("timestamp", ResponseFactory.now());
		return responses.unsigned("mosip.ida.certificate", response);
	}

	/**
	 * IDA biometric auth used by operator onboarding. MockSync decrypts the request to log which modalities were
	 * sent (counts only, no biometric data) and answers {@code authStatus = mocksync.ida-auth-status}.
	 *
	 * @param body onboarding auth request
	 * @return envelope with {@code authStatus}
	 */
	@PostMapping("/registrationstatus/auth")
	public ResponseEntity<byte[]> idaAuth(@RequestBody Map<String, Object> body) {
		Map<String, Integer> modalities = inspectAuthRequest(body);
		boolean status = properties.isIdaAuthStatus();
		LOGGER.info("IDA auth for {}: biometrics {} -> authStatus {}", body.get("individualId"), modalities, status);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("authStatus", status);
		response.put("authToken", UUID.randomUUID().toString());
		// Not part of the IDA API; clients ignore it. Shows which modalities the onboarding request carried.
		response.put("mockSyncBiometrics", modalities);
		return responses.unsigned("mosip.identity.auth", response);
	}

	/**
	 * Packet sync (v1 and v2). The body is a JSON string with the sync list encrypted to the machine's REGISTRATION
	 * key; MockSync decrypts it and answers SUCCESS for every registration ID.
	 *
	 * @param body      JSON string body
	 * @param centerMachineRefId {@code <center>_<machine>} header set by the client
	 * @return signed envelope with one status per registration ID
	 */
	@PostMapping({ "/registrationstatus/sync", "/registrationstatus/syncV2" })
	public ResponseEntity<byte[]> packetSync(@RequestBody String body,
			@RequestHeader(name = "Center-Machine-RefId", required = false) String centerMachineRefId) {
		List<Map<String, Object>> statuses = new ArrayList<>();
		try {
			String encoded = mapper.readValue(body, String.class);
			byte[] plain = ServerCrypto.decryptPacketData(keys.registration(centerMachineRefId).getPrivateKey(),
					ClientCrypto.decodeBase64Any(encoded));
			for (JsonNode node : mapper.readTree(plain).findParents("registrationId")) {
				Map<String, Object> status = new LinkedHashMap<>();
				status.put("registrationId", node.get("registrationId").asText());
				status.put("status", "SUCCESS");
				statuses.add(status);
			}
			LOGGER.info("Packet sync from {}: {} registration(s)", centerMachineRefId, statuses.size());
		} catch (IOException | GeneralSecurityException | RuntimeException e) {
			LOGGER.warn("Packet sync from {} could not be decrypted: {}", centerMachineRefId, e.getMessage());
			return responses.error("mosip.registration.sync", "RPR-RGS-001", "Packet sync request could not be decrypted");
		}
		return responses.signed("mosip.registration.sync", statuses);
	}

	/**
	 * Packet upload. The packet is saved under {@code <work>/packets/} and acknowledged.
	 *
	 * @param file uploaded packet zip
	 * @return signed envelope with {@code status}
	 * @throws IOException if the packet cannot be saved
	 */
	@PostMapping("/packetreceiver/registrationpackets")
	public ResponseEntity<byte[]> uploadPacket(@RequestParam("file") MultipartFile file) throws IOException {
		Path dir = Path.of(properties.getWorkDir()).resolve("packets");
		Files.createDirectories(dir);
		String name = Path.of(String.valueOf(file.getOriginalFilename())).getFileName().toString();
		file.transferTo(dir.resolve(name).toAbsolutePath());
		LOGGER.info("Packet {} received ({} bytes)", name, file.getSize());
		return responses.signed("mosip.registration.packet", Map.of("status", "Packet has reached Packet Receiver"));
	}

	/**
	 * Packet status queries; MockSync keeps no processing state and returns an empty list.
	 *
	 * @return signed envelope with an empty list
	 */
	@PostMapping({ "/registrationstatus/search", "/registrationstatus/packetexternalstatus" })
	public ResponseEntity<byte[]> packetStatus() {
		return responses.signed("mosip.registration.status", List.of());
	}

	private Map<String, Integer> inspectAuthRequest(Map<String, Object> body) {
		Map<String, Integer> counts = new TreeMap<>();
		try {
			byte[] aesKey = ServerCrypto.rsaOaepSha256(keys.ida().getPrivateKey(),
					ClientCrypto.decodeBase64Any(String.valueOf(body.get("requestSessionKey"))));
			byte[] request = ServerCrypto.decryptWithTrailingIv(aesKey,
					ClientCrypto.decodeBase64Any(String.valueOf(body.get("request"))));
			for (JsonNode biometric : mapper.readTree(request).path("biometrics")) {
				JsonNode data = mapper.readTree(new String(ClientCrypto.decodeBase64Any(biometric.path("data").asText()),
						StandardCharsets.UTF_8));
				counts.merge(data.path("bioType").asText("UNKNOWN"), 1, Integer::sum);
			}
		} catch (IOException | GeneralSecurityException | RuntimeException e) {
			LOGGER.warn("IDA auth request could not be decrypted: {}", e.getMessage());
		}
		return counts;
	}
}
