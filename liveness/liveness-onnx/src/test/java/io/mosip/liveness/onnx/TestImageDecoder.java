/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * Test {@link ImageDecoder}: JPEG and PNG through ImageIO ({@link TestSupport#decode(byte[])}), BGR888 and RGB888
 * frames passed through unchanged. Frames are assumed upright.
 */
public final class TestImageDecoder implements ImageDecoder {

    @Override
    public ImageBuffer decode(FaceFrame frame) throws ImageDecodeException {
        PixelFormat format = frame.format();
        if (format == PixelFormat.BGR888 || format == PixelFormat.RGB888) {
            return ImageBuffer.ofBytes(frame.width(), frame.height(), format, frame.data());
        }
        return decode(frame.data());
    }

    @Override
    public ImageBuffer decode(byte[] encoded) throws ImageDecodeException {
        try {
            return TestSupport.decode(encoded);
        } catch (RuntimeException e) {
            throw new ImageDecodeException("test decoder cannot read the image", e);
        }
    }
}
