/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Face image quality: {@link io.mosip.liveness.onnx.quality.FaceQualityMeter} measures brightness and sharpness
 * (variance of the Laplacian) on a fixed-size grey crop of the face, plus the inter-eye distance.
 */
package io.mosip.liveness.onnx.quality;
