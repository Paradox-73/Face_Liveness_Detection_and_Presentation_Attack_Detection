/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
     * Stores the operator's face of a successful packet authentication. A capture without a signed device response
     * (a vendor SBI app) has nothing to store and is accepted as before.
     *
     * @param capture the passing capture of the authentication
     * @return {@code false} when the face could not be stored (failed SBI checks, no open registration): the caller
     *         fails the authentication, so no packet is written without the officer face
     */
    public boolean keep(LivenessCaptureResult capture) {
        if (capture == null || capture.rCaptureResponse() == null) {
            return true;
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
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Operator face not kept for the packet: " + e.getClass().getSimpleName());
            return false;
        }
    }
}
