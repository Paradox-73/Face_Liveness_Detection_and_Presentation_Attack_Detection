package io.mosip.mocksync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.crypto.ClientCrypto;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.web.ResponseFactory;
import io.mosip.mocksync.web.SyncDataController;

/**
 * T-SYNC-04 contract: what the Android Registration Client ({@code clients/android}, upstream release-1.2.x
 * {@code c4d3ca7}) reads from each first-login and sync response. A simulated tablet registers with the JSON of the
 * app's Machine Keys dialog, logs in, runs the seven sync steps and decrypts every part the way the app's
 * {@code LocalClientCryptoServiceImpl.decrypt} does. The field lists are the keys the app's repositories read with
 * {@code JSONObject.get*} (a missing key throws and the whole entity is skipped).
 */
@SpringBootTest(properties = { "mocksync.http-port=0", "server.ssl.enabled=false" })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AndroidClientContractTest {

	/** Mixed case, like the random 12-character name the app creates on first launch. */
	private static final String MACHINE_NAME = "7qP2xR9kLm3Z";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int WRAPPED_KEY_LENGTH = 256;

	/** Keys each structured entity row must carry: {@code MasterDataServiceImpl.saveStructuredData} and repositories. */
	private static final Map<String, List<String>> REQUIRED_FIELDS = requiredFields();

	/** Sync job {@code apiName}s the Flutter sync provider looks up to log each step's last sync time. */
	private static final Set<String> JOB_API_NAMES = Set.of("masterSyncJob", "synchConfigDataJob",
			"userDetailServiceJob", "keyPolicySyncJob", "syncCertificateJob", "publicKeySyncJob",
			"preRegistrationDataSyncJob");

	private static Path workDir;

	@Autowired
	private MockMvc mvc;

	private final KeyPair encryptionKeys = KeyMaterial.newRsaKeyPair();
	private final KeyPair signingKeys = KeyMaterial.newRsaKeyPair();
	private Map<String, Object> machine;

	@DynamicPropertySource
	static void workDirectory(DynamicPropertyRegistry registry) throws Exception {
		workDir = Files.createTempDirectory("mocksync-android-test");
		registry.add("mocksync.work-dir", () -> workDir.toString());
	}

	@BeforeAll
	void registerMachineFromMachineKeysDialog() throws Exception {
		Map<String, String> dialogJson = new LinkedHashMap<>();
		dialogJson.put("signPublicKey", ClientCrypto.base64Url(signingKeys.getPublic().getEncoded()));
		dialogJson.put("name", MACHINE_NAME);
		dialogJson.put("publicKey", ClientCrypto.base64Url(encryptionKeys.getPublic().getEncoded()));
		dialogJson.put("version", "Alpha");
		dialogJson.put("clientType", "ANDROID");
		machine = json(mvc.perform(post("/admin/machines").contentType(MediaType.APPLICATION_JSON)
				.content(MAPPER.writeValueAsString(dialogJson))).andReturn().getResponse());
		assertEquals("ANDROID", machine.get("clientType"));
	}

	@Test
	void actuatorInfoSelectsTheV2SyncApis() throws Exception {
		Map<String, Object> info = json(mvc.perform(get("/v1/syncdata/actuator/info")).andReturn().getResponse());
		Map<?, ?> build = (Map<?, ?>) info.get("build");
		assertFalse(((String) build.get("version")).startsWith("1.1.5"), "1.1.5 would switch the app to v1 endpoints");
		assertTrue(build.values().stream().allMatch(String.class::isInstance), "ActuatorInfo.build is Map<String, String>");
		assertTrue(info.get("git") instanceof Map);
	}

	@Test
	void loginTokenPassesSessionManagerChecks() throws Exception {
		Map<String, Object> tokens = MAPPER.readValue(arcDecrypt(stringResponse(login("Mosip@Officer1"))),
				new TypeReference<Map<String, Object>>() {
				});
		assertNotNull(tokens.get("refreshToken"));
		Long.parseLong(String.valueOf(tokens.get("expiryTime")));
		Long.parseLong(String.valueOf(tokens.get("refreshExpiryTime")));

		DecodedJWT token = JWT.decode((String) tokens.get("token"));
		assertTrue(token.getExpiresAt().after(new Date(System.currentTimeMillis() + 15_000)), "not expired (15 s leeway)");
		List<?> roles = (List<?>) token.getClaim("realm_access").asMap().get("roles");
		assertTrue(roles.contains("REGISTRATION_OFFICER"));
		assertEquals("Test Officer One", token.getClaim("name").asString());
		assertEquals("officer1", token.getClaim("preferred_username").asString());
	}

	@Test
	void unknownMachineGetsTheCodeTheAppShowsAsNotOnboarded() throws Exception {
		KeyPair other = KeyMaterial.newRsaKeyPair();
		List<?> errors = (List<?>) json(login("Mosip@Officer1", other)).get("errors");
		assertEquals(SyncDataController.MACHINE_NOT_FOUND, ((Map<?, ?>) errors.get(0)).get("errorCode"));
	}

	@Test
	void masterDataRowsCarryEveryFieldTheRepositoriesRead() throws Exception {
		Map<String, List<Map<String, Object>>> entities = structuredEntities();
		REQUIRED_FIELDS.forEach((entity, fields) -> {
			List<Map<String, Object>> rows = entities.get(entity);
			assertNotNull(rows, entity + " is synced");
			assertFalse(rows.isEmpty(), entity + " has rows");
			for (Map<String, Object> row : rows) {
				for (String field : fields) {
					assertTrue(row.containsKey(field), entity + "." + field + " is present in " + row);
				}
			}
		});
	}

	@Test
	void machineAndCentreResolveForThisTablet() throws Exception {
		Map<String, List<Map<String, Object>>> entities = structuredEntities();
		Map<String, Object> machineRow = entities.get("Machine").get(0);
		assertTrue(MACHINE_NAME.equalsIgnoreCase((String) machineRow.get("name")),
				"MachineMasterDao.findMachineByName compares lower(name)");
		assertEquals(machine.get("machineId"), machineRow.get("id"));
		assertEquals(Boolean.TRUE, machineRow.get("isActive"));
		assertTrue(entities.get("RegistrationCenter").stream()
				.anyMatch(c -> c.get("id").equals(machineRow.get("regCenterId")) && Boolean.TRUE.equals(c.get("isActive"))),
				"the machine's centre is synced and active");
	}

	@Test
	void syncJobsAndDynamicFieldsHaveTheShapesTheAppUses() throws Exception {
		Map<String, List<Map<String, Object>>> entities = structuredEntities();
		Set<String> apiNames = entities.get("SyncJobDef").stream().filter(j -> Boolean.TRUE.equals(j.get("isActive")))
				.map(j -> (String) j.get("apiName")).collect(Collectors.toSet());
		assertTrue(apiNames.containsAll(JOB_API_NAMES), "active jobs " + apiNames);

		List<Map<String, Object>> dynamic = dynamicFields();
		assertFalse(dynamic.isEmpty());
		for (Map<String, Object> field : dynamic) {
			for (String key : List.of("id", "dataType", "name", "langCode", "isActive", "fieldVal")) {
				assertTrue(field.containsKey(key), "dynamic field " + key + " in " + field.get("name"));
			}
			for (Object value : (List<?>) field.get("fieldVal")) {
				assertTrue(((Map<?, ?>) value).containsKey("code") && ((Map<?, ?>) value).containsKey("value"));
			}
		}
	}

	@Test
	void mvelScriptEntryHasTheDownloadFieldsTheAppReads() throws Exception {
		Map<String, Object> script = MAPPER.readValue(arcDecrypt(entry("applicanttype.mvel").get("data").toString()),
				new TypeReference<Map<String, Object>>() {
				});
		assertTrue(script.get("headers") instanceof String, "downloadUrlData: getString(\"headers\")");
		assertTrue(script.get("encrypted") instanceof Boolean, "downloadUrlData: getBoolean(\"encrypted\")");
		assertTrue(((String) script.get("url")).endsWith("/v1/syncdata/scripts/applicanttype.mvel"));
	}

	@Test
	void configsDecryptToFlatMapsWithLanguageAndLivenessKeys() throws Exception {
		Map<?, ?> detail = (Map<?, ?>) response(mvc.perform(get("/v1/syncdata/configs/" + keyIndex())
				.param("version", "1.2.0.2")).andReturn().getResponse()).get("configDetail");
		Map<String, Object> registration = MAPPER.readValue(arcDecrypt((String) detail.get("registrationConfiguration")),
				new TypeReference<Map<String, Object>>() {
				});
		Map<String, Object> global = MAPPER.readValue(arcDecrypt((String) detail.get("globalConfiguration")),
				new TypeReference<Map<String, Object>>() {
				});
		assertEquals("eng", registration.get("mosip.mandatory-languages"));
		assertEquals("Y", registration.get("mosip.registration.liveness.enable"));
		assertFalse(global.isEmpty());
	}

	@Test
	void userDetailsDecryptToTheListUserDetailRepositorySaves() throws Exception {
		Map<String, Object> response = response(mvc.perform(get("/v1/syncdata/v2/userdetails")
				.param("keyindex", keyIndex()).param("version", "1.2.0.2")).andReturn().getResponse());
		List<Map<String, Object>> users = list(arcDecrypt((String) response.get("userDetails")));
		assertTrue(users.stream().anyMatch(u -> "officer1".equals(u.get("userId"))));
		for (Map<String, Object> user : users) {
			assertTrue(user.containsKey("isActive") && user.containsKey("regCenterId"), "UserDetailRepository fields");
		}
	}

	@Test
	void idSchemaCarriesAProcessSpecForEveryProcessKey() throws Exception {
		Map<String, Object> schema = response(mvc.perform(get("/v1/syncdata/latestidschema")
				.param("version", "1.2.0.2").param("domain", "registration-client")).andReturn().getResponse());
		assertTrue(schema.get("idVersion") instanceof Number);
		assertNotNull(schema.get("newProcess"), "a null newProcess makes the app migrate a 1.1.5 UI spec");
		List<String> processKeys = schema.keySet().stream().filter(k -> k.toLowerCase().endsWith("process"))
				.collect(Collectors.toList());
		assertTrue(processKeys.containsAll(List.of("newProcess", "updateProcess", "lostProcess")), processKeys.toString());
		for (String key : processKeys) {
			Map<?, ?> process = (Map<?, ?>) schema.get(key);
			assertTrue(process.get("screens") instanceof List, key + ".screens");
		}
	}

	@Test
	void certificatesForPolicyKeyKernelSignAndDeviceTrustAreServed() throws Exception {
		String reference = machine.get("centerId") + "_" + machine.get("machineId");
		for (String[] query : List.of(new String[] { "REGISTRATION", reference }, new String[] { "KERNEL", "SIGN" })) {
			Map<String, Object> cert = response(mvc.perform(get("/v1/syncdata/getCertificate")
					.param("applicationId", query[0]).param("referenceId", query[1])).andReturn().getResponse());
			KeyMaterial.parseCertificate(((String) cert.get("certificate")).getBytes(StandardCharsets.US_ASCII));
		}
		List<?> caCerts = (List<?>) response(mvc.perform(get("/v1/syncdata/getcacertificates")).andReturn()
				.getResponse()).get("certificateDTOList");
		assertTrue(caCerts.stream().anyMatch(c -> "DEVICE".equals(((Map<?, ?>) c).get("partnerDomain"))),
				"MasterDataServiceImpl.saveCACertificate keeps DEVICE rows only");
	}

	private Map<String, List<Map<String, Object>>> structuredEntities() throws Exception {
		Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
		for (Map<String, Object> item : dataToSync()) {
			if ("structured".equals(item.get("entityType"))) {
				result.put((String) item.get("entityName"), list(arcDecrypt((String) item.get("data"))));
			}
		}
		return result;
	}

	private List<Map<String, Object>> dynamicFields() throws Exception {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> item : dataToSync()) {
			if ("dynamic".equals(item.get("entityType"))) {
				result.addAll(list(arcDecrypt((String) item.get("data"))));
			}
		}
		return result;
	}

	private Map<String, Object> entry(String entityName) throws Exception {
		return dataToSync().stream().filter(e -> entityName.equals(e.get("entityName"))).findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> dataToSync() throws Exception {
		Map<String, Object> response = response(mvc.perform(get("/v1/syncdata/v2/clientsettings")
				.param("keyindex", keyIndex()).param("version", "1.2.0.2")).andReturn().getResponse());
		assertNotNull(response.get("lastSyncTime"));
		return (List<Map<String, Object>>) response.get("dataToSync");
	}

	private String keyIndex() {
		return (String) machine.get("keyIndex");
	}

	private MockHttpServletResponse login(String password) throws Exception {
		return login(password, signingKeys);
	}

	/** Builds the request like {@code SyncRestUtil.getAuthRequest}: kid = signing key fingerprint. */
	private MockHttpServletResponse login(String password, KeyPair signer) throws Exception {
		String header = String.format("{\"kid\" : \"%s\"}", ClientCrypto.fingerprint(signer.getPublic().getEncoded()));
		String payload = String.format("{\"userId\" : \"%s\", \"password\": \"%s\", \"authType\":\"%s\", \"timestamp\" : \"%s\"}",
				"officer1", password, "NEW", ResponseFactory.now());
		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initSign(signer.getPrivate());
		signature.update(payload.getBytes(StandardCharsets.UTF_8));
		String jwt = ClientCrypto.base64Url(header.getBytes(StandardCharsets.UTF_8)) + "."
				+ ClientCrypto.base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "."
				+ ClientCrypto.base64Url(signature.sign());
		return mvc.perform(post("/v1/syncdata/authenticate/useridpwd").contentType(MediaType.APPLICATION_JSON)
				.content(MAPPER.writeValueAsString(Map.of("request", jwt, "requesttime", ResponseFactory.now()))))
				.andReturn().getResponse();
	}

	/**
	 * Decrypts like the app's {@code LocalClientCryptoServiceImpl.decrypt}: the session key is unwrapped with OAEP
	 * SHA-256 and MGF1 SHA-256, retried with MGF1 SHA-1, then AES-GCM with a 12-byte IV and 32-byte AAD.
	 */
	private String arcDecrypt(String base64Url) throws GeneralSecurityException {
		byte[] data = ClientCrypto.decodeBase64Any(base64Url);
		byte[] wrapped = Arrays.copyOfRange(data, 0, WRAPPED_KEY_LENGTH);
		byte[] sessionKey;
		try {
			sessionKey = oaepUnwrap(encryptionKeys.getPrivate(), wrapped, MGF1ParameterSpec.SHA256);
		} catch (GeneralSecurityException e) {
			sessionKey = oaepUnwrap(encryptionKeys.getPrivate(), wrapped, MGF1ParameterSpec.SHA1);
		}
		int ivEnd = WRAPPED_KEY_LENGTH + ClientCrypto.IV_LENGTH;
		int aadEnd = ivEnd + ClientCrypto.AAD_LENGTH;
		byte[] plain = ClientCrypto.aesGcm(Cipher.DECRYPT_MODE, sessionKey, Arrays.copyOfRange(data, WRAPPED_KEY_LENGTH, ivEnd),
				Arrays.copyOfRange(data, ivEnd, aadEnd), Arrays.copyOfRange(data, aadEnd, data.length));
		return new String(plain, StandardCharsets.UTF_8);
	}

	private static byte[] oaepUnwrap(PrivateKey key, byte[] wrapped, MGF1ParameterSpec mgf) throws GeneralSecurityException {
		Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
		rsa.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec("SHA-256", "MGF1", mgf, PSource.PSpecified.DEFAULT));
		return rsa.doFinal(wrapped);
	}

	private static Map<String, List<String>> requiredFields() {
		Map<String, List<String>> fields = new LinkedHashMap<>();
		fields.put("Machine", List.of("id", "isActive", "name", "regCenterId"));
		fields.put("RegistrationCenter", List.of("id", "langCode", "latitude", "longitude", "locationCode", "name", "isActive"));
		fields.put("DocumentType", List.of("code", "langCode", "name", "description"));
		fields.put("ApplicantValidDocument", List.of("docTypeCode", "docCatCode", "isActive"));
		fields.put("Template", List.of("id", "langCode", "templateTypeCode", "fileText", "fileFormatCode", "name", "isActive"));
		fields.put("Location", List.of("code", "langCode", "name", "hierarchyLevel", "hierarchyName", "isActive", "parentLocCode"));
		fields.put("LocationHierarchy", List.of("hierarchyLevel", "hierarchyLevelName", "langCode"));
		fields.put("BlocklistedWords", List.of("word", "isActive"));
		fields.put("SyncJobDef", List.of("id", "name", "apiName", "parentSyncJobId", "syncFreq", "lockDuration", "langCode",
				"isDeleted", "isActive"));
		fields.put("Language", List.of("code", "name", "nativeName", "isActive", "isDeleted"));
		fields.put("ReasonList", List.of("code", "name", "langCode", "description"));
		fields.put("PermittedLocalConfig", List.of("code", "name", "type", "isActive"));
		return fields;
	}

	private static String stringResponse(MockHttpServletResponse response) throws Exception {
		return (String) json(response).get("response");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> response(MockHttpServletResponse response) throws Exception {
		return (Map<String, Object>) json(response).get("response");
	}

	private static Map<String, Object> json(MockHttpServletResponse response) throws Exception {
		return MAPPER.readValue(response.getContentAsByteArray(), new TypeReference<Map<String, Object>>() {
		});
	}

	private static List<Map<String, Object>> list(String json) throws Exception {
		return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
		});
	}
}
