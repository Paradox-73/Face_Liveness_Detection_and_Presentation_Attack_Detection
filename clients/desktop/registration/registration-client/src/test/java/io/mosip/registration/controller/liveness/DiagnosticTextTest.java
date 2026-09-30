package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Optional;

import org.junit.Test;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.service.bio.liveness.LivenessConfig;

/** The diagnostic lines of the liveness window (G5): technical values only, and graceful when parts are missing. */
public class DiagnosticTextTest {

	private static final String SHA = "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0";

	@Test
	public void showsPolicyEngineModelsAndSessionValues() {
		DiagnosticText.Input in = new DiagnosticText.Input();
		in.workflow = "registration";
		in.policy = LivenessConfig.of(Collections.<String, Object>emptyMap()).policy(Workflow.REGISTRATION);
		in.engineStatus = "READY";
		in.engine = Optional.of(EngineInfo.builder("mosip-onnx-default", "1.0.0")
				.model(ModelInfo.of("yunet", "2026may", SHA)).build());
		in.phase = UiPhase.CHECKING;
		in.record = Optional.of(DiagnosticRecord.builder("s-1", SessionState.PASSIVE, 812).padScore(84).fps(14.8)
				.planarityResidual(0.01234).bindingSimilarity(91).flag(ReasonCode.FROZEN).build());

		String text = DiagnosticText.format(in);

		String[] lines = text.split("\n");
		assertEquals(3, lines.length);
		assertTrue(lines[0], lines[0].startsWith("DIAGNOSTIC  workflow=registration  policy=" + in.policy.mode()));
		assertTrue(lines[0], lines[0].contains("engine=READY mosip-onnx-default"));
		assertEquals("phase=CHECKING  state=PASSIVE  t=812ms  pad=84  fps=14.8  planarity=0.0123  binding=91  "
				+ "flags=FROZEN", lines[1]);
		assertEquals("models=yunet@2026may#ebafce4e", lines[2]);
	}

	@Test
	public void missingPartsAreShownAsDashes() {
		DiagnosticText.Input in = new DiagnosticText.Input();
		in.workflow = "eod_auth";
		in.engineStatus = "LOADING";

		String text = DiagnosticText.format(in);

		assertEquals("DIAGNOSTIC  workflow=eod_auth  engine=LOADING\nphase=-  state=-", text);
		assertFalse(text.contains("null"));
	}

	@Test
	public void modelVersionsComeFromTheRecordWhenTheEngineIsNotLoaded() {
		DiagnosticText.Input in = new DiagnosticText.Input();
		in.workflow = "login_auth";
		in.record = Optional.of(DiagnosticRecord.builder("s-2", SessionState.CHALLENGE_OBSERVE, 10)
				.model("minifasnet-v2", "2.7_80x80").build());

		assertTrue(DiagnosticText.format(in).endsWith("models=minifasnet-v2@2.7_80x80"));
	}
}
