/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.util.Optional;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.registration.livenessmanager.engine.ModelBundleImporter;

/**
 * Face liveness for the rest of ARC: resident face capture, operator onboarding and packet authentication, and
 * supervisor approval use this one facade (Dagger: {@code LivenessModule}).
 *
 * <p>Typical use from an activity or a Pigeon API that has one:
 * <pre>{@code
 * if (liveness.isEnforced(Workflow.REGISTRATION)) {
 *     Intent intent = liveness.newCaptureIntent(activity,
 *             LivenessCaptureRequest.builder(Workflow.REGISTRATION, rid).language("eng").build());
 *     activity.startActivityForResult(intent, LivenessService.REQUEST_CODE);
 * }
 * // in onActivityResult(requestCode == LivenessService.REQUEST_CODE):
 * LivenessCaptureResult result = liveness.parseResult(resultCode, data);
 * if (result.isPass()) {
 *     // result.rCaptureResponse() goes to Biometrics095Service.handleRCaptureResponse (unchanged SBI checks);
 *     // result.evidence() goes into the BIR "others" entries (LIVENESS_*).
 * }
 * }</pre>
 */
public interface LivenessService {

    /** Request code for {@code startActivityForResult} (design change N5: codes 1-5 are ARC's SBI calls). */
    int REQUEST_CODE = 6;

    /**
     * Resolves the policy of a workflow from the synced configuration (defaults, floors and ranges applied).
     *
     * @param workflow the workflow
     * @return the policy; mode {@code OFF} when the master switch is off
     */
    LivenessPolicy policy(Workflow workflow);

    /**
     * Tells whether a face capture in this workflow needs a liveness pass.
     *
     * @param workflow the workflow
     * @return {@code true} when the master switch is on and the workflow's policy is not {@code OFF}
     */
    boolean isEnforced(Workflow workflow);

    /**
     * Creates the intent that starts the capture screen.
     *
     * @param context the calling activity
     * @param request what to verify
     * @return the intent for {@code startActivityForResult(intent, REQUEST_CODE)}
     */
    Intent newCaptureIntent(Context context, LivenessCaptureRequest request);

    /**
     * Reads the capture screen's result. Never returns {@code null}: a missing result is a cancellation.
     *
     * @param resultCode result code of {@code onActivityResult}
     * @param data       result intent
     * @return the result
     */
    LivenessCaptureResult parseResult(int resultCode, Intent data);

    /** Starts loading and verifying the models in the background, so the first capture does not wait. */
    void preload();

    /**
     * Tells whether the built-in camera's device key has an issued DEVICE certificate (see
     * {@code AndroidKeystoreSigner}). Captures of an unprovisioned device fail ARC's SBI trust check.
     *
     * @return {@code true} if provisioned
     */
    boolean isBuiltInDeviceProvisioned();

    /**
     * Tells whether this tablet's face device is its own camera ({@code mosip.registration.liveness.android.device}
     * {@code BUILTIN}, the default) rather than a vendor SBI face app. The built-in camera then also takes the
     * exception photo.
     *
     * @return {@code true} for the built-in camera
     */
    default boolean isBuiltInCameraFaceDevice() {
        return true;
    }

    /**
     * Imports a signed model bundle for the signed-in user and switches the engine to it (secure offline model
     * update, see {@link ModelBundleImporter}). Blocks for the engine build; call it off the main thread.
     *
     * @param location folder or {@code .zip} file with {@code manifest.json}, {@code manifest.jws} and the models
     * @return the result; never throws for a bad bundle
     */
    default ModelBundleImporter.Result importModelBundle(File location) {
        return ModelBundleImporter.Result.of(ModelBundleImporter.Status.NOT_SUPPORTED);
    }

    /**
     * Describes the model bundle the engine runs or would load.
     *
     * @return name, version and whether it was imported; empty when no bundle can be read
     */
    default Optional<ModelBundleImporter.Result> currentModelBundle() {
        return Optional.empty();
    }
}
