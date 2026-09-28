package io.mosip.mocksync.web;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.ClientCrypto;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.crypto.ServerKeys;
import io.mosip.mocksync.data.CaCertificateService;
import io.mosip.mocksync.data.ClientSettingsBuilder;
import io.mosip.mocksync.data.ConfigService;
import io.mosip.mocksync.data.SchemaService;
import io.mosip.mocksync.data.UserStore;
import io.mosip.mocksync.data.UserStore.User;
import io.mosip.mocksync.machine.Machine;
import io.mosip.mocksync.machine.MachineRegistry;

/**
 * The syncdata-service endpoints registration clients call for first login, initial sync and later syncs
 * ({@code /v1/syncdata/...}). Paths, parameters and response shapes follow what the desktop client (master
 * {@code 22fe01f}) sends and parses; see the README endpoint table.
 */
@RestController
@RequestMapping("/v1/syncdata")
public class SyncDataController {

	private static final Logger LOGGER = LoggerFactory.getLogger(SyncDataController.class);

	private final MachineRegistry machines;
	private final UserStore users;
	private final TokenService tokens;
	private final ConfigService config;
	private final ClientSettingsBuilder clientSettings;
	private final SchemaService schema;
	private final CaCertificateService caCertificates;
	private final ServerKeys keys;
	private final ResponseFactory responses;
	private final MockSyncProperties properties;
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Creates the controller.
	 *
	 * @param machines       machine registry
	 * @param users          operator accounts
	 * @param tokens         token issuer
	 * @param config         config service
	 * @param clientSettings client settings builder
	 * @param schema         schema service
	 * @param caCertificates CA certificate service
	 * @param keys           server keys
	 * @param responses      response factory
	 * @param properties     MockSync settings
	 */
	public SyncDataController(MachineRegistry machines, UserStore users, TokenService tokens, ConfigService config,
			ClientSettingsBuilder clientSettings, SchemaService schema, CaCertificateService caCertificates,
			ServerKeys keys, ResponseFactory responses, MockSyncProperties properties) {
		this.machines = machines;
		this.users = users;
		this.tokens = tokens;
		this.config = config;
		this.clientSettings = clientSettings;
		this.schema = schema;
		this.caCertificates = caCertificates;
		this.keys = keys;
		this.responses = responses;
		this.properties = properties;
	}

	/**
	 * Health check both clients use to decide whether they are online.
	 *
	 * @return {@code {"status":"UP"}}
	 */
	@GetMapping("/actuator/health")
	public Map<String, Object> health() {
		return Map.of("status", "UP");
	}

	/**
	 * Build information; the Android client reads {@code build.version} to choose v1 or v2 sync APIs.
	 *
	 * @return actuator-style info
	 */
	@GetMapping("/actuator/info")
	public Map<String, Object> info() {
		Map<String, Object> build = new LinkedHashMap<>();
		build.put("version", properties.getServerVersion());
		build.put("name", "mock-sync");
		build.put("artifact", "mock-sync");
		build.put("group", "io.mosip.liveness");
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("git", Map.of("commit", Map.of("id", "mock-sync")));
		info.put("build", build);
		return info;
	}

	/**
	 * Password login. The request is a JWS signed with the machine signing key whose {@code kid} is the key
	 * fingerprint; the response is the token JSON encrypted to the machine's public key.
	 *
	 * @param body request wrapper with {@code request} = compact JWS
	 * @return envelope whose {@code response} is base64url cipher text
	 */
	@PostMapping("/authenticate/useridpwd")
	public ResponseEntity<byte[]> authenticate(@RequestBody Map<String, Object> body) {
		String id = "mosip.syncdata.auth";
		String[] parts = String.valueOf(body.get("request")).split("\\.");
		if (parts.length != 3) {
			return responses.error(id, "KER-SNC-AUTH-400", "request must be a signed JWT");
		}
		Map<String, Object> header = decodeJson(parts[0]);
		Map<String, Object> payload = decodeJson(parts[1]);
		String kid = header == null ? null : String.valueOf(header.get("kid"));
		Optional<Machine> machine = machines.findBySignKeyIndex(kid);
		if (machine.isEmpty()) {
			LOGGER.warn("Login from unregistered machine (signing key {}); register it first", kid);
			return responses.error(id, "KER-SNC-MACHINE-404", "Machine not registered in MockSync (key " + kid
					+ "). Register it with scripts/register-machine and retry.");
		}
		logSignatureCheck(machine.get(), parts);
		String userId = payload == null ? null : String.valueOf(payload.get("userId"));
		Optional<User> user = users.find(userId);
		if (user.isEmpty() || !user.get().isActive()
				|| !user.get().getPassword().equals(String.valueOf(payload.get("password")))) {
			LOGGER.info("Login rejected for user {} on {}", userId, machine.get().getName());
			return responses.error(id, "KER-ATH-401", "Invalid user ID or password");
		}
		String tokenJson = tokens.tokenResponse(user.get(), machine.get().getName());
		LOGGER.info("Login accepted for user {} on {}", user.get().getUserId(), machine.get().getName());
		return responses.unsigned(id, encryptFor(machine.get(), tokenJson));
	}

	/**
	 * OTP login is not offered by MockSync.
	 *
	 * @return error envelope
	 */
	@PostMapping("/authenticate/sendotp")
	public ResponseEntity<byte[]> sendOtp() {
		return responses.error("mosip.syncdata.otp", "KER-SNC-OTP-501", "OTP login is not available in MockSync");
	}

	/**
	 * Certificates: {@code KERNEL/SIGN} (response signing, stored by the client on first use) and
	 * {@code REGISTRATION/<center>_<machine>} (packet encryption).
	 *
	 * @param applicationId application ID
	 * @param referenceId   reference ID
	 * @return signed envelope with {@code certificate} (PEM)
	 */
	@GetMapping("/getCertificate")
	public ResponseEntity<byte[]> getCertificate(@RequestParam String applicationId,
			@RequestParam(required = false) String referenceId) {
		String id = "mosip.syncdata.certificate";
		java.security.cert.X509Certificate cert;
		if ("KERNEL".equalsIgnoreCase(applicationId) && (referenceId == null || "SIGN".equalsIgnoreCase(referenceId))) {
			cert = keys.kernelSign().getCertificate();
		} else if ("REGISTRATION".equalsIgnoreCase(applicationId) && referenceId != null) {
			cert = keys.registration(referenceId).getCertificate();
		} else if ("IDA".equalsIgnoreCase(applicationId)) {
			cert = keys.ida().getCertificate();
		} else {
			return responses.error(id, "KER-KMS-012", "No certificate for " + applicationId + "/" + referenceId);
		}
		return responses.signed(id, certificateResponse(cert));
	}

	/**
	 * Machine key verification. Known machines get their key index back; unknown machines are registered from the
	 * request when {@code mocksync.auto-register-machines=true}.
	 *
	 * @param body request wrapper with {@code machineName}, {@code publicKey} and {@code signPublicKey}
	 * @return signed envelope with {@code keyIndex}
	 */
	@PostMapping("/tpm/publickey/verify")
	public ResponseEntity<byte[]> verifyMachineKey(@RequestBody Map<String, Object> body) {
		String id = "mosip.syncdata.tpm.publickey.verify";
		@SuppressWarnings("unchecked")
		Map<String, Object> request = (Map<String, Object>) body.get("request");
		if (request == null || request.get("publicKey") == null) {
			return responses.error(id, "KER-SNC-VERIFY-400", "machineName and publicKey are required");
		}
		String name = String.valueOf(request.get("machineName"));
		String publicKey = String.valueOf(request.get("publicKey"));
		Object signKey = request.get("signPublicKey");
		String keyIndex = ClientCrypto.fingerprint(ClientCrypto.decodeBase64Any(publicKey));
		Optional<Machine> known = machines.findByKeyIndex(keyIndex);
		if (known.isEmpty()) {
			if (!properties.isAutoRegisterMachines()) {
				return responses.error(id, "KER-SNC-MACHINE-404", "Machine " + name + " is not registered");
			}
			machines.register(name, publicKey, signKey == null ? null : signKey.toString(), null, "auto");
		}
		return responses.signed(id, Map.of("keyIndex", keyIndex));
	}

	/**
	 * Global and registration configuration, each encrypted to the machine.
	 *
	 * @param keyIndex machine key index
	 * @return signed envelope with {@code configDetail}
	 */
	@GetMapping("/configs/{keyIndex}")
	public ResponseEntity<byte[]> configs(@PathVariable String keyIndex) {
		String id = "mosip.syncdata.configs";
		Optional<Machine> machine = machines.findByKeyIndex(keyIndex);
		if (machine.isEmpty()) {
			return unknownMachine(id, keyIndex);
		}
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("globalConfiguration", encryptFor(machine.get(), toJson(config.globalConfiguration())));
		detail.put("registrationConfiguration", encryptFor(machine.get(), toJson(config.registrationConfiguration())));
		return responses.signed(id, Map.of("configDetail", detail));
	}

	/**
	 * Master data (client settings), each entity list encrypted to the machine.
	 *
	 * @param keyIndex machine key index
	 * @return signed envelope with {@code lastSyncTime} and {@code dataToSync}
	 */
	@GetMapping({ "/v2/clientsettings", "/clientsettings" })
	public ResponseEntity<byte[]> clientSettings(@RequestParam(name = "keyindex", required = false) String keyIndex) {
		String id = "mosip.syncdata.clientsettings";
		Optional<Machine> machine = machines.findByKeyIndex(keyIndex);
		if (machine.isEmpty()) {
			return unknownMachine(id, keyIndex);
		}
		String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
		List<Map<String, Object>> dataToSync = new ArrayList<>();
		for (ClientSettingsBuilder.Entry entry : clientSettings.build(machine.get(), base)) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("entityName", entry.getEntityName());
			item.put("entityType", entry.getEntityType());
			item.put("data", encryptFor(machine.get(), entry.getJson()));
			dataToSync.add(item);
		}
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("lastSyncTime", ResponseFactory.now());
		response.put("dataToSync", dataToSync);
		return responses.signed(id, response);
	}

	/**
	 * Identity schema and UI specs.
	 *
	 * @return signed envelope with the schema
	 */
	@GetMapping("/latestidschema")
	public ResponseEntity<byte[]> latestIdSchema() {
		return responses.signed("mosip.syncdata.idschema", schema.latestSchema());
	}

	/**
	 * Operators mapped to the machine's centre, encrypted to the machine.
	 *
	 * @param keyIndex machine key index
	 * @return signed envelope with {@code userDetails}
	 */
	@GetMapping({ "/v2/userdetails", "/userdetails" })
	public ResponseEntity<byte[]> userDetails(@RequestParam(name = "keyindex", required = false) String keyIndex) {
		String id = "mosip.syncdata.userdetails";
		Optional<Machine> machine = machines.findByKeyIndex(keyIndex);
		if (machine.isEmpty()) {
			return unknownMachine(id, keyIndex);
		}
		List<Map<String, Object>> list = new ArrayList<>();
		for (User user : users.all()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("userId", user.getUserId());
			row.put("langCode", properties.getPrimaryLang());
			row.put("name", user.getName());
			row.put("isActive", user.isActive());
			row.put("isDeleted", Boolean.FALSE);
			row.put("regCenterId", machine.get().getCenterId());
			list.add(row);
		}
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("lastSyncTime", ResponseFactory.now());
		response.put("userDetails", encryptFor(machine.get(), toJson(list)));
		return responses.signed(id, response);
	}

	/**
	 * Device trust anchors (CA certificates by partner domain).
	 *
	 * @return signed envelope with {@code certificateDTOList}
	 */
	@GetMapping("/getcacertificates")
	public ResponseEntity<byte[]> caCertificates() {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("certificateDTOList", caCertificates.certificates());
		response.put("lastSyncTime", ResponseFactory.now());
		return responses.signed("mosip.syncdata.cacertificates", response);
	}

	private Map<String, Object> certificateResponse(java.security.cert.X509Certificate cert) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("certificate", KeyMaterial.toPem(cert));
		response.put("certSignRequest", null);
		response.put("issuedAt", cert.getNotBefore().toInstant().toString());
		response.put("expiryAt", cert.getNotAfter().toInstant().toString());
		response.put("timestamp", ResponseFactory.now());
		return response;
	}

	private ResponseEntity<byte[]> unknownMachine(String id, String keyIndex) {
		LOGGER.warn("Request from unregistered machine key index {}", keyIndex);
		return responses.error(id, "KER-SNC-MACHINE-404", "Machine with key index " + keyIndex + " is not registered");
	}

	private String encryptFor(Machine machine, String json) {
		try {
			return ClientCrypto.encryptToBase64Url(machine.getClientType(),
					ClientCrypto.decodeBase64Any(machine.getPublicKey()), json);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encryption for machine " + machine.getName() + " failed", e);
		}
	}

	private void logSignatureCheck(Machine machine, String[] jwtParts) {
		byte[] signed = ClientCrypto.decodeBase64Any(jwtParts[1]);
		boolean valid = ClientCrypto.verifyClientSignature(ClientCrypto.decodeBase64Any(machine.getSignPublicKey()),
				ClientCrypto.decodeBase64Any(jwtParts[2]), signed);
		if (!valid) {
			LOGGER.warn("Login request signature from {} did not verify (accepted anyway)", machine.getName());
		}
	}

	private Map<String, Object> decodeJson(String part) {
		try {
			return mapper.readValue(new String(ClientCrypto.decodeBase64Any(part), StandardCharsets.UTF_8),
					new TypeReference<Map<String, Object>>() {
					});
		} catch (JsonProcessingException | IllegalArgumentException e) {
			return null;
		}
	}

	private String toJson(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}
}
