package io.mosip.registration.service.bio.liveness;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Workflow;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.UserDTO;
import io.mosip.registration.service.login.LoginService;

/**
 * Applies {@code on_max_failure=BLOCK_AND_LOCK} to an operator or supervisor account: a
 * face authentication whose liveness attempts are used up counts as one failed login under the client's existing
 * rule {@code mosip.registration.invalid_login_count} / {@code invalid_login_time}, through
 * {@link LoginService#validateInvalidLogin}. When the count is reached the account is locked for the lock time, as
 * after failed password logins; the login screen then refuses the user until the time has passed.
 *
 * <p>The login screen already applies the rule for {@code login_auth}; this class is for the authentications
 * inside a session ({@code packet_auth}, {@code exception_auth}, {@code eod_auth}).
 *
 * <p>Thread-safe as far as {@link LoginService} is.
 */
@Component
public class LivenessAccountLock {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessAccountLock.class);
	/** Reason text stored by the login service; it is not shown by this class. */
	static final String REASON = "face liveness attempts used up";

	/** What the lock did. */
	public enum Result {
		/** The failure was counted; the account is not locked yet. */
		COUNTED,
		/** The count was reached: the account is locked for the lock time. */
		LOCKED,
		/** Nothing was done: not an authentication workflow, or the user is unknown. */
		NOT_APPLIED
	}

	private final LoginService loginService;
	private final LivenessConfig config;

	/**
	 * Creates the lock.
	 *
	 * @param loginService the client's login service (user details and the invalid-login rule)
	 * @param config       liveness configuration (invalid login count and time)
	 */
	@Autowired
	public LivenessAccountLock(LoginService loginService, LivenessConfig config) {
		this.loginService = Objects.requireNonNull(loginService, "loginService");
		this.config = Objects.requireNonNull(config, "config");
	}

	/**
	 * Counts a used-up liveness transaction as a failed login of the authenticated user.
	 *
	 * @param workflow the authentication workflow
	 * @param userId   the user whose face was being verified
	 * @return what was applied
	 */
	public Result apply(Workflow workflow, String userId) {
		if (workflow == null || !workflow.isAuth() || userId == null || userId.trim().isEmpty()) {
			return Result.NOT_APPLIED;
		}
		UserDTO user;
		try {
			user = loginService.getUserDetail(userId);
		} catch (RuntimeException e) {
			user = null;
		}
		if (user == null) {
			LOGGER.info("Liveness lock not applied for {}: unknown user", workflow.id());
			return Result.NOT_APPLIED;
		}
		String outcome = loginService.validateInvalidLogin(user, REASON, config.invalidLoginCount(),
				config.invalidLoginMinutes());
		Result result = RegistrationConstants.ERROR.equalsIgnoreCase(outcome) ? Result.LOCKED : Result.COUNTED;
		LOGGER.info("Liveness BLOCK_AND_LOCK for {}: failed login {} (limit {})", workflow.id(), result,
				config.invalidLoginCount());
		return result;
	}
}
