package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.enums.Role;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.OverrideRefusedException;
import io.mosip.registration.service.bio.liveness.SupervisorApproval;

/**
 * Tests the supervisor override steps of the liveness window without opening a window: who may start the
 * supervisor's authentication, what is granted after it, and which text each refusal shows.
 */
public class SupervisorOverrideFlowTest {

	private static final Instant NOW = Instant.parse("2026-10-04T10:15:30Z");
	private static final LivenessContext GRANTED = LivenessContext.withoutPass(Workflow.REGISTRATION, "10001100010003");

	private final Map<String, Set<String>> users = new HashMap<>();
	private final List<SupervisorApproval> grants = new ArrayList<>();
	private String operator = "officer1";
	private RuntimeException grantFailure;

	private final SupervisorOverrideFlow flow = new SupervisorOverrideFlow(approval -> {
		grants.add(approval);
		if (grantFailure != null) {
			throw grantFailure;
		}
		return GRANTED;
	}, () -> operator, id -> Optional.ofNullable(users.get(id)), () -> NOW);

	public SupervisorOverrideFlowTest() {
		users.put("officer1", roles(Role.REGISTRATION_SUPERVISOR, Role.REGISTRATION_OFFICER));
		users.put("supervisor2", roles(Role.REGISTRATION_SUPERVISOR));
		users.put("officer2", roles(Role.REGISTRATION_OFFICER));
	}

	@Test
	public void loggedInOperatorCannotApproveEvenAsSupervisor() {
		assertEquals(Optional.of(LivenessTexts.OVERRIDE_SAME_USER), flow.checkCandidate("officer1"));
		assertEquals(Optional.of(LivenessTexts.OVERRIDE_SAME_USER), flow.checkCandidate(" OFFICER1 "));
		SupervisorOverrideFlow.Step step = flow.authenticated("officer1", true);
		assertFalse(step.isGranted());
		assertEquals(LivenessTexts.OVERRIDE_SAME_USER, step.messageKey());
		assertTrue("nothing reaches the grant", grants.isEmpty());
	}

	@Test
	public void userWithoutSupervisorRoleOrUnknownIsRefused() {
		assertEquals(Optional.of(MessageKeys.ERROR_NOT_SUPERVISOR), flow.checkCandidate("officer2"));
		assertEquals(Optional.of(MessageKeys.ERROR_NOT_SUPERVISOR), flow.checkCandidate("nobody"));
		assertEquals(Optional.of(LivenessTexts.OVERRIDE_PROMPT), flow.checkCandidate("  "));
		assertFalse(flow.authenticated("officer2", true).isGranted());
		assertTrue(grants.isEmpty());
	}

	@Test
	public void noLoggedInOperatorGrantsNothing() {
		operator = "";
		assertEquals(Optional.of(LivenessTexts.OVERRIDE_NOT_ALLOWED), flow.checkCandidate("supervisor2"));
	}

	@Test
	public void failedFaceAuthenticationGrantsNothing() {
		assertEquals(Optional.empty(), flow.checkCandidate("supervisor2"));
		SupervisorOverrideFlow.Step step = flow.authenticated("supervisor2", false);
		assertFalse(step.isGranted());
		assertEquals(LivenessTexts.OVERRIDE_AUTH_FAILED, step.messageKey());
		assertTrue(grants.isEmpty());
	}

	@Test
	public void otherSupervisorIsGrantedWithTheTimeOfTheMatch() {
		SupervisorOverrideFlow.Step step = flow.authenticated("supervisor2", true);
		assertTrue(step.isGranted());
		assertSame(GRANTED, step.context());
		assertEquals(1, grants.size());
		SupervisorApproval approval = grants.get(0);
		assertEquals("supervisor2", approval.supervisorId());
		assertTrue(approval.hasSupervisorRole());
		assertEquals(NOW, approval.authenticatedAt());
	}

	@Test
	public void serviceRefusalsShowTheirText() {
		grantFailure = new OverrideRefusedException(OverrideRefusedException.Reason.AUTH_EXPIRED);
		assertEquals(LivenessTexts.OVERRIDE_AUTH_EXPIRED, flow.authenticated("supervisor2", true).messageKey());
		grantFailure = new OverrideRefusedException(OverrideRefusedException.Reason.SAME_USER);
		assertEquals(LivenessTexts.OVERRIDE_SAME_USER, flow.authenticated("supervisor2", true).messageKey());
		grantFailure = new OverrideRefusedException(OverrideRefusedException.Reason.NOT_SUPERVISOR);
		assertEquals(MessageKeys.ERROR_NOT_SUPERVISOR, flow.authenticated("supervisor2", true).messageKey());
		grantFailure = new IllegalStateException("already granted");
		assertEquals(LivenessTexts.OVERRIDE_NOT_ALLOWED, flow.authenticated("supervisor2", true).messageKey());
	}

	@Test
	public void everyRefusalTextExistsInEnglishFrenchAndArabic() {
		for (String lang : Arrays.asList("eng", "fra", "ara")) {
			LivenessTexts texts = LivenessTexts.forLanguage(lang);
			for (String key : Arrays.asList(LivenessTexts.OVERRIDE_PROMPT, LivenessTexts.OVERRIDE_SUPERVISOR_ID,
					LivenessTexts.OVERRIDE_SAME_USER, LivenessTexts.OVERRIDE_AUTH_FAILED,
					LivenessTexts.OVERRIDE_AUTH_EXPIRED, LivenessTexts.OVERRIDE_NOT_ALLOWED)) {
				assertNotEquals(key + " in " + lang, key, texts.text(key));
			}
			for (OverrideRefusedException.Reason reason : OverrideRefusedException.Reason.values()) {
				String key = SupervisorOverrideFlow.messageKey(reason);
				assertNotEquals(reason + " in " + lang, key, texts.text(key));
			}
		}
	}

	private static Set<String> roles(Role... roles) {
		Set<String> codes = new HashSet<>();
		for (Role r : roles) {
			codes.add(r.name());
		}
		return codes;
	}
}
