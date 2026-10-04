/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Passive presentation attack detection with the MiniFASNet ensemble:
 * {@link io.mosip.liveness.onnx.pad.MiniFasPassivePad} averages MiniFASNetV2 (crop scale 2.7) and MiniFASNetV1SE
 * (crop scale 4.0), each run by {@link io.mosip.liveness.onnx.pad.MiniFasModel} with the upstream
 * preprocessing (BGR, raw 0-255, 80x80, softmax, class 1 = live).
 */
package io.mosip.liveness.onnx.pad;
