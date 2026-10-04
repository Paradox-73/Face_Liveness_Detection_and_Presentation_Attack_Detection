/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import java.util.Map;
import java.util.Objects;

/**
 * The SDK's initialisation parameters, read from the map MOSIP's loaders pass to {@code init}: every
 * {@code mosip.biometric.sdk.providers.face.<vendor>.<param>} value, keyed by {@code <param>}.
 */
final class SdkSettings {

    private final double threshold;
    private final String modelDirectory;
    private final String decoderClass;

    private SdkSettings(double threshold, String modelDirectory, String decoderClass) {
        this.threshold = threshold;
        this.modelDirectory = modelDirectory;
        this.decoderClass = decoderClass;
    }

    /**
     * Reads the parameters.
     *
     * @param params init parameters; may be {@code null}
     * @return the settings, with defaults for missing or empty values
     * @throws IllegalArgumentException if the threshold is not a number from 0 to 100
     */
    static SdkSettings from(Map<String, String> params) {
        String threshold = value(params, SFaceBioApi.PARAM_THRESHOLD);
        return new SdkSettings(threshold == null ? SFaceBioApi.DEFAULT_THRESHOLD : parseThreshold(threshold),
                value(params, SFaceBioApi.PARAM_MODEL_DIR),
                orDefault(value(params, SFaceBioApi.PARAM_DECODER), SFaceBioApi.DEFAULT_DECODER));
    }

    private static double parseThreshold(String text) {
        double t;
        try {
            t = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SFace SDK threshold is not a number: " + text, e);
        }
        if (!(t >= 0 && t <= 100)) {
            throw new IllegalArgumentException("SFace SDK threshold must be 0-100: " + text);
        }
        return t;
    }

    private static String value(Map<String, String> params, String key) {
        if (params == null) {
            return null;
        }
        String v = params.get(key);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * Returns the match threshold on the 0-100 similarity scale.
     *
     * @return the threshold
     */
    double threshold() {
        return threshold;
    }

    /**
     * Returns the model bundle directory.
     *
     * @return the directory, or {@code null} to read the bundle from the class path
     */
    String modelDirectory() {
        return modelDirectory;
    }

    /**
     * Returns the image decoder class name.
     *
     * @return a class implementing {@link FaceImageDecoder} or the liveness {@code ImageDecoder}
     */
    String decoderClass() {
        return decoderClass;
    }

    /**
     * Tells whether loading the models again is needed to change to other settings.
     *
     * @param other the new settings
     * @return {@code true} when the model directory or the decoder differ
     */
    boolean needsReload(SdkSettings other) {
        return !Objects.equals(modelDirectory, other.modelDirectory) || !decoderClass.equals(other.decoderClass);
    }

    @Override
    public String toString() {
        return "SdkSettings[threshold=" + threshold + ", models=" + (modelDirectory == null ? "class path"
                : modelDirectory) + ", decoder=" + decoderClass + "]";
    }
}
