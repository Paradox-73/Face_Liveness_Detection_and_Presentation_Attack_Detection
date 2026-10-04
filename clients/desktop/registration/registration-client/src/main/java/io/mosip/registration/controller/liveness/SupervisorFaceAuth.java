package io.mosip.registration.controller.liveness;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import io.mosip.kernel.core.cbeffutil.jaxbclasses.SingleType;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Workflow;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.controller.ClientApplication;
import io.mosip.registration.dto.UserDTO;
import io.mosip.registration.dto.UserRoleDTO;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.service.bio.BioService;
import io.mosip.registration.service.bio.liveness.LivenessOrchestrator;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;
import io.mosip.registration.service.login.LoginService;
import io.mosip.registration.service.security.AuthenticationService;
import javafx.application.Platform;
import javafx.stage.Window;

/**
 * The supervisor authentication of an override: the client's supervisor face authentication, the one the reviewer
 * step of packet authentication uses. Face liveness runs first under the {@code exception_auth} policy (when that
 * workflow is enforced), then the signed face capture is matched 1:1 against the supervisor's enrolled face.
 * Unlike the reviewer step, the captured face is not added to the packet.
 */
final class SupervisorFaceAuth implements LivenessDialog.SupervisorAuthenticator {

	private static final Logger LOGGER = AppConfig.getLogger(SupervisorFaceAuth.class);

	@Override
	public void authenticate(String userId, LivenessOrchestrator orchestrator, LivenessDialog.Options parent,
			Window owner, Consumer<Boolean> done) {
		if (!orchestrator.isEnforced(Workflow.EXCEPTION_AUTH)) {
			captureAndMatchAsync(userId, orchestrator, done);
			return;
		}
		boolean[] captureStarted = { false };
		LivenessDialog.Options options = new LivenessDialog.Options(Workflow.EXCEPTION_AUTH, userId).owner(owner)
				.language(parent.languageCode(), parent.isRightToLeft());
		LivenessDialog.open(orchestrator, options, new LivenessDialog.Callbacks() {
			@Override
			public void captureReady(LivenessContext context) {
				captureStarted[0] = true;
				captureAndMatchAsync(userId, orchestrator, done);
			}

			@Override
			public void closedWithoutPass(Optional<LivenessOutcome> lastOutcome) {
				if (!captureStarted[0]) {
					done.accept(false);
				}
			}
		});
	}

	/**
	 * Returns the active roles of a user registered on this machine.
	 *
	 * @param userId the user ID
	 * @return the role codes; empty when the user is unknown or inactive
	 */
	static Optional<Set<String>> rolesOf(String userId) {
		UserDTO user = ClientApplication.getApplicationContext().getBean(LoginService.class).getUserDetail(userId);
		if (user == null || Boolean.FALSE.equals(user.getIsActive()) || user.getUserRole() == null) {
			return Optional.empty();
		}
		Set<String> roles = new LinkedHashSet<>();
		for (UserRoleDTO role : user.getUserRole()) {
			if (role != null && role.isActive() && role.getRoleCode() != null) {
				roles.add(role.getRoleCode());
			}
		}
		return Optional.of(Collections.unmodifiableSet(roles));
	}

	private static void captureAndMatchAsync(String userId, LivenessOrchestrator orchestrator,
			Consumer<Boolean> done) {
		Thread thread = new Thread(() -> {
			boolean matched;
			try {
				matched = captureAndMatch(userId, orchestrator);
			} catch (Exception e) {
				LOGGER.error("Supervisor face authentication for the override failed: {}", e.getClass().getSimpleName());
				matched = false;
			}
			boolean result = matched;
			Platform.runLater(() -> done.accept(result));
		}, "liveness-override-auth");
		thread.setDaemon(true);
		thread.start();
	}

	private static boolean captureAndMatch(String userId, LivenessOrchestrator orchestrator) throws Exception {
		MDMRequestDto request = new MDMRequestDto(RegistrationConstants.FACE_FULLFACE, null, "Registration",
				ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.SERVER_ACTIVE_PROFILE),
				ApplicationContext.getIntValueFromApplicationMap(RegistrationConstants.CAPTURE_TIME_OUT), 1,
				ApplicationContext.getIntValueFromApplicationMap(RegistrationConstants.FACE_AUTHENTICATION_THRESHOLD));
		// the pass of the supervisor's liveness session, if any; the liveness gate checks it
		request.setLivenessContext(orchestrator.contextFor(Workflow.EXCEPTION_AUTH, userId));
		org.springframework.context.ApplicationContext spring = ClientApplication.getApplicationContext();
		List<BiometricsDto> biometrics = spring.getBean(BioService.class).captureModalityForAuth(request);
		Boolean match = spring.getBean(AuthenticationService.class).authValidator(userId, SingleType.FACE.value(),
				biometrics);
		return Boolean.TRUE.equals(match);
	}
}
