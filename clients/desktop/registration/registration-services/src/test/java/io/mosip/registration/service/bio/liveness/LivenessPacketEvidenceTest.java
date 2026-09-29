package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.biometrics.commons.CbeffValidator;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BIRInfo;
import io.mosip.kernel.biometrics.entities.VersionType;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.dto.packetmanager.metadata.BiometricsMetaInfoDto;
import io.mosip.registration.util.common.BIRBuilder;

/**
 * Tests the packet evidence of a face capture (design section 4.18): the {@code LIVENESS_*} entries in the CBEFF
 * BIR {@code others} map, their survival through CBEFF XML, and the liveness fields of the packet meta info.
 */
public class LivenessPacketEvidenceTest {

	private static Map<String, String> evidence() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_PASS);
		m.put(PacketEvidenceKeys.METHOD, "PASSIVE_ACTIVE");
		m.put(PacketEvidenceKeys.SCORE, "91");
		m.put(PacketEvidenceKeys.CHALLENGES, "BLINK:OK,TURN_LEFT:OK");
		m.put(PacketEvidenceKeys.ENGINE, "mosip-onnx-default@1.0.0");
		m.put(PacketEvidenceKeys.SESSION, "s-123");
		m.put(PacketEvidenceKeys.DEVICE_LEVEL, "L0");
		return m;
	}

	private static BiometricsDto face(Map<String, String> evidence) {
		BiometricsDto dto = new BiometricsDto("face", new byte[] { 1, 2, 3 }, 90);
		dto.setPayLoad("{\"bioValue\":\"QUJD\",\"timestamp\":\"2026-09-29T10:00:00Z\"}");
		dto.setSpecVersion("0.9.5");
		dto.setLivenessEvidence(evidence);
		return dto;
	}

	@Test
	public void birOthersCarryLivenessEvidenceNextToStockEntries() {
		BIR bir = new BIRBuilder().buildBIR(face(evidence()));
		Map<String, String> others = bir.getOthers();
		assertEquals("PASS", others.get(PacketEvidenceKeys.DECISION));
		assertEquals("PASSIVE_ACTIVE", others.get(PacketEvidenceKeys.METHOD));
		assertEquals("BLINK:OK,TURN_LEFT:OK", others.get(PacketEvidenceKeys.CHALLENGES));
		assertTrue("stock entries are kept", others.containsKey("SPEC_VERSION"));
	}

	@Test
	public void captureWithoutEvidenceIsUnchanged() {
		BIR bir = new BIRBuilder().buildBIR(face(null));
		for (String key : bir.getOthers().keySet()) {
			assertFalse(key, key.startsWith("LIVENESS_"));
		}
	}

	@Test
	public void evidenceSurvivesCbeffXml() throws Exception {
		BIR bir = new BIRBuilder().buildBIR(face(evidence()));
		BIR root = new BIR.BIRBuilder().withVersion(new VersionType(1, 1)).withCbeffversion(new VersionType(1, 1))
				.withBirInfo(new BIRInfo.BIRInfoBuilder().withIntegrity(false).build()).build();
		root.setBirs(Collections.singletonList(bir));
		byte[] xsd;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("cbeff.xsd")) {
			xsd = in.readAllBytes();
		}
		byte[] xml = CbeffValidator.createXMLBytes(root, xsd);
		BIR parsed = CbeffValidator.getBIRFromXML(xml).getBirs().get(0);
		assertEquals("PASS", parsed.getOthers().get(PacketEvidenceKeys.DECISION));
		assertEquals("s-123", parsed.getOthers().get(PacketEvidenceKeys.SESSION));
	}

	@Test
	public void metaInfoHasDecisionAndMethodOnlyForLivenessCaptures() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String withEvidence = mapper.writeValueAsString(new BiometricsMetaInfoDto(1, false, "1", evidence()));
		assertTrue(withEvidence, withEvidence.contains("\"livenessDecision\":\"PASS\""));
		assertTrue(withEvidence, withEvidence.contains("\"livenessMethod\":\"PASSIVE_ACTIVE\""));

		BiometricsMetaInfoDto plain = new BiometricsMetaInfoDto(1, false, "1", null);
		assertNull(plain.getLivenessDecision());
		String json = mapper.writeValueAsString(plain);
		assertFalse(json, json.contains("liveness"));
	}
}
