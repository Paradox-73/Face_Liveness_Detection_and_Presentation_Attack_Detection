/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.util.Objects;

import io.mosip.registration.exception.RegistrationExceptionConstants;

/**
 * A supervisor override was refused because of who approved it. The refusal is audited ({@code REG-EVT-LV16} with
 * decision {@code REFUSED}); the liveness window shows the text of {@link #reason()}.
 */
public final class OverrideRefusedException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	/** Why the approval was refused. */
	public enum Reason {
		/** The authenticated user does not have the supervisor role. */
		NOT_SUPERVISOR,
		/** The authenticated supervisor is the logged-in operator: an override needs a second person. */
		SAME_USER,
		/** The supervisor authenticated longer ago than the override validity. */
		AUTH_EXPIRED,
		/** No operator is logged in, so a second person cannot be shown. */
		NO_OPERATOR
	}

	private final Reason reason;

	/**
	 * Creates the exception.
	 *
	 * @param reason why the approval was refused
	 */
	public OverrideRefusedException(Reason reason) {
		super(RegistrationExceptionConstants.LIVENESS_OVERRIDE_NOT_ALLOWED.getErrorMessage() + " ("
				+ Objects.requireNonNull(reason, "reason") + ")");
		this.reason = reason;
	}

	/**
	 * Returns why the approval was refused.
	 *
	 * @return the reason
	 */
	public Reason reason() {
		return reason;
	}
}
