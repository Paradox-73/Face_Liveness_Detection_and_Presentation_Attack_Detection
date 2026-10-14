/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Pure-Java image handling for the model adapters: {@link io.mosip.liveness.onnx.image.Rgb8Image} (3-byte
 * working image), {@link io.mosip.liveness.onnx.image.ImageOps} (bilinear resize, affine warp,
 * tensor normalisation), {@link io.mosip.liveness.onnx.image.AffineTransform} (including the
 * five-point similarity estimate) and {@link io.mosip.liveness.onnx.image.TensorFormat} (the pinned
 * preprocessing of each model). No {@code java.awt} or Android classes.
 */
package io.mosip.liveness.onnx.image;
