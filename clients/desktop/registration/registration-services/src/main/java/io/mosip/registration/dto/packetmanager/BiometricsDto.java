package io.mosip.registration.dto.packetmanager;

import java.util.Map;

import io.mosip.commons.packet.constants.Biometric;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BiometricsDto {
	
	private byte[] attributeISO;
	private String bioAttribute;
	private String bioSubType;
	private String modalityName;
	private double qualityScore;
	private boolean isForceCaptured;
	private int numOfRetries;	
	private boolean isCaptured;
	private String subType;
	private double sdkScore;
	private String payLoad;
	private String signature;
	private String specVersion;

	/** Liveness evidence for the BIR others map (LIVENESS_* keys); set by the liveness gate on face captures. */
	private Map<String, String> livenessEvidence;
	/** Workflow the liveness gate checked this face capture for; {@code null} when no gate applied. */
	private String livenessWorkflow;
	

		
	public BiometricsDto(String bioAttribute, byte[] attributeISO, double qualityScore) {
		this.bioAttribute = bioAttribute;
		this.attributeISO = attributeISO;
		this.qualityScore = qualityScore;
		this.modalityName = Biometric.getModalityNameByAttribute(bioAttribute);
	}
}
