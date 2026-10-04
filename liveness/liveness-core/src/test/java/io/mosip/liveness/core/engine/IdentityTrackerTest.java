package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceEmbedder;
import io.mosip.liveness.core.vision.FaceEmbedding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reference face of {@link IdentityTracker}: neutral frames first, else the most frontal good frames. */
class IdentityTrackerTest {

    private static final FaceBox BOX = TestFaces.neutral().build().box();

    /** Embedder that returns a fixed vector per image and records which images it embedded. */
    private static final class ImageEmbedder implements FaceEmbedder {
        final Map<ImageBuffer, float[]> vectors = new IdentityHashMap<>();
        final List<ImageBuffer> embedded = new ArrayList<>();

        @Override
        public FaceEmbedding embed(ImageBuffer image, FaceBox face) throws EngineException {
            embedded.add(image);
            return FaceEmbedding.of("sface-test", vectors.get(image));
        }
    }

    private static ImageBuffer image() {
        return ImageBuffer.ofBytes(4, 4, PixelFormat.GRAY8, new byte[16]);
    }

    @Test
    void referenceFromFrontalFramesKeepsTheMostFrontal() throws EngineException {
        ImageEmbedder embedder = new ImageEmbedder();
        IdentityTracker tracker = new IdentityTracker(embedder, 2);
        double[] angles = {18, 12, 20, 11, 15};
        List<ImageBuffer> images = new ArrayList<>();
        for (double angle : angles) {
            ImageBuffer img = image();
            images.add(img);
            embedder.vectors.put(img, angle <= 12 ? ScriptedModels.PERSON_A : ScriptedModels.PERSON_B);
            tracker.offerFallback(img, BOX, angle);
        }
        assertTrue(embedder.embedded.isEmpty(), "no embedding before the reference is completed");
        assertFalse(tracker.hasReference());

        tracker.completeReference();
        assertTrue(tracker.hasReference());
        assertEquals(2, embedder.embedded.size());
        assertTrue(embedder.embedded.contains(images.get(1)), "12 degrees");
        assertTrue(embedder.embedded.contains(images.get(3)), "11 degrees");

        ImageBuffer probe = image();
        embedder.vectors.put(probe, ScriptedModels.PERSON_A);
        assertEquals(100, tracker.similarity(probe, BOX));
    }

    @Test
    void neutralFramesWinOverFallbackFrames() throws EngineException {
        ImageEmbedder embedder = new ImageEmbedder();
        IdentityTracker tracker = new IdentityTracker(embedder, 3);
        ImageBuffer turned = image();
        embedder.vectors.put(turned, ScriptedModels.PERSON_B);
        tracker.offerFallback(turned, BOX, 14);
        ImageBuffer neutral = image();
        embedder.vectors.put(neutral, ScriptedModels.PERSON_A);
        tracker.addReference(neutral, BOX);
        tracker.offerFallback(turned, BOX, 13);

        tracker.completeReference();
        assertEquals(1, embedder.embedded.size(), "the fallback frames are dropped once a neutral frame counted");
        ImageBuffer probe = image();
        embedder.vectors.put(probe, ScriptedModels.PERSON_A);
        assertEquals(100, tracker.similarity(probe, BOX));
    }

    @Test
    void framesWithoutLandmarksNeverFormAReference() throws EngineException {
        ImageEmbedder embedder = new ImageEmbedder();
        IdentityTracker tracker = new IdentityTracker(embedder, 3);
        tracker.offerFallback(image(), TestFaces.neutral().noLandmarks().build().box(), 12);
        tracker.completeReference();
        assertFalse(tracker.hasReference());
        assertTrue(embedder.embedded.isEmpty());
        assertEquals(-1, tracker.similarity(image(), BOX));
    }

    @Test
    void withoutAnEmbedderNothingIsKept() throws EngineException {
        IdentityTracker tracker = new IdentityTracker(null, 3);
        tracker.offerFallback(image(), BOX, 12);
        tracker.completeReference();
        assertFalse(tracker.isEnabled());
        assertFalse(tracker.hasReference());
    }
}
