/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.kernel.biometrics.constant.BiometricFunction;
import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.constant.ProcessedLevelType;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.biometrics.model.MatchDecision;
import io.mosip.kernel.biometrics.model.QualityCheck;
import io.mosip.kernel.biometrics.model.Response;
import io.mosip.kernel.biometrics.model.SDKInfo;
import io.mosip.kernel.biometrics.spi.IBioApi;
import io.mosip.kernel.biosdk.provider.factory.BioAPIFactory;
import io.mosip.kernel.biosdk.provider.impl.BioProviderImpl_V_0_9;
import io.mosip.kernel.biosdk.provider.spi.iBioProviderApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.mosip.liveness.facematch.SdkTestData.faceBir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the SDK through MOSIP's own loader ({@code kernel-biosdk-provider} 1.2.0.1, the version in the desktop
 * Registration Client): {@link BioAPIFactory} bound to the {@code mosip.biometric.sdk.providers.*} keys, with this
 * SDK for FACE and another SDK kept for FINGER under the same vendor, exactly as the recommended configuration
 * does.
 */
class MosipSdkLoaderTest {

    private static BioAPIFactory factory;

    @BeforeAll
    static void loadLikeTheDesktopClient() throws Exception {
        // mosip.biometric.sdk.providers.face.mockvendor.* and ...finger.mockvendor.*
        Map<String, Map<String, String>> face = Collections.singletonMap("mockvendor",
                params(SFaceBioApi.class.getName(), "threshold", "40", "modeldir",
                        SdkTestData.modelsDir().getAbsolutePath()));
        Map<String, Map<String, String>> finger = Collections.singletonMap("mockvendor",
                params(FingerOnlySdk.class.getName(), "threshold", "60"));
        factory = new BioAPIFactory();
        factory.setFace(new HashMap<>(face));
        factory.setFinger(new HashMap<>(finger));
        factory.setIris(new HashMap<>());
        set(factory, "providerApis", Collections.<iBioProviderApi>singletonList(new BioProviderImpl_V_0_9()));
        factory.initializeBioAPIProviders();
    }

    @AfterAll
    static void release() {
        factory = null;
    }

    @Test
    void faceFunctionsGoToThisSdkAndFingerStaysWithTheOther() throws Exception {
        iBioProviderApi faceMatch = factory.getBioProvider(BiometricType.FACE, BiometricFunction.MATCH);
        assertSame(faceMatch, factory.getBioProvider(BiometricType.FACE, BiometricFunction.QUALITY_CHECK));
        assertSame(faceMatch, factory.getBioProvider(BiometricType.FACE, BiometricFunction.EXTRACT));
        iBioProviderApi fingerMatch = factory.getBioProvider(BiometricType.FINGER, BiometricFunction.MATCH);
        assertSame(faceMatch, fingerMatch, "one provider object per vendor; it routes by modality inside");
        assertFalse(fingerMatch.verify(Collections.singletonList(fingerBir()),
                Collections.singletonList(fingerBir()), BiometricType.FINGER, null), "the finger stub never matches");
        assertTrue(FingerOnlySdk.matchCalls > 0, "FINGER reached the other SDK, not SFace");
    }

    @Test
    void verifyAndIdentifyGiveTheExpectedDecisions() throws Exception {
        iBioProviderApi provider = factory.getBioProvider(BiometricType.FACE, BiometricFunction.MATCH);
        List<BIR> auth = Collections.singletonList(faceBir(SdkTestData.authFaceIso(), ProcessedLevelType.RAW));
        List<BIR> registration = Collections.singletonList(faceBir(SdkTestData.registrationFaceIso(),
                ProcessedLevelType.RAW));
        List<BIR> otherPerson = Collections.singletonList(faceBir(SdkTestData.exceptionPhotoIso(),
                ProcessedLevelType.RAW));
        assertTrue(provider.verify(auth, registration, BiometricType.FACE, null));
        assertFalse(provider.verify(auth, otherPerson, BiometricType.FACE, null));

        Map<String, List<BIR>> gallery = new HashMap<>();
        gallery.put("officer1", registration);
        gallery.put("officer2", otherPerson);
        Map<String, Boolean> result = provider.identify(auth, gallery, BiometricType.FACE, null);
        assertEquals(Boolean.TRUE, result.get("officer1"));
        assertEquals(Boolean.FALSE, result.get("officer2"));
    }

    @Test
    void qualityAndTemplatesThroughTheLoader() throws Exception {
        iBioProviderApi provider = factory.getBioProvider(BiometricType.FACE, BiometricFunction.QUALITY_CHECK);
        BIR face = faceBir(SdkTestData.registrationFaceIso(), ProcessedLevelType.RAW);
        float[] segment = provider.getSegmentQuality(new BIR[] {face}, null);
        assertEquals(1, segment.length);
        assertTrue(segment[0] > 50, "segment quality " + segment[0]);
        Map<BiometricType, Float> modality = provider.getModalityQuality(new BIR[] {face}, null);
        assertEquals(segment[0], modality.get(BiometricType.FACE), 1e-3);

        List<BIR> templates = factory.getBioProvider(BiometricType.FACE, BiometricFunction.EXTRACT)
                .extractTemplate(Collections.singletonList(face), null);
        assertEquals(1, templates.size());
        assertTrue(FaceTemplate.isTemplate(templates.get(0).getBdb()));
        assertNotSame(face, templates.get(0));
        List<BIR> probe = Collections.singletonList(faceBir(SdkTestData.authFaceIso(), ProcessedLevelType.RAW));
        assertTrue(provider.verify(probe, templates, BiometricType.FACE, null), "stored template matches a new image");
    }

    private static BIR fingerBir() {
        return SdkTestData.bir(BiometricType.FINGER, new byte[] {1, 2, 3}, ProcessedLevelType.RAW);
    }

    private static Map<String, String> params(String classname, String... extra) {
        Map<String, String> p = new HashMap<>();
        p.put("classname", classname);
        p.put("version", "0.9");
        p.put("args", "");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            p.put(extra[i], extra[i + 1]);
        }
        return p;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Stands in for MOSIP's mock SDK on FINGER: advertises FINGER only and never matches. */
    public static final class FingerOnlySdk implements IBioApi {

        static volatile int matchCalls;

        /** Created by MOSIP's loader. */
        public FingerOnlySdk() {
        }

        @Override
        public SDKInfo init(Map<String, String> initParams) {
            return new SDKInfo("0.9", "test", "test", "test")
                    .withSupportedMethod(BiometricFunction.MATCH, BiometricType.FINGER);
        }

        @Override
        public Response<QualityCheck> checkQuality(BiometricRecord sample, List<BiometricType> modalitiesToCheck,
                                                   Map<String, String> flags) {
            return new Response<>();
        }

        @Override
        public Response<MatchDecision[]> match(BiometricRecord sample, BiometricRecord[] gallery,
                                               List<BiometricType> modalitiesToMatch, Map<String, String> flags) {
            matchCalls++;
            Response<MatchDecision[]> r = new Response<>();
            r.setStatusCode(200);
            MatchDecision d = new MatchDecision(0);
            d.setDecisions(new HashMap<>());
            r.setResponse(new MatchDecision[] {d});
            return r;
        }

        @Override
        public Response<BiometricRecord> extractTemplate(BiometricRecord sample, List<BiometricType> modalities,
                                                         Map<String, String> flags) {
            return new Response<>();
        }

        @Override
        public Response<BiometricRecord> segment(BiometricRecord sample, List<BiometricType> modalities,
                                                 Map<String, String> flags) {
            return new Response<>();
        }

        @Deprecated
        @Override
        public BiometricRecord convertFormat(BiometricRecord sample, String sourceFormat, String targetFormat,
                                             Map<String, String> sourceParams, Map<String, String> targetParams,
                                             List<BiometricType> modalitiesToConvert) {
            return sample;
        }
    }
}
