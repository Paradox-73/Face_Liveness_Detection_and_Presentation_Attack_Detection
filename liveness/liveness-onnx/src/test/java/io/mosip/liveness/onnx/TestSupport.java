/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.onnx.detect.YuNetFaceDetector;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Shared test fixtures: the repository model bundle, loaded model sets and the test face images. ImageIO is used
 * here only, in test code, to decode JPEG and PNG files.
 */
public final class TestSupport {

    private static OnnxModelSet defaultModels;
    private static OnnxModelSet nativeResolutionModels;

    private TestSupport() {
    }

    /**
     * Returns the repository model bundle directory ({@code models/}).
     *
     * @return the directory
     */
    public static File modelsDir() {
        return new File(System.getProperty("liveness.models.dir", "../../models"));
    }

    /**
     * Opens the repository bundle with the built-in development trust anchor.
     *
     * @return the verified registry
     * @throws EngineException if the bundle does not verify
     */
    public static ModelRegistry registry() throws EngineException {
        return ModelRegistry.open(ModelSources.directory(modelsDir()));
    }

    /**
     * Returns every model loaded with default settings (detector input 320 px), shared by all tests of the run.
     *
     * @return the model set
     * @throws EngineException if loading fails
     */
    public static synchronized OnnxModelSet defaultModels() throws EngineException {
        if (defaultModels == null) {
            defaultModels = OnnxModelSet.builder(registry()).runtimeOptions(OnnxRuntimeOptions.defaults()).load();
        }
        return defaultModels;
    }

    /**
     * Returns every model with the detector at full image resolution, as in the Python reference that produced
     * the golden values.
     *
     * @return the model set
     * @throws EngineException if loading fails
     */
    public static synchronized OnnxModelSet nativeResolutionModels() throws EngineException {
        if (nativeResolutionModels == null) {
            nativeResolutionModels = OnnxModelSet.builder(registry())
                    .runtimeOptions(OnnxRuntimeOptions.builder().intraOpThreads(1).build())
                    .detectorOptions(YuNetFaceDetector.Options.of(0.7, 0.3, 0, 10)).load();
        }
        return nativeResolutionModels;
    }

    /**
     * Decodes a test face image into a BGR888 buffer.
     *
     * @param name file name under {@code src/test/resources/faces}
     * @return the image
     */
    public static ImageBuffer face(String name) {
        File dir = new File(System.getProperty("liveness.test.faces.dir", "src/test/resources/faces"));
        try {
            BufferedImage img = ImageIO.read(new File(dir, name));
            if (img == null) {
                throw new IOException("cannot decode " + name);
            }
            return toBgr(img);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Decodes JPEG or PNG bytes into a BGR888 buffer.
     *
     * @param encoded file bytes
     * @return the image
     */
    public static ImageBuffer decode(byte[] encoded) {
        try {
            return toBgr(ImageIO.read(new ByteArrayInputStream(encoded)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Converts a buffered image to a BGR888 buffer.
     *
     * @param img the image
     * @return the image buffer
     */
    public static ImageBuffer toBgr(BufferedImage img) {
        BufferedImage bgr = img;
        if (img.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            bgr = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            bgr.getGraphics().drawImage(img, 0, 0, null);
        }
        byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        return ImageBuffer.ofBytes(bgr.getWidth(), bgr.getHeight(), PixelFormat.BGR888, data.clone());
    }
}
