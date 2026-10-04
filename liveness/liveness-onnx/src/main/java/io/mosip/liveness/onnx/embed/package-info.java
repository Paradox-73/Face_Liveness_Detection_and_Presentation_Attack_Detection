/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Face embeddings for the same-person check and stream-to-capture binding:
 * {@link io.mosip.liveness.onnx.embed.SFaceEmbedder} (SFace, 128 values, unit length) on faces aligned by
 * {@link io.mosip.liveness.onnx.embed.FaceAligner} to the OpenCV 112x112 five-point template.
 */
package io.mosip.liveness.onnx.embed;
