/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import io.mosip.registration.enums.Role;

/**
 * A supervisor who authenticated to approve a liveness override: the user ID entered at the override, the roles of
 * that user on this machine and the time the authentication succeeded.
 *
 * <p>The liveness window creates it after the supervisor passed the client's supervisor authentication (the face
 * authentication of the reviewer and end-of-day approvals, with face liveness under the {@code exception_auth}
 * policy). {@link LivenessRun.OverrideRequest#grant(SupervisorApproval)} refuses it when the user lacks the
 * supervisor role, is the logged-in operator, or authenticated longer ago than the override validity.
 */
public final class SupervisorApproval {

	private final String supervisorId;
	private final Set<String> roles;
	private final Instant authenticatedAt;

	private SupervisorApproval(String supervisorId, Set<String> roles, Instant authenticatedAt) {
		this.supervisorId = supervisorId;
		this.roles = roles;
		this.authenticatedAt = authenticatedAt;
	}

	/**
	 * Creates an approval.
	 *
	 * @param supervisorId    user ID of the authenticated supervisor
	 * @param roles           role codes of that user on this machine; {@code null} means none
	 * @param authenticatedAt when the supervisor's authentication succeeded
	 * @return the approval
	 * @throws IllegalArgumentException if the user ID is blank
	 */
	public static SupervisorApproval of(String supervisorId, Collection<String> roles, Instant authenticatedAt) {
		if (supervisorId == null || supervisorId.trim().isEmpty()) {
			throw new IllegalArgumentException("supervisorId is required");
		}
		Set<String> copy = roles == null ? Collections.<String>emptySet()
				: Collections.unmodifiableSet(new LinkedHashSet<>(roles));
		return new SupervisorApproval(supervisorId.trim(), copy, Objects.requireNonNull(authenticatedAt,
				"authenticatedAt"));
	}

	/**
	 * Returns the supervisor's user ID.
	 *
	 * @return the user ID, trimmed
	 */
	public String supervisorId() {
		return supervisorId;
	}

	/**
	 * Tells whether the user has the {@code REGISTRATION_SUPERVISOR} role.
	 *
	 * @return {@code true} for a supervisor
	 */
	public boolean hasSupervisorRole() {
		return roles.contains(Role.REGISTRATION_SUPERVISOR.name());
	}

	/**
	 * Returns when the supervisor's authentication succeeded.
	 *
	 * @return the time
	 */
	public Instant authenticatedAt() {
		return authenticatedAt;
	}

	@Override
	public String toString() {
		return "SupervisorApproval[supervisor=" + hasSupervisorRole() + ", authenticatedAt=" + authenticatedAt + "]";
	}
}
