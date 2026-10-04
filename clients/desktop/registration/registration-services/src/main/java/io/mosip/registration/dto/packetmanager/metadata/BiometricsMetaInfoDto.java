package io.mosip.registration.dto.packetmanager.metadata;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.mosip.liveness.core.api.PacketEvidenceKeys;
import lombok.Data;

@Data
public class BiometricsMetaInfoDto {

	private int numRetry;
	private boolean forceCaptured;
	private String BIRIndex;
	/** Liveness decision of a face capture (PASS or OVERRIDE); left out of the JSON for other captures. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String livenessDecision;
	/** Liveness method of a face capture; left out of the JSON for other captures. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String livenessMethod;

	public BiometricsMetaInfoDto(int numRetry, boolean forceCaptured, String bIRIndex) {
		super();
		this.numRetry = numRetry;
		this.forceCaptured = forceCaptured;
		BIRIndex = bIRIndex;
	}

	/**
	 * Creates the meta info of a capture that may carry liveness evidence.
	 *
	 * @param numRetry         number of retries
	 * @param forceCaptured    whether the capture was forced
	 * @param bIRIndex         index of the BIR
	 * @param livenessEvidence the LIVENESS_* evidence of a face capture, or {@code null}
	 */
	public BiometricsMetaInfoDto(int numRetry, boolean forceCaptured, String bIRIndex,
			Map<String, String> livenessEvidence) {
		this(numRetry, forceCaptured, bIRIndex);
		if (livenessEvidence != null) {
			this.livenessDecision = livenessEvidence.get(PacketEvidenceKeys.DECISION);
			this.livenessMethod = livenessEvidence.get(PacketEvidenceKeys.METHOD);
		}
	}

}
