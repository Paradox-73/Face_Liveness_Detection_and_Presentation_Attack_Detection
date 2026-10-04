/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.mdm.dto;

import lombok.Data;

/**
 * Body of {@code POST /admin/certification}: switches the mock face device
 * between certification levels L0 and L1.
 */
@Data
public class CertificationRequest {
	/** Device type: Face or Biometric Device; optional. */
	private String type;

	/** L0 or L1. */
	private String certification;
}
