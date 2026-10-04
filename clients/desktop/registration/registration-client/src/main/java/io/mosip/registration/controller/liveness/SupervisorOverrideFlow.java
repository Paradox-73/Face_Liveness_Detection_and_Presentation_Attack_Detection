/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.enums.Role;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessRun;
import io.mosip.registration.service.bio.liveness.OverrideRefusedException;
import io.mosip.registration.service.bio.liveness.SupervisorApproval;

/**
 * The steps of a supervisor override in the liveness window, without the window: who may approve, and what the
 * window shows after the supervisor's authentication.
 *
 * <ol>
 *   <li>The Supervisor override button starts a {@link LivenessRun.OverrideRequest}.</li>
 *   <li>{@link #checkCandidate(String)}: the user ID entered must belong to a supervisor of this machine who is not
 *       the logged-in operator. Refused before the camera starts.</li>
 *   <li>The supervisor authenticates with the client's supervisor face authentication (face liveness under the
 *       {@code exception_auth} policy, then the 1:1 match).</li>
 *   <li>{@link #authenticated(String, boolean)} builds the {@link SupervisorApproval} with the time of the match and
 *       asks the request to grant the override. The service checks the same rules again and audits the result.</li>
 * </ol>
 */
final class SupervisorOverrideFlow {

	/** Grants an override to an approval ({@link LivenessRun.OverrideRequest#grant}). */
	@FunctionalInterface
	interface Grant {
		LivenessContext grant(SupervisorApproval approval);
	}

	/** Reads the roles of a user registered on this machine. */
	@FunctionalInterface
	interface UserDirectory {

		/**
		 * Returns the active roles of a user.
		 *
		 * @param userId the user ID
		 * @return the role codes; empty when the user is unknown or inactive on this machine
		 */
		Optional<Set<String>> rolesOf(String userId);
	}

	/** What the window does after a step. */
	static final class Step {
		private final LivenessContext context;
		private final String messageKey;

		private Step(LivenessContext context, String messageKey) {
			this.context = context;
			this.messageKey = messageKey;
		}

		/**
		 * Tells whether the override was granted.
		 *
		 * @return {@code true} when {@link #context()} is set
		 */
		boolean isGranted() {
			return context != null;
		}

		/**
		 * Returns the override pass for the capture request.
		 *
		 * @return the context; {@code null} when refused
		 */
		LivenessContext context() {
			return context;
		}

		/**
		 * Returns the label key of the refusal text.
		 *
		 * @return the key; {@code null} when granted
		 */
		String messageKey() {
			return messageKey;
		}
	}

	private final Grant grant;
	private final Supplier<String> operatorId;
	private final UserDirectory users;
	private final Supplier<Instant> clock;

	/**
	 * Creates the flow of one override request.
	 *
	 * @param grant      the override request's grant
	 * @param operatorId the logged-in operator's user ID
	 * @param users      roles of the users of this machine
	 * @param clock      current time
	 */
	SupervisorOverrideFlow(Grant grant, Supplier<String> operatorId, UserDirectory users, Supplier<Instant> clock) {
		this.grant = Objects.requireNonNull(grant, "grant");
		this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
		this.users = Objects.requireNonNull(users, "users");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Checks the user ID entered for the override before the supervisor's face is captured.
	 *
	 * @param userId the user ID entered
	 * @return the label key of the refusal; empty when the supervisor may authenticate
	 */
	Optional<String> checkCandidate(String userId) {
		String id = userId == null ? "" : userId.trim();
		if (id.isEmpty()) {
			return Optional.of(LivenessTexts.OVERRIDE_PROMPT);
		}
		String operator = operatorId.get();
		if (operator == null || operator.trim().isEmpty()) {
			return Optional.of(LivenessTexts.OVERRIDE_NOT_ALLOWED);
		}
		if (operator.trim().equalsIgnoreCase(id)) {
			return Optional.of(LivenessTexts.OVERRIDE_SAME_USER);
		}
		if (!rolesOf(id).contains(Role.REGISTRATION_SUPERVISOR.name())) {
			return Optional.of(MessageKeys.ERROR_NOT_SUPERVISOR);
		}
		return Optional.empty();
	}

	/**
	 * Finishes the override after the supervisor's face authentication.
	 *
	 * @param userId  the supervisor's user ID
	 * @param matched whether the authentication succeeded (liveness passed and the face matched)
	 * @return the granted pass, or the refusal text
	 */
	Step authenticated(String userId, boolean matched) {
		if (!matched) {
			return new Step(null, LivenessTexts.OVERRIDE_AUTH_FAILED);
		}
		Optional<String> refusal = checkCandidate(userId);
		if (refusal.isPresent()) {
			return new Step(null, refusal.get());
		}
		String id = userId.trim();
		try {
			return new Step(grant.grant(SupervisorApproval.of(id, rolesOf(id), clock.get())), null);
		} catch (OverrideRefusedException e) {
			return new Step(null, messageKey(e.reason()));
		} catch (IllegalStateException | IllegalArgumentException e) {
			return new Step(null, LivenessTexts.OVERRIDE_NOT_ALLOWED);
		}
	}

	/**
	 * Returns the label key that explains a refusal.
	 *
	 * @param reason the refusal reason
	 * @return the label key
	 */
	static String messageKey(OverrideRefusedException.Reason reason) {
		switch (reason) {
		case NOT_SUPERVISOR:
			return MessageKeys.ERROR_NOT_SUPERVISOR;
		case SAME_USER:
			return LivenessTexts.OVERRIDE_SAME_USER;
		case AUTH_EXPIRED:
			return LivenessTexts.OVERRIDE_AUTH_EXPIRED;
		default:
			return LivenessTexts.OVERRIDE_NOT_ALLOWED;
		}
	}

	private Set<String> rolesOf(String userId) {
		try {
			return users.rolesOf(userId).orElse(Collections.<String>emptySet());
		} catch (RuntimeException e) {
			return Collections.emptySet();
		}
	}
}
