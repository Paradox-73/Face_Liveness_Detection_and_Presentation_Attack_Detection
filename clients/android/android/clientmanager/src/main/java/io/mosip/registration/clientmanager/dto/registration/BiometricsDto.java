package io.mosip.registration.clientmanager.dto.registration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class BiometricsDto {

    private String modality;
    private String bioSubType;
    private String bioValue;
    private String specVersion;
    private boolean isException;

    public String getDecodedBioResponse() {
        return decodedBioResponse;
    }

    private String decodedBioResponse;
    private String signature;
    private boolean isForceCaptured;
    private int numOfRetries;
    private double sdkScore;

    public float getQualityScore() {
        return qualityScore;
    }

    private float qualityScore;

    /**
     * Face liveness evidence of the capture (the LIVENESS_* keys of the CBEFF BIR "others"), or null when the
     * capture did not go through liveness.
     */
    private Map<String, String> livenessEvidence;

    /**
     * Creates a capture without liveness evidence (the constructor the rest of the client uses).
     */
    public BiometricsDto(String modality, String bioSubType, String bioValue, String specVersion, boolean isException,
                         String decodedBioResponse, String signature, boolean isForceCaptured, int numOfRetries,
                         double sdkScore, float qualityScore) {
        this(modality, bioSubType, bioValue, specVersion, isException, decodedBioResponse, signature, isForceCaptured,
                numOfRetries, sdkScore, qualityScore, null);
    }

    public void setIsException(boolean exception) {
        isException = exception;
    }

    public void setIsForceCaptured(boolean forceCaptured) {
        isForceCaptured = forceCaptured;
    }


    public String getBioSubType() {
        return  bioSubType;
    }
    public String getBioValue(){
        return bioValue;
    }

    public String getModality(){return  modality;}
}
