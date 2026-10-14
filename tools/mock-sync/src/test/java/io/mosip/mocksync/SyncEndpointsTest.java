/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

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

import io.mosip.kernel.crypto.jce.core.CryptoCore;
import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.ClientCrypto;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.data.ConfigService;
import io.mosip.mocksync.web.ModelBundleController;
import io.mosip.mocksync.web.ResponseFactory;
import io.mosip.mocksync.web.ScriptController;
import io.mosip.mocksync.web.SyncDataController;

/**
 * Endpoint shape tests: a simulated desktop machine with local (non-TPM) keys goes through first login and every
 * initial sync call. Encrypted parts are decrypted and signatures verified with keymanager code.
 */
@SpringBootTest(properties = { "mocksync.http-port=0", "server.ssl.enabled=false" })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncEndpointsTest {

	private static final String MACHINE = "test-machine-01";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static Path workDir;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MockSyncProperties properties;

	@Autowired
	private ConfigService configService;

	private final KeyPair machineKeys = KeyMaterial.newRsaKeyPair();
	private String keyIndex;
	private String signCertPem;
	private CryptoCore cryptoCore;

	@DynamicPropertySource
	static void workDirectory(DynamicPropertyRegistry registry) throws Exception {
		workDir = Files.createTempDirectory("mocksync-test");
		registry.add("mocksync.work-dir", () -> workDir.toString());
	}

	@BeforeAll
	void registerMachine() throws Exception {
		cryptoCore = MosipClientSide.cryptoCore();
		String body = MAPPER.writeValueAsString(Map.of("name", MACHINE.toUpperCase(),
				"publicKey", ClientCrypto.base64Url(machineKeys.getPublic().getEncoded())));
		Map<String, Object> machine = json(mvc.perform(post("/admin/machines").contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn().getResponse());
		keyIndex = (String) machine.get("keyIndex");
		assertEquals(MACHINE, machine.get("name"), "machine names are stored in lower case");
		assertEquals("LOCAL", machine.get("clientType"));
		assertEquals(ClientCrypto.fingerprint(machineKeys.getPublic().getEncoded()), keyIndex);

		MockHttpServletResponse certResponse = mvc.perform(get("/v1/syncdata/getCertificate")
				.param("applicationId", "KERNEL").param("referenceId", "SIGN")).andReturn().getResponse();
		signCertPem = (String) response(certResponse).get("certificate");
		assertSigned(certResponse);
	}

	@Test
	void healthAndInfo() throws Exception {
		assertEquals("UP", json(mvc.perform(get("/v1/syncdata/actuator/health")).andReturn().getResponse()).get("status"));
		Map<String, Object> info = json(mvc.perform(get("/v1/syncdata/actuator/info")).andReturn().getResponse());
		assertEquals("1.2.0.2", ((Map<?, ?>) info.get("build")).get("version"));
		assertNotNull(info.get("git"));
	}

	@Test
	void livenessModelBundleIsServedOnlyWhenConfigured() throws Exception {
		assertEquals("", configService.effectiveValue("mosip.registration.liveness.model.update_url"),
				"the online model update is off by default");
		Path bundle = Files.write(workDir.resolve("bundle-1.3.1.zip"), new byte[] { 'P', 'K', 3, 4, 42 });
		try {
			properties.setLivenessModelBundle(bundle.toString());
			MockHttpServletResponse served = mvc.perform(get(ModelBundleController.PATH)).andReturn().getResponse();
			assertEquals(200, served.getStatus());
			assertEquals("application/zip", served.getContentType());
			assertArrayEquals(Files.readAllBytes(bundle), served.getContentAsByteArray());

			properties.setLivenessModelBundle(workDir.resolve("missing.zip").toString());
			assertEquals(404, mvc.perform(get(ModelBundleController.PATH)).andReturn().getResponse().getStatus());
		} finally {
			properties.setLivenessModelBundle("");
		}
		assertEquals(404, mvc.perform(get(ModelBundleController.PATH)).andReturn().getResponse().getStatus());
	}

	@Test
	void loginReturnsEncryptedTokensWithClaimsTheClientReads() throws Exception {
		MockHttpServletResponse response = login("officer1", "Mosip@Officer1", machineKeys);
		Map<String, Object> tokens = MAPPER.readValue(decrypt((String) json(response).get("response")),
				new TypeReference<Map<String, Object>>() {
				});

		DecodedJWT token = JWT.decode((String) tokens.get("token"));
		assertTrue(token.getExpiresAt().after(new Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000)));
		assertEquals("Test Officer One", token.getClaim("name").asString());
		assertEquals(List.of("REGISTRATION_OFFICER"), token.getClaim("realm_access").asMap().get("roles"));
		assertNotNull(JWT.decode((String) tokens.get("refreshToken")).getExpiresAt());
		assertTrue(((Number) tokens.get("expiryTime")).longValue() > 0);
	}

	@Test
	void loginRejectsWrongPasswordAndUnknownMachine() throws Exception {
		assertEquals("KER-ATH-401", firstErrorCode(login("officer1", "wrong", machineKeys)));
		assertEquals(SyncDataController.MACHINE_NOT_FOUND,
				firstErrorCode(login("officer1", "Mosip@Officer1", KeyMaterial.newRsaKeyPair())));
	}

	@Test
	void machineKeyVerificationReturnsKeyIndex() throws Exception {
		Map<String, Object> request = Map.of("machineName", MACHINE, "publicKey",
				ClientCrypto.base64Url(machineKeys.getPublic().getEncoded()), "signPublicKey",
				ClientCrypto.base64Url(machineKeys.getPublic().getEncoded()));
		MockHttpServletResponse response = mvc.perform(post("/v1/syncdata/tpm/publickey/verify")
				.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of("request", request))))
				.andReturn().getResponse();
		assertEquals(keyIndex, response(response).get("keyIndex"));
		assertSigned(response);
	}

	@Test
	void configsAreEncryptedResolvedAndIncludeLivenessKeys() throws Exception {
		Map<String, String> registration = registrationConfig();
		Map<String, String> global = config("globalConfiguration");

		assertEquals("Y", registration.get("mosip.registration.liveness.enable"));
		assertEquals("BLINK,SMILE,TURN_LEFT,TURN_RIGHT",
				registration.get("mosip.registration.liveness.active.challenge_types"));
		assertEquals("eng", registration.get("mosip.mandatory-languages"), "self-referencing keys keep their value");
		assertEquals("applicanttype.mvel", global.get("mosip.kernel.applicantType.mvel.file"));
		assertNull(registration.get("mosip.hostname"));
		assertNull(registration.get("mosip.registration.initial_setup"), "client state keys are never sent");
		assertNull(registration.get("mosip.reg.services.current.version"), "client state keys are never sent");
		assertNull(global.get("mosip.hostname"));
		assertFalse(registration.values().stream().anyMatch(v -> v.contains("${")), "no unresolved placeholders");
		assertFalse(global.values().stream().anyMatch(v -> v.contains("${")), "no unresolved placeholders");
	}

	@Test
	void adminConfigChangeArrivesWithNextConfigSync() throws Exception {
		String key = "mosip.registration.liveness.passive.threshold";
		mvc.perform(put("/admin/config").contentType(MediaType.APPLICATION_JSON)
				.content(MAPPER.writeValueAsString(Map.of(key, "77")))).andReturn();
		assertEquals("77", registrationConfig().get(key));

		mvc.perform(delete("/admin/config/" + key)).andReturn();
		assertEquals("80", registrationConfig().get(key));
	}

	@Test
	void clientSettingsCarryEntitiesTheClientCanMap() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/syncdata/v2/clientsettings").param("keyindex", keyIndex))
				.andReturn().getResponse();
		assertSigned(response);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> dataToSync = (List<Map<String, Object>>) response(response).get("dataToSync");
		Map<String, String> byName = dataToSync.stream().collect(Collectors.toMap(e -> (String) e.get("entityName"),
				e -> new String(decrypt((String) e.get("data")), StandardCharsets.UTF_8), (a, b) -> a));

		List<Map<String, Object>> machine = list(byName.get("Machine"));
		assertEquals(MACHINE, machine.get(0).get("name"));
		assertEquals("10001", machine.get(0).get("regCenterId"));

		Map<String, Object> center = list(byName.get("RegistrationCenter")).stream()
				.filter(c -> "eng".equals(c.get("langCode"))).findFirst().orElseThrow();
		assertEquals("10001", center.get("id"));
		assertTrue(center.get("numberOfKiosks") instanceof Integer, "Integer entity field");
		assertEquals("00:15:00", center.get("perKioskProcessTime"), "java.sql.Time entity field");
		assertTrue(center.get("latitude") instanceof String, "String entity field");

		assertTrue(list(byName.get("AppAuthenticationMethod")).stream().anyMatch(m -> "FACE".equals(m.get("authMethodCode"))
				&& "login_auth".equals(m.get("processId")) && "REGISTRATION_OFFICER".equals(m.get("roleCode"))
				&& Boolean.TRUE.equals(m.get("isActive"))), "FACE login is active");
		assertTrue(list(byName.get("PermittedLocalConfig")).stream()
				.anyMatch(p -> "mosip.registration.liveness.passive.threshold".equals(p.get("name"))));
		assertFalse(list(byName.get("PermittedLocalConfig")).stream()
				.anyMatch(p -> "mosip.registration.liveness.enable".equals(p.get("name"))), "master switch is server-only");
		assertTrue(list(byName.get("Location")).get(0).get("hierarchyLevel") instanceof Integer);
		assertTrue(list(byName.get("BiometricAttribute")).stream().allMatch(a -> "eng".equals(a.get("langCode"))),
				"attributes without a biometric type in the same language are dropped");
		assertFalse(list(byName.get("Template")).isEmpty());

		List<Map<String, Object>> gender = list(byName.get("gender"));
		assertFalse(((List<?>) gender.get(0).get("fieldVal")).isEmpty(), "dynamic field values grouped per language");
		Map<String, Object> script = MAPPER.readValue(byName.get("applicanttype.mvel"),
				new TypeReference<Map<String, Object>>() {
				});
		assertTrue(((String) script.get("url")).endsWith("/v1/syncdata/scripts/applicanttype.mvel"));
		assertEquals("Authorization:OAUTH", script.get("auth-token"));
	}

	@Test
	void permittedLocalConfigOffersOnlyKeysTheCoreAllowsLocally() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/syncdata/v2/clientsettings").param("keyindex", keyIndex))
				.andReturn().getResponse();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> dataToSync = (List<Map<String, Object>>) response(response).get("dataToSync");
		Map<String, String> byName = dataToSync.stream().collect(Collectors.toMap(e -> (String) e.get("entityName"),
				e -> new String(decrypt((String) e.get("data")), StandardCharsets.UTF_8), (a, b) -> a));
		List<Object> names = list(byName.get("PermittedLocalConfig")).stream().map(p -> p.get("name"))
				.collect(Collectors.toList());

		assertFalse(names.contains("mosip.registration.liveness.active.challenge_types"));
		assertFalse(names.contains("mosip.registration.liveness.passive.timeout_ms"));
		assertFalse(names.contains("mosip.registration.liveness.binding.min_similarity"));
		assertTrue(names.contains("mosip.registration.liveness.max_retries"));
		assertTrue(names.contains("mosip.registration.liveness.diagnostic.enable"));
		assertTrue(names.contains("mosip.registration.liveness.passive.threshold"));
		assertTrue(names.contains("mosip.registration.liveness.active.challenge_timeout_ms"));
	}

	@Test
	void latestIdSchemaCarriesSchemaAndProcessSpecs() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/syncdata/latestidschema").param("schemaVersion", "0"))
				.andReturn().getResponse();
		assertSigned(response);
		Map<String, Object> schema = response(response);
		assertEquals(0.1, ((Number) schema.get("idVersion")).doubleValue());
		assertTrue(((String) schema.get("schemaJson")).contains("individualBiometrics"));
		assertNotNull(schema.get("newProcess"));
		assertNotNull(schema.get("settings"));
		assertEquals("2021-01-01T00:00:00.000", schema.get("effectiveFrom"));
	}

	@Test
	void userDetailsAreEncrypted() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/syncdata/v2/userdetails").param("keyindex", keyIndex))
				.andReturn().getResponse();
		assertSigned(response);
		List<Map<String, Object>> users = list(new String(decrypt((String) response(response).get("userDetails")),
				StandardCharsets.UTF_8));
		assertEquals(3, users.size());
		assertEquals("10001", users.get(0).get("regCenterId"));
	}

	@Test
	void caCertificatesIncludeMockMdsRootsAndDeviceCa() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/syncdata/getcacertificates")).andReturn().getResponse();
		assertSigned(response);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> certs = (List<Map<String, Object>>) response(response).get("certificateDTOList");
		List<String> domains = certs.stream().map(c -> (String) c.get("partnerDomain")).collect(Collectors.toList());
		assertEquals(List.of("DEVICE", "FTM", "DEVICE", "DEVICE"), domains, "rootca, rootcaftm twice, MockSync DEVICE CA");
		for (Map<String, Object> cert : certs) {
			KeyMaterial.parseCertificate(((String) cert.get("certData")).getBytes(StandardCharsets.US_ASCII));
		}
	}

	@Test
	void scriptDownloadHasLengthAndFileSignature() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/syncdata/scripts/applicanttype.mvel")
				.param("keyindex", keyIndex)).andReturn().getResponse();
		byte[] content = response.getContentAsByteArray();
		assertEquals(String.valueOf(content.length), response.getHeader("Content-Length"));
		String actualData = String.format("{\"hash\":\"%s\"}", ScriptController.sha256UpperHex(content));
		assertTrue(MosipClientSide.jwtVerify(response.getHeader(ScriptController.FILE_SIGNATURE_HEADER), actualData,
				signCertPem));
		assertTrue(new String(content, StandardCharsets.UTF_8).contains("getApplicantType"));
	}

	@Test
	void idaAuthDecryptsAnOnboardingRequestBuiltLikeTheClient() throws Exception {
		Map<String, Object> cert = response(mvc.perform(get("/registrationprocessor/v1/registrationstatus/getCertificate")
				.param("applicationId", "IDA").param("referenceId", "INTERNAL")).andReturn().getResponse());
		X509Certificate idaCert = KeyMaterial.parseCertificate(((String) cert.get("certificate")).getBytes(StandardCharsets.US_ASCII));

		// UserOnboardServiceImpl.getIdaAuthResponse: AES session key, CryptoCore.symmetricEncrypt(key, data, null)
		// and the key wrapped with CryptoCore.asymmetricEncrypt(IDA public key).
		KeyGenerator generator = KeyGenerator.getInstance("AES");
		generator.init(256);
		SecretKey sessionKey = generator.generateKey();
		String dataBlock = ClientCrypto.base64Url(MAPPER.writeValueAsBytes(Map.of("bioType", "FACE", "bioSubType", "")));
		byte[] requestJson = MAPPER.writeValueAsBytes(Map.of("biometrics", List.of(Map.of("data", dataBlock)),
				"timestamp", ResponseFactory.now()));
		Map<String, Object> body = new HashMap<>();
		body.put("individualId", "officer1");
		body.put("request", ClientCrypto.base64Url(cryptoCore.symmetricEncrypt(sessionKey, requestJson, null)));
		body.put("requestSessionKey", ClientCrypto.base64Url(cryptoCore.asymmetricEncrypt(idaCert.getPublicKey(),
				sessionKey.getEncoded())));

		Map<String, Object> auth = response(mvc.perform(post("/registrationprocessor/v1/registrationstatus/auth")
				.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body))).andReturn().getResponse());
		assertEquals(Boolean.TRUE, auth.get("authStatus"));
		assertEquals(Map.of("FACE", 1), auth.get("mockSyncBiometrics"));
	}

	@Test
	void packetSyncDecryptsTheRegistrationList() throws Exception {
		String refId = "10001_10001";
		Map<String, Object> cert = response(mvc.perform(get("/v1/syncdata/getCertificate")
				.param("applicationId", "REGISTRATION").param("referenceId", refId)).andReturn().getResponse());
		X509Certificate regCert = KeyMaterial.parseCertificate(((String) cert.get("certificate")).getBytes(StandardCharsets.US_ASCII));
		byte[] plain = MAPPER.writeValueAsBytes(Map.of("id", "mosip.registration.sync", "request",
				List.of(Map.of("registrationId", "10001100011000120260928120000", "packetId", "p1"))));

		// commons-packet-manager OfflinePacketCryptoServiceImpl.encrypt: nonce | aad | thumbprint | wrapped key
		// | #KEY_SPLITTER# | AES-GCM(data, nonce, aad)
		KeyGenerator generator = KeyGenerator.getInstance("AES");
		generator.init(256);
		SecretKey key = generator.generateKey();
		byte[] nonce = new byte[12];
		byte[] aad = new byte[32];
		new java.security.SecureRandom().nextBytes(nonce);
		new java.security.SecureRandom().nextBytes(aad);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		out.write(nonce);
		out.write(aad);
		out.write(java.security.MessageDigest.getInstance("SHA-256").digest(regCert.getEncoded()));
		out.write(cryptoCore.asymmetricEncrypt(regCert.getPublicKey(), key.getEncoded()));
		out.write("#KEY_SPLITTER#".getBytes(StandardCharsets.US_ASCII));
		out.write(cryptoCore.symmetricEncrypt(key, plain, nonce, aad));

		MockHttpServletResponse response = mvc.perform(post("/registrationprocessor/v1/registrationstatus/sync")
				.header("Center-Machine-RefId", refId).contentType(MediaType.APPLICATION_JSON)
				.content(MAPPER.writeValueAsString(ClientCrypto.base64Url(out.toByteArray())))).andReturn().getResponse();
		assertSigned(response);
		List<?> statuses = (List<?>) json(response).get("response");
		assertEquals(Map.of("registrationId", "10001100011000120260928120000", "status", "SUCCESS"), statuses.get(0));
	}

	@Test
	void packetStatusAnswersEveryRequestedPacket() throws Exception {
		// RegPacketStatusServiceImpl.syncServerStatus: an empty or missing list counts as a failed status sync.
		String request = MAPPER.writeValueAsString(Map.of("id", "mosip.registration.packet.external.status", "request",
				List.of(Map.of("packetId", "p1"), Map.of("registrationId", "r2"))));
		MockHttpServletResponse response = mvc.perform(post("/registrationprocessor/v1/registrationstatus/packetexternalstatus")
				.contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse();
		assertSigned(response);
		List<?> statuses = (List<?>) json(response).get("response");
		assertEquals(List.of(Map.of("packetId", "p1", "statusCode", "RECEIVED"),
				Map.of("registrationId", "r2", "statusCode", "RECEIVED")), statuses);
	}

	private Map<String, String> registrationConfig() throws Exception {
		return config("registrationConfiguration");
	}

	private Map<String, String> config(String part) throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/v1/syncdata/configs/" + keyIndex)).andReturn().getResponse();
		assertSigned(response);
		Map<?, ?> detail = (Map<?, ?>) response(response).get("configDetail");
		return MAPPER.readValue(decrypt((String) detail.get(part)), new TypeReference<Map<String, String>>() {
		});
	}

	private MockHttpServletResponse login(String user, String password, KeyPair keys) throws Exception {
		String header = String.format("{\"kid\" : \"%s\"}", ClientCrypto.fingerprint(keys.getPublic().getEncoded()));
		String payload = String.format("{\"userId\" : \"%s\", \"password\": \"%s\", \"authType\":\"NEW\", \"timestamp\" : \"%s\"}",
				user, password, ResponseFactory.now());
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(keys.getPrivate());
		signer.update(payload.getBytes(StandardCharsets.UTF_8));
		String jwt = ClientCrypto.base64Url(header.getBytes(StandardCharsets.UTF_8)) + "."
				+ ClientCrypto.base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "."
				+ ClientCrypto.base64Url(signer.sign());
		Map<String, String> body = Map.of("id", "", "version", "", "request", jwt, "requesttime", ResponseFactory.now());
		return mvc.perform(post("/v1/syncdata/authenticate/useridpwd").contentType(MediaType.APPLICATION_JSON)
				.content(MAPPER.writeValueAsString(body))).andReturn().getResponse();
	}

	private byte[] decrypt(String base64Url) {
		return MosipClientSide.facadeDecrypt(cryptoCore, machineKeys.getPrivate(), ClientCrypto.decodeBase64Any(base64Url));
	}

	private void assertSigned(MockHttpServletResponse response) throws Exception {
		String signature = response.getHeader(ResponseFactory.SIGNATURE_HEADER);
		assertNotNull(signature, "response-signature header");
		assertTrue(MosipClientSide.verifyResponseSignature(signature, response.getContentAsByteArray(),
				signCertPem != null ? signCertPem : (String) response(response).get("certificate")));
	}

	private static String firstErrorCode(MockHttpServletResponse response) throws Exception {
		List<?> errors = (List<?>) json(response).get("errors");
		return (String) ((Map<?, ?>) errors.get(0)).get("errorCode");
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
