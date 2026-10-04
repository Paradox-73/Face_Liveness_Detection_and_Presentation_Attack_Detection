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
import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.registration.livenessmanager.device.FaceDeviceSelection;
import io.mosip.registration.livenessmanager.engine.ModelBundleImporter;
import io.mosip.registration.livenessmanager.ui.LivenessCaptureActivity;

/**
 * Default {@link LivenessService} over the process-wide {@link LivenessRuntime}.
 */
public final class DefaultLivenessService implements LivenessService {

    private final LivenessRuntime runtime;

    /**
     * Creates the service.
     *
     * @param runtime the installed runtime
     */
    public DefaultLivenessService(LivenessRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public LivenessPolicy policy(Workflow workflow) {
        return runtime.resolve(Objects.requireNonNull(workflow, "workflow")).policy();
    }

    @Override
    public boolean isEnforced(Workflow workflow) {
        return policy(workflow).isEnabled();
    }

    @Override
    public Intent newCaptureIntent(Context context, LivenessCaptureRequest request) {
        return request.writeTo(new Intent(context, LivenessCaptureActivity.class));
    }

    @Override
    public LivenessCaptureResult parseResult(int resultCode, Intent data) {
        return LivenessCaptureResult.fromIntent(data);
    }

    @Override
    public void preload() {
        runtime.engines().preload();
    }

    @Override
    public boolean isBuiltInDeviceProvisioned() {
        return runtime.deviceSigner().isProvisioned();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code mosip.registration.liveness.android.device} through {@link FaceDeviceSelection#kind}, the same
     * rule that routes face captures: a missing, blank or unknown value means the built-in camera; only {@code SBI}
     * selects the vendor face app.
     */
    @Override
    public boolean isBuiltInCameraFaceDevice() {
        return FaceDeviceSelection.kind(runtime.config()) == FaceDeviceSelection.Kind.BUILTIN;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The user is the one signed in to ARC; the roles come from ARC's login service.
     */
    @Override
    public ModelBundleImporter.Result importModelBundle(File location) {
        String user = runtime.currentUserId();
        return runtime.modelImporter().importBundle(Objects.requireNonNull(location, "location").toPath(), user,
                runtime.rolesOf(user));
    }

    @Override
    public Optional<ModelBundleImporter.Result> currentModelBundle() {
        return runtime.modelImporter().current();
    }
}
