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
