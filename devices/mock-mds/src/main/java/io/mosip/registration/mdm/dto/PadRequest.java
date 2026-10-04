/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.mdm.dto;

import lombok.Data;

/**
 * Body of {@code POST /admin/pad}: controls the padCapability advertised by
 * the mock face device (MOSIP issue android-registration-client#1117) and the
 * PAD decision it echoes in RCAPTURE. Null fields keep their current value.
 */
@Data
public class PadRequest {
	/** Device type: Face or Biometric Device; optional. */
	private String type;

	/** Advertise padCapability in the signed DeviceInfo. */
	private Boolean padCapability;

	/** Also advertise padCapability in discovery (stock clients reject this). */
	private Boolean discovery;

	/** Echoed decision: NOT_EVALUATED, NO_ATTACK or ATTACK. */
	private String decision;
}
