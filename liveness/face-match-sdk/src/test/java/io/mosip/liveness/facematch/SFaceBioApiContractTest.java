package io.mosip.liveness.facematch;

import io.mosip.kernel.biometrics.constant.BiometricFunction;
import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.constant.Match;
import io.mosip.kernel.biometrics.constant.ProcessedLevelType;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.biometrics.model.Decision;
import io.mosip.kernel.biometrics.model.MatchDecision;
import io.mosip.kernel.biometrics.model.QualityCheck;
import io.mosip.kernel.biometrics.model.QualityScore;
import io.mosip.kernel.biometrics.model.Response;
import io.mosip.kernel.biometrics.model.SDKInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.mosip.liveness.facematch.SdkTestData.faceBir;
import static io.mosip.liveness.facematch.SdkTestData.modalities;
import static io.mosip.liveness.facematch.SdkTestData.record;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code IBioApiV2} semantics of {@link SFaceBioApi} on the MockMDS face records: status codes, per-modality
 * decisions, template extraction and the match threshold. Person A: registration and auth records and the stream
 * frame; person B: the exception photo.
 */
class SFaceBioApiContractTest {

    private static SFaceBioApi sdk;
    private static SDKInfo info;

    @BeforeAll
    static void init() {
        sdk = new SFaceBioApi();
        Map<String, String> params = new HashMap<>();
        params.put("classname", SFaceBioApi.class.getName());
        params.put("version", "0.9");
        params.put("args", "");
        params.put(SFaceBioApi.PARAM_THRESHOLD, "40");
        params.put(SFaceBioApi.PARAM_MODEL_DIR, SdkTestData.modelsDir().getAbsolutePath());
        info = sdk.init(params);
    }

    @AfterAll
    static void close() {
        sdk.close();
    }

    @Test
    void initDescribesAFaceOnlySdkOfApiVersion09() {
        assertEquals("0.9", info.getApiVersion());
        assertEquals(SFaceBioApi.SDK_VERSION, info.getSdkVersion());
        assertEquals(Collections.singletonList(BiometricType.FACE), info.getSupportedModalities());
        assertEquals(3, info.getSupportedMethods().size());
        for (BiometricFunction f : new BiometricFunction[] {BiometricFunction.MATCH, BiometricFunction.QUALITY_CHECK,
                BiometricFunction.EXTRACT}) {
            assertEquals(Collections.singletonList(BiometricType.FACE), info.getSupportedMethods().get(f), f.name());
        }
        assertFalse(info.getSupportedMethods().containsKey(BiometricFunction.SEGMENT));
        assertEquals("sface@2021dec-fp16w", info.getOtherInfo().get("model"));
        assertEquals("40", info.getOtherInfo().get("threshold"));
        assertTrue(info.getOtherInfo().containsKey("model.yunet"));
        assertTrue(info.getOtherInfo().containsKey("model.sface"));
        assertFalse(info.getOtherInfo().containsKey("model.facemesh"), "only YuNet and SFace are loaded");
    }

    @Test
    void sameRecordMatchesItselfWithAHighScore() {
        BiometricRecord a = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        Decision d = faceDecision(sdk.match(a, new BiometricRecord[] {a}, modalities(BiometricType.FACE), null), 0);
        assertEquals(Match.MATCHED, d.getMatch());
        assertTrue(score(d) >= 99, "self-match score " + score(d));
        assertEquals("40", d.getAnalyticsInfo().get("threshold"));
        assertEquals("sface@2021dec-fp16w", d.getAnalyticsInfo().get("model"));
    }

    @Test
    void samePersonMatchesAndADifferentPersonDoesNot() {
        // Probe: the MockMDS stream frame (JPEG, another photo of person A).
        BiometricRecord probe = record(faceBir(SdkTestData.streamFrameJpeg(), ProcessedLevelType.RAW));
        BiometricRecord[] gallery = {
            record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW)),
            record(faceBir(SdkTestData.exceptionPhotoIso(), ProcessedLevelType.RAW)),
            record(faceBir(SdkTestData.authFaceIso(), ProcessedLevelType.RAW))
        };
        Response<MatchDecision[]> r = sdk.match(probe, gallery, modalities(BiometricType.FACE), null);
        assertEquals(200, r.getStatusCode());
        assertEquals(3, r.getResponse().length);
        for (int i = 0; i < 3; i++) {
            assertEquals(i, r.getResponse()[i].getGalleryIndex());
        }
        Decision samePerson = faceDecision(r, 0);
        Decision otherPerson = faceDecision(r, 1);
        Decision authRecord = faceDecision(r, 2);
        System.out.println("[sdk] stream frame vs registration record " + score(samePerson)
                + ", vs exception photo (person B) " + score(otherPerson) + ", vs auth record " + score(authRecord));
        assertEquals(Match.MATCHED, samePerson.getMatch(), "score " + score(samePerson));
        assertEquals(Match.NOT_MATCHED, otherPerson.getMatch(), "score " + score(otherPerson));
        assertTrue(score(otherPerson) < 20, "different person score " + score(otherPerson));
        assertTrue(score(samePerson) - score(otherPerson) >= 30);
        assertEquals(Match.MATCHED, authRecord.getMatch(), "score " + score(authRecord));
    }

    @Test
    void lossyAndLosslessCopiesOfOnePhotoScoreAsTheSame() {
        // The auth record holds the registration photo again, compressed lossy instead of lossless.
        BiometricRecord auth = record(faceBir(SdkTestData.authFaceIso(), ProcessedLevelType.RAW));
        BiometricRecord registration = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        Decision d = faceDecision(sdk.match(auth, new BiometricRecord[] {registration}, null, null), 0);
        assertTrue(score(d) >= 95, "score " + score(d));
    }

    @Test
    void theThresholdDecides() {
        SFaceBioApi tuned = new SFaceBioApi();
        try {
            Map<String, String> params = new HashMap<>();
            params.put(SFaceBioApi.PARAM_MODEL_DIR, SdkTestData.modelsDir().getAbsolutePath());
            BiometricRecord probe = record(faceBir(SdkTestData.streamFrameJpeg(), ProcessedLevelType.RAW));
            BiometricRecord[] reference = {record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW))};

            params.put(SFaceBioApi.PARAM_THRESHOLD, "0");
            tuned.init(params);
            int score = score(faceDecision(tuned.match(probe, reference, null, null), 0));
            assertTrue(score > 30 && score < 90, "a genuine pair from two photos: " + score);

            params.put(SFaceBioApi.PARAM_THRESHOLD, Integer.toString(score + 1));
            tuned.init(params);
            Decision above = faceDecision(tuned.match(probe, reference, null, null), 0);
            assertEquals(Match.NOT_MATCHED, above.getMatch());
            assertEquals(Integer.toString(score + 1), above.getAnalyticsInfo().get("threshold"));

            params.put(SFaceBioApi.PARAM_THRESHOLD, Integer.toString(score));
            tuned.init(params);
            assertEquals(Match.MATCHED, faceDecision(tuned.match(probe, reference, null, null), 0).getMatch(),
                    "a score equal to the threshold matches");
        } finally {
            tuned.close();
        }
    }

    @Test
    void extractedTemplatesMatchImagesAndEachOther() {
        BIR finger = SdkTestData.bir(BiometricType.FINGER, new byte[] {1, 2, 3}, ProcessedLevelType.RAW);
        BiometricRecord sample = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW), finger);
        Response<BiometricRecord> r = sdk.extractTemplate(sample, null, null);
        assertEquals(200, r.getStatusCode());
        BIR template = r.getResponse().getSegments().get(0);
        assertTrue(FaceTemplate.isTemplate(template.getBdb()));
        assertEquals(539, template.getBdb().length);
        assertEquals(ProcessedLevelType.PROCESSED, template.getBdbInfo().getLevel());
        assertEquals(sample.getSegments().get(0).getBdbInfo().getType(), template.getBdbInfo().getType());
        assertEquals(sample.getSegments().get(0).getBdbInfo().getFormat(), template.getBdbInfo().getFormat());
        assertEquals(ProcessedLevelType.RAW, sample.getSegments().get(0).getBdbInfo().getLevel(), "input untouched");
        assertSame(finger, r.getResponse().getSegments().get(1), "other modalities pass through");

        BiometricRecord templateRecord = record(template);
        Decision self = faceDecision(sdk.match(templateRecord, new BiometricRecord[] {templateRecord}, null, null), 0);
        assertEquals(Match.MATCHED, self.getMatch());
        assertTrue(score(self) >= 99);
        BiometricRecord auth = record(faceBir(SdkTestData.authFaceIso(), ProcessedLevelType.RAW));
        Decision mixed = faceDecision(sdk.match(auth, new BiometricRecord[] {templateRecord}, null, null), 0);
        assertEquals(Match.MATCHED, mixed.getMatch(), "image probe against a template, score " + score(mixed));
    }

    @Test
    void checkQualityScoresTheFaceAndFlagsProblems() {
        Response<QualityCheck> good = sdk.checkQuality(record(faceBir(SdkTestData.registrationFaceIso(),
                ProcessedLevelType.RAW)), modalities(BiometricType.FACE), null);
        assertEquals(200, good.getStatusCode());
        QualityScore q = good.getResponse().getScores().get(BiometricType.FACE);
        System.out.println("[sdk] registration record quality " + q.getScore() + " " + q.getErrors() + " "
                + q.getAnalyticsInfo());
        assertTrue(q.getScore() > 50 && q.getScore() <= 100, "quality " + q.getScore());
        assertEquals("1", q.getAnalyticsInfo().get("faces"));
        assertEquals(1, good.getResponse().getScores().size());

        Response<QualityCheck> blank = sdk.checkQuality(record(faceBir(SdkTestData.blankJpeg(),
                ProcessedLevelType.RAW)), null, null);
        assertEquals(200, blank.getStatusCode());
        QualityScore none = blank.getResponse().getScores().get(BiometricType.FACE);
        assertEquals(0f, none.getScore());
        assertTrue(none.getErrors().contains("NO_FACE"));

        Response<QualityCheck> garbage = sdk.checkQuality(record(faceBir(new byte[] {9, 9, 9, 9, 9},
                ProcessedLevelType.RAW)), null, null);
        assertEquals(200, garbage.getStatusCode());
        assertTrue(garbage.getResponse().getScores().get(BiometricType.FACE).getErrors().contains("UNREADABLE"));
    }

    @Test
    void otherModalitiesAreNotSupported() {
        BiometricRecord face = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        Response<MatchDecision[]> fingerOnly = sdk.match(face, new BiometricRecord[] {face},
                modalities(BiometricType.FINGER), null);
        assertEquals(401, fingerOnly.getStatusCode());
        assertNull(fingerOnly.getResponse());
        assertEquals(401, sdk.checkQuality(face, modalities(BiometricType.IRIS), null).getStatusCode());
        assertEquals(401, sdk.extractTemplate(face, modalities(BiometricType.FINGER), null).getStatusCode());

        Response<MatchDecision[]> both = sdk.match(face, new BiometricRecord[] {face},
                modalities(BiometricType.FACE, BiometricType.IRIS), null);
        assertEquals(200, both.getStatusCode());
        Map<BiometricType, Decision> decisions = both.getResponse()[0].getDecisions();
        assertEquals(Match.MATCHED, decisions.get(BiometricType.FACE).getMatch());
        assertEquals(Match.ERROR, decisions.get(BiometricType.IRIS).getMatch());
        assertEquals(Collections.singletonList("NOT_SUPPORTED: IRIS"), decisions.get(BiometricType.IRIS).getErrors());

        BiometricRecord fingerRecord = record(SdkTestData.bir(BiometricType.FINGER, new byte[] {1},
                ProcessedLevelType.RAW));
        assertEquals(402, sdk.match(fingerRecord, new BiometricRecord[] {face}, null, null).getStatusCode(),
                "a record without FACE is missing input");
    }

    @Test
    void missingAndBadInputsGetMosipStatusCodes() {
        BiometricRecord face = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        assertEquals(402, sdk.match(null, new BiometricRecord[] {face}, null, null).getStatusCode());
        assertEquals(402, sdk.match(face, null, null, null).getStatusCode());
        assertEquals(402, sdk.checkQuality(null, null, null).getStatusCode());
        assertEquals(402, sdk.extractTemplate(record(), null, null).getStatusCode());

        BiometricRecord blank = record(faceBir(SdkTestData.blankJpeg(), ProcessedLevelType.RAW));
        Response<MatchDecision[]> noFace = sdk.match(blank, new BiometricRecord[] {face}, null, null);
        assertEquals(406, noFace.getStatusCode());
        assertEquals(406, sdk.extractTemplate(blank, null, null).getStatusCode());

        BiometricRecord garbage = record(faceBir(new byte[] {1, 2, 3, 4, 5, 6}, ProcessedLevelType.RAW));
        assertEquals(401, sdk.match(garbage, new BiometricRecord[] {face}, null, null).getStatusCode());

        Response<MatchDecision[]> badGallery = sdk.match(face, new BiometricRecord[] {blank, record()}, null, null);
        assertEquals(200, badGallery.getStatusCode());
        assertEquals(Match.ERROR, faceDecision(badGallery, 0).getMatch());
        assertTrue(faceDecision(badGallery, 0).getErrors().contains("NO_FACE"));
        assertEquals(Match.ERROR, faceDecision(badGallery, 1).getMatch());
        assertEquals(Collections.singletonList("NO_FACE_IN_GALLERY_RECORD"), faceDecision(badGallery, 1).getErrors());
    }

    @Test
    void templatesOfAnotherModelAreRefused() {
        BiometricRecord face = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        byte[] foreign = FaceTemplate.encode(io.mosip.liveness.core.vision.FaceEmbedding.of("other@1",
                FaceTemplateTest.unitVector(128, 2)));
        Response<MatchDecision[]> r = sdk.match(record(faceBir(foreign, ProcessedLevelType.PROCESSED)),
                new BiometricRecord[] {face}, null, null);
        assertEquals(401, r.getStatusCode());
        assertTrue(r.getStatusMessage().contains("enrol again"), r.getStatusMessage());
    }

    @Test
    void anUninitialisedSdkFailsWithUnknownError() {
        SFaceBioApi fresh = new SFaceBioApi();
        BiometricRecord face = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        assertEquals(500, fresh.match(face, new BiometricRecord[] {face}, null, null).getStatusCode());
        assertEquals(500, fresh.checkQuality(face, null, null).getStatusCode());
        assertEquals(500, fresh.extractTemplate(face, null, null).getStatusCode());
        assertTrue(fresh.toString().contains("not initialised"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void segmentAndConvertFormatAreMinimal() {
        BiometricRecord face = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        Response<BiometricRecord> seg = sdk.segment(face, null, null);
        assertEquals(200, seg.getStatusCode());
        assertSame(face, seg.getResponse());

        assertSame(face, sdk.convertFormatV2(face, "ISO19794_5_2011", "iso19794_5_2011", null, null, null)
                .getResponse());
        assertEquals(401, sdk.convertFormatV2(face, "ISO19794_5_2011", "JPEG", null, null, null).getStatusCode());
        assertEquals(402, sdk.convertFormatV2(null, "A", "A", null, null, null).getStatusCode());
        assertSame(face, sdk.convertFormat(face, "X", "X", null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> sdk.convertFormat(face, "X", "Y", null, null, null));
    }

    @Test
    void initRejectsBadParametersAndLoadsFromTheClassPathByDefault() {
        SFaceBioApi other = new SFaceBioApi();
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> other.init(Collections.singletonMap(SFaceBioApi.PARAM_THRESHOLD, "high")));
            assertThrows(IllegalArgumentException.class,
                    () -> other.init(Collections.singletonMap(SFaceBioApi.PARAM_THRESHOLD, "101")));
            assertThrows(IllegalArgumentException.class,
                    () -> other.init(Collections.singletonMap(SFaceBioApi.PARAM_DECODER, "java.lang.String")));
            assertThrows(IllegalArgumentException.class,
                    () -> other.init(Collections.singletonMap(SFaceBioApi.PARAM_DECODER, "no.such.Decoder")));
            assertThrows(IllegalStateException.class,
                    () -> other.init(Collections.singletonMap(SFaceBioApi.PARAM_MODEL_DIR, "no/such/dir")));

            SDKInfo defaults = other.init(null);
            assertEquals("40", defaults.getOtherInfo().get("threshold"), "default threshold");
            SDKInfo changed = other.init(Collections.singletonMap(SFaceBioApi.PARAM_THRESHOLD, "55"));
            assertEquals("55", changed.getOtherInfo().get("threshold"), "a new threshold needs no reload");
            assertTrue(other.toString().contains("threshold=55.0"));
        } finally {
            other.close();
        }
    }

    @Test
    void aLivenessImageDecoderCanBeNamedAsTheDecoder() {
        SFaceBioApi other = new SFaceBioApi();
        try {
            Map<String, String> params = new HashMap<>();
            params.put(SFaceBioApi.PARAM_MODEL_DIR, SdkTestData.modelsDir().getAbsolutePath());
            params.put(SFaceBioApi.PARAM_DECODER, LivenessStyleDecoder.class.getName());
            other.init(params);
            BiometricRecord face = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
            assertEquals(Match.MATCHED, faceDecision(other.match(face, new BiometricRecord[] {face}, null, null), 0)
                    .getMatch());
            assertTrue(LivenessStyleDecoder.calls.get() > 0);
        } finally {
            other.close();
        }
    }

    @Test
    void responsesCarryNoBiometricData() {
        BiometricRecord probe = record(faceBir(SdkTestData.authFaceIso(), ProcessedLevelType.RAW));
        BiometricRecord reference = record(faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW));
        Decision d = faceDecision(sdk.match(probe, new BiometricRecord[] {reference}, null, null), 0);
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("score", "threshold", "model")),
                new java.util.TreeSet<>(d.getAnalyticsInfo().keySet()));
        for (String v : d.getAnalyticsInfo().values()) {
            assertTrue(v.length() < 32, v);
        }
        assertNotNull(r(sdk.checkQuality(probe, null, null)).getScores().get(BiometricType.FACE).getAnalyticsInfo());
        for (String key : r(sdk.checkQuality(probe, null, null)).getScores().get(BiometricType.FACE)
                .getAnalyticsInfo().keySet()) {
            assertTrue(Arrays.asList("faces", "interEyeDistance", "brightness").contains(key), key);
        }
    }

    private static <T> T r(Response<T> response) {
        assertEquals(200, response.getStatusCode(), response.getStatusMessage());
        return response.getResponse();
    }

    @Test
    void theSampleByteArraysAreNotModified() {
        byte[] iso = SdkTestData.registrationFaceIso();
        byte[] copy = iso.clone();
        BiometricRecord face = record(faceBir(iso, ProcessedLevelType.RAW));
        sdk.match(face, new BiometricRecord[] {face}, null, null);
        sdk.extractTemplate(face, null, null);
        assertArrayEquals(copy, iso);
    }

    private static Decision faceDecision(Response<MatchDecision[]> r, int index) {
        assertEquals(200, r.getStatusCode(), r.getStatusMessage());
        return r.getResponse()[index].getDecisions().get(BiometricType.FACE);
    }

    private static int score(Decision d) {
        String s = d.getAnalyticsInfo().get("score");
        return s == null ? -1 : Integer.parseInt(s);
    }

    /** A decoder written against the liveness {@code ImageDecoder} interface, as the Android client has one. */
    public static final class LivenessStyleDecoder implements io.mosip.liveness.core.api.ImageDecoder {

        static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        private final FaceImageDecoder delegate = new io.mosip.liveness.facematch.imageio.ImageIoFaceImageDecoder();

        /** Created by name. */
        public LivenessStyleDecoder() {
        }

        @Override
        public io.mosip.liveness.core.image.ImageBuffer decode(byte[] encoded)
                throws io.mosip.liveness.core.image.ImageDecodeException {
            calls.incrementAndGet();
            return delegate.decode(encoded);
        }

        @Override
        public io.mosip.liveness.core.image.ImageBuffer decode(io.mosip.liveness.core.device.FaceFrame frame)
                throws io.mosip.liveness.core.image.ImageDecodeException {
            return decode(frame.data());
        }
    }
}
