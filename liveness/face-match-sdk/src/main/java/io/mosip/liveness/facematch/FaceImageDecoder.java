/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;

import java.util.Objects;

/**
 * Platform decoder of an encoded still image (JPEG, JPEG 2000 or PNG, identified from its leading bytes) into
 * upright pixels. The SDK takes the image out of the ISO/IEC 19794-5 record itself, so an implementation only
 * decodes the image.
 *
 * <p>Desktop: {@link io.mosip.liveness.facematch.imageio.ImageIoFaceImageDecoder} (the default; JPEG 2000 through
 * the jai-imageio reader the Registration Client already ships). Android: wrap the client's liveness
 * {@link ImageDecoder} ({@code BitmapFactory} plus its JPEG 2000 decoder) with {@link #of(ImageDecoder)} in a
 * class with a public no-argument constructor, and name that class in the SDK's {@code decoder} parameter.
 *
 * <p>Implementations must be thread-safe and must not log or store image data.
 */
@FunctionalInterface
public interface FaceImageDecoder {

    /**
     * Decodes an image.
     *
     * @param encoded image file bytes; not modified
     * @return the decoded image, preferably {@code BGR888} or {@code RGB888}
     * @throws ImageDecodeException if the data is corrupt or the format is not supported on this platform
     */
    ImageBuffer decode(byte[] encoded) throws ImageDecodeException;

    /**
     * Adapts a liveness {@link ImageDecoder}, so a client can reuse the decoder it already has for liveness.
     *
     * @param decoder the client's decoder
     * @return a face image decoder calling {@link ImageDecoder#decode(byte[])}
     */
    static FaceImageDecoder of(ImageDecoder decoder) {
        Objects.requireNonNull(decoder, "decoder");
        return encoded -> decoder.decode(encoded);
    }
}
