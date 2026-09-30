package io.mosip.registration_client.liveness;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.registration.clientmanager.constant.Modality;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.dto.registration.RegistrationDto;
import io.mosip.registration.clientmanager.service.Biometrics095Service;
import io.mosip.registration.clientmanager.spi.RegistrationService;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;

/**
 * Keeps the face with which the operator authenticated the packet ({@code packet_auth}) for the packet: the signed
 * capture goes through ARC's usual SBI response checks (signature, DEVICE trust, timestamp, transaction ID), gets
 * its {@code LIVENESS_*} evidence and is stored as the registration's officer biometrics, which
 * {@code RegistrationServiceImpl} writes as {@code officer_bio_cbeff} like the desktop client.
 */
@Singleton
public class OperatorFaceEvidence {

    private static final String TAG = "Liveness";

    private final RegistrationService registrationService;
    private final Biometrics095Service biometricsService;

    /**
     * Creates the component.
     *
     * @param registrationService the registration being created
     * @param biometricsService   ARC's SBI response checks
     */
    @Inject
    public OperatorFaceEvidence(RegistrationService registrationService, Biometrics095Service biometricsService) {
        this.registrationService = registrationService;
        this.biometricsService = biometricsService;
    }

    /**
     * Stores the operator's face of a successful packet authentication. A capture that fails the SBI checks is
     * not stored (the authentication itself stays valid); the packet then carries no officer face.
     *
     * @param capture the passing capture of the authentication
     */
    public void keep(LivenessCaptureResult capture) {
        if (capture == null || capture.rCaptureResponse() == null) {
            return;
        }
        try {
            RegistrationDto registration = registrationService.getRegistrationDto();
            List<BiometricsDto> faces = biometricsService.handleRCaptureResponse(Modality.FACE,
                    new ByteArrayInputStream(capture.rCaptureResponse().getBytes(StandardCharsets.UTF_8)),
                    Collections.emptyList(), capture.transactionId(), true);
            for (BiometricsDto face : faces) {
                face.setLivenessEvidence(new HashMap<>(capture.evidence()));
                // The built-in device signs bioSubType "" for FACE; ARC's face records use null.
                if (face.getBioSubType() != null && face.getBioSubType().isEmpty()) {
                    face.setBioSubType(null);
                }
            }
            registration.getOfficerBiometrics().clear();
            registration.getOfficerBiometrics().addAll(faces);
            Log.i(TAG, "Operator face kept for the packet: " + faces.size() + " record(s)");
        } catch (Exception e) {
            Log.w(TAG, "Operator face not kept for the packet: " + e.getClass().getSimpleName());
        }
    }
}
