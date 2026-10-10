/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.kernel.biometrics.constant.BiometricFunction;
import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.constant.Match;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.biometrics.model.Decision;
import io.mosip.kernel.biometrics.model.MatchDecision;
import io.mosip.kernel.biometrics.model.QualityCheck;
import io.mosip.kernel.biometrics.model.QualityScore;
import io.mosip.kernel.biometrics.model.Response;
import io.mosip.kernel.biometrics.model.SDKInfo;
import io.mosip.kernel.biometrics.spi.IBioApiV2;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.onnx.model.ModelSources;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * MOSIP biometric SDK ({@link IBioApiV2}, API version {@value #API_VERSION}) for FACE, on the SFace embedding
 * model of the signed liveness bundle. It replaces MOSIP's mock SDK for FACE only; the mock SDK (or a vendor SDK)
 * stays configured for FINGER and IRIS, because MOSIP selects one SDK per modality.
 *
 * <p>Selected in the Registration Clients with (desktop and Android use the same keys):
 * <pre>
 * mosip.biometric.sdk.providers.face.&lt;vendor&gt;.classname=io.mosip.liveness.facematch.SFaceBioApi
 * mosip.biometric.sdk.providers.face.&lt;vendor&gt;.version=0.9
 * mosip.biometric.sdk.providers.face.&lt;vendor&gt;.args=
 * mosip.biometric.sdk.providers.face.&lt;vendor&gt;.threshold=40
 * </pre>
 * Optional parameters under the same prefix: {@value #PARAM_MODEL_DIR} (a directory holding the model bundle;
 * default: the bundle on the class path, {@code liveness-onnx-<version>-models.jar}) and {@value #PARAM_DECODER}
 * (class name of the image decoder; default {@value #DEFAULT_DECODER}).
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #init}: verifies the bundle signature and loads YuNet and SFace.</li>
 *   <li>{@link #checkQuality}: 0-100 from the face detector ({@link FaceQualityScore}).</li>
 *   <li>{@link #extractTemplate}: replaces each FACE BDB with a {@link FaceTemplate} (539 bytes); other segments
 *       are returned unchanged.</li>
 *   <li>{@link #match}: similarity {@code round(100 * max(0, cosine))} of the best probe/gallery face pair;
 *       {@code MATCHED} when it is at least the threshold. BDBs may be ISO/IEC 19794-5 records, plain images or
 *       templates. The score and threshold are in each decision's analytics; nothing biometric is.</li>
 *   <li>{@link #segment}, {@link #convertFormatV2}: FACE needs no segmentation and only same-format conversion is
 *       supported; they are not advertised in {@link SDKInfo}.</li>
 *   <li>Other modalities: not advertised; a request for them only gets {@link SdkStatus#INVALID_INPUT}, or an
 *       {@code ERROR} decision beside the FACE decision.</li>
 * </ul>
 *
 * <p>Thread-safe. MOSIP's loaders create one instance per configuration and call {@link #init} once; calling it
 * again with other settings reloads the models.
 */
public class SFaceBioApi implements IBioApiV2 {

    /** MOSIP biometric SDK API version this class implements. */
    public static final String API_VERSION = "0.9";
    /** Version of this SDK. */
    public static final String SDK_VERSION = "1.0.0";
    /** Product owner organisation reported in {@link SDKInfo}. */
    public static final String ORGANIZATION = "MOSIP";
    /** Product owner type reported in {@link SDKInfo}. */
    public static final String TYPE = "SFace face match";
    /** Init parameter: match threshold on the 0-100 similarity scale. */
    public static final String PARAM_THRESHOLD = "threshold";
    /** Init parameter: directory of the model bundle. */
    public static final String PARAM_MODEL_DIR = "modeldir";
    /** Init parameter: image decoder class name. */
    public static final String PARAM_DECODER = "decoder";
    /** Threshold used when none is configured: cosine 0.40, a little stricter than OpenCV's 0.363 for SFace. */
    public static final double DEFAULT_THRESHOLD = 40;
    /** Default image decoder (desktop, ImageIO). */
    public static final String DEFAULT_DECODER = "io.mosip.liveness.facematch.imageio.ImageIoFaceImageDecoder";
    /** Analytics key: similarity 0-100 of the best face pair. */
    public static final String ANALYTICS_SCORE = "score";
    /** Analytics key: threshold in use. */
    public static final String ANALYTICS_THRESHOLD = "threshold";
    /** Analytics key: embedding model identifier. */
    public static final String ANALYTICS_MODEL = "model";

    /** Calls that use the matcher share the read lock; replacing or closing the matcher takes the write lock. */
    private final ReentrantReadWriteLock models = new ReentrantReadWriteLock();
    private volatile SFaceMatcher matcher;
    private volatile SdkSettings settings;

    /** Creates the SDK; MOSIP's loaders call this, then {@link #init}. Loads nothing. */
    public SFaceBioApi() {
        // Models load in init().
    }

    /**
     * Loads the models and reads the parameters.
     *
     * @param initParams the {@code mosip.biometric.sdk.providers.face.<vendor>.*} values by parameter name
     * @return the SDK description: FACE for {@code MATCH}, {@code QUALITY_CHECK} and {@code EXTRACT}
     * @throws IllegalArgumentException if the threshold is invalid or the decoder class unusable
     * @throws IllegalStateException    if the model bundle fails verification or cannot be loaded
     */
    @Override
    public synchronized SDKInfo init(Map<String, String> initParams) {
        SdkSettings next = SdkSettings.from(initParams);
        SFaceMatcher previous = matcher;
        // Loaded outside the lock, so running matches go on with the old models meanwhile.
        SFaceMatcher current = previous == null || settings.needsReload(next) ? load(next) : previous;
        models.writeLock().lock();
        try {
            // Settings first: a reader that sees the new matcher (volatile) also sees its settings.
            settings = next;
            matcher = current;
            if (previous != null && previous != current) {
                previous.close();
            }
        } finally {
            models.writeLock().unlock();
        }
        return sdkInfo(current, next);
    }

    /** Runs a call that uses the matcher; {@link #init} and {@link #close} wait until it ends before closing it. */
    private <T> T withModels(Supplier<T> call) {
        models.readLock().lock();
        try {
            return call.get();
        } finally {
            models.readLock().unlock();
        }
    }

    private static SFaceMatcher load(SdkSettings s) {
        ModelSource source = s.modelDirectory() == null
                ? ModelSources.classpath(SFaceBioApi.class.getClassLoader())
                : ModelSources.directory(new File(s.modelDirectory()));
        try {
            return SFaceMatcher.load(source, decoder(s.decoderClass()));
        } catch (EngineException e) {
            throw new IllegalStateException("SFace SDK models could not be loaded (" + e.getErrorCode() + "): "
                    + e.getMessage(), e);
        }
    }

    /**
     * Creates the image decoder named in the settings: a {@link FaceImageDecoder}, or a liveness
     * {@link ImageDecoder} (wrapped), with a public no-argument constructor.
     */
    static FaceImageDecoder decoder(String className) {
        Object instance;
        try {
            Class<?> type = Class.forName(className, true, SFaceBioApi.class.getClassLoader());
            instance = type.getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalArgumentException("SFace SDK image decoder " + className + " cannot be created", e);
        }
        if (instance instanceof FaceImageDecoder) {
            return (FaceImageDecoder) instance;
        }
        if (instance instanceof ImageDecoder) {
            return FaceImageDecoder.of((ImageDecoder) instance);
        }
        throw new IllegalArgumentException("SFace SDK image decoder " + className + " is not a FaceImageDecoder");
    }

    private static SDKInfo sdkInfo(SFaceMatcher m, SdkSettings s) {
        SDKInfo info = new SDKInfo(API_VERSION, SDK_VERSION, ORGANIZATION, TYPE);
        info.setSupportedModalities(new ArrayList<>(Collections.singletonList(BiometricType.FACE)));
        Map<BiometricFunction, List<BiometricType>> methods = new EnumMap<>(BiometricFunction.class);
        for (BiometricFunction f : new BiometricFunction[] {BiometricFunction.MATCH, BiometricFunction.QUALITY_CHECK,
                BiometricFunction.EXTRACT}) {
            methods.put(f, new ArrayList<>(Collections.singletonList(BiometricType.FACE)));
        }
        info.setSupportedMethods(methods);
        Map<String, String> other = new HashMap<>();
        other.put(ANALYTICS_MODEL, m.modelId());
        other.put(ANALYTICS_THRESHOLD, format(s.threshold()));
        for (ModelInfo model : m.models()) {
            other.put("model." + model.id(), model.version() + "#" + model.sha256Prefix());
        }
        info.setOtherInfo(other);
        return info;
    }

    @Override
    public Response<QualityCheck> checkQuality(BiometricRecord sample, List<BiometricType> modalitiesToCheck,
                                               Map<String, String> flags) {
        return withModels(() -> checkQualityNow(sample, modalitiesToCheck));
    }

    private Response<QualityCheck> checkQualityNow(BiometricRecord sample, List<BiometricType> modalitiesToCheck) {
        SFaceMatcher m = matcher;
        Response<QualityCheck> invalid = checkRequest(m, sample, modalitiesToCheck);
        if (invalid != null) {
            return invalid;
        }
        QualityCheck check = new QualityCheck();
        Map<BiometricType, QualityScore> scores = new EnumMap<>(BiometricType.class);
        scores.put(BiometricType.FACE, faceQuality(m, Birs.faceSegments(sample)));
        check.setScores(scores);
        check.setAnalyticsInfo(new HashMap<>());
        return ok(check);
    }

    private static QualityScore faceQuality(SFaceMatcher m, List<BIR> faces) {
        List<String> errors = new ArrayList<>();
        Map<String, String> analytics = new HashMap<>();
        double sum = 0;
        for (BIR bir : faces) {
            try {
                FaceQualityScore q = m.quality(bir.getBdb());
                sum += q.score();
                addAll(errors, q.flags());
                analytics.put("faces", Integer.toString(q.faces()));
                analytics.put("interEyeDistance", Long.toString(Math.round(q.interEyeDistance())));
                analytics.put("brightness", Long.toString(Math.round(q.brightness())));
            } catch (FaceMatchException e) {
                addAll(errors, Collections.singletonList(e.problem().name()));
            }
        }
        QualityScore score = new QualityScore();
        score.setScore((float) (sum / faces.size()));
        score.setErrors(errors);
        score.setAnalyticsInfo(analytics);
        return score;
    }

    @Override
    public Response<MatchDecision[]> match(BiometricRecord sample, BiometricRecord[] gallery,
                                           List<BiometricType> modalitiesToMatch, Map<String, String> flags) {
        return withModels(() -> matchNow(sample, gallery, modalitiesToMatch));
    }

    private Response<MatchDecision[]> matchNow(BiometricRecord sample, BiometricRecord[] gallery,
                                               List<BiometricType> modalitiesToMatch) {
        SFaceMatcher m = matcher;
        if (gallery == null) {
            return failure(SdkStatus.MISSING_INPUT, "gallery");
        }
        Response<MatchDecision[]> invalid = checkRequest(m, sample, modalitiesToMatch);
        if (invalid != null) {
            return invalid;
        }
        List<FaceSample> probes = new ArrayList<>();
        FaceMatchException firstProblem = null;
        for (BIR bir : Birs.faceSegments(sample)) {
            try {
                probes.add(m.analyse(bir.getBdb()));
            } catch (FaceMatchException e) {
                firstProblem = firstProblem == null ? e : firstProblem;
            }
        }
        if (probes.isEmpty()) {
            return failure(statusOf(firstProblem), "sample FACE: " + firstProblem.getMessage());
        }
        double threshold = settings.threshold();
        MatchDecision[] decisions = new MatchDecision[gallery.length];
        for (int i = 0; i < gallery.length; i++) {
            decisions[i] = new MatchDecision(i);
            Map<BiometricType, Decision> byModality = new EnumMap<>(BiometricType.class);
            byModality.put(BiometricType.FACE, faceDecision(m, probes, gallery[i], threshold));
            for (BiometricType other : Birs.unsupported(modalitiesToMatch)) {
                byModality.put(other, decision(Match.ERROR, "NOT_SUPPORTED: " + other.name()));
            }
            decisions[i].setDecisions(byModality);
            decisions[i].setAnalyticsInfo(new HashMap<>());
        }
        return ok(decisions);
    }

    private static Decision faceDecision(SFaceMatcher m, List<FaceSample> probes, BiometricRecord candidate,
                                         double threshold) {
        List<BIR> faces = Birs.faceSegments(candidate);
        if (faces.isEmpty()) {
            return decision(Match.ERROR, "NO_FACE_IN_GALLERY_RECORD");
        }
        int best = -1;
        List<String> errors = new ArrayList<>();
        for (BIR bir : faces) {
            try {
                FaceSample reference = m.analyse(bir.getBdb());
                for (FaceSample probe : probes) {
                    best = Math.max(best, SFaceMatcher.similarity(probe.embedding(), reference.embedding()));
                }
            } catch (FaceMatchException e) {
                addAll(errors, Collections.singletonList(e.problem().name()));
            }
        }
        if (best < 0) {
            Decision d = decision(Match.ERROR, null);
            d.setErrors(errors);
            return d;
        }
        Decision d = decision(best >= threshold ? Match.MATCHED : Match.NOT_MATCHED, null);
        d.getAnalyticsInfo().put(ANALYTICS_SCORE, Integer.toString(best));
        d.getAnalyticsInfo().put(ANALYTICS_THRESHOLD, format(threshold));
        d.getAnalyticsInfo().put(ANALYTICS_MODEL, m.modelId());
        return d;
    }

    @Override
    public Response<BiometricRecord> extractTemplate(BiometricRecord sample, List<BiometricType> modalitiesToExtract,
                                                     Map<String, String> flags) {
        return withModels(() -> extractTemplateNow(sample, modalitiesToExtract));
    }

    private Response<BiometricRecord> extractTemplateNow(BiometricRecord sample,
                                                         List<BiometricType> modalitiesToExtract) {
        SFaceMatcher m = matcher;
        Response<BiometricRecord> invalid = checkRequest(m, sample, modalitiesToExtract);
        if (invalid != null) {
            return invalid;
        }
        List<BIR> segments = new ArrayList<>();
        for (BIR bir : sample.getSegments()) {
            if (!Birs.isFace(bir)) {
                segments.add(bir);
                continue;
            }
            try {
                segments.add(Birs.withTemplate(bir, FaceTemplate.encode(m.analyse(bir.getBdb()).embedding())));
            } catch (FaceMatchException e) {
                return failure(statusOf(e), "FACE: " + e.getMessage());
            }
        }
        return ok(Birs.recordLike(sample, segments));
    }

    /**
     * Returns the FACE segments unchanged: a face image needs no segmentation.
     *
     * @param biometricRecord    the record
     * @param modalitiesToSegment requested modalities
     * @param flags              not used
     * @return the record, or a failure status for a request without FACE
     */
    @Override
    public Response<BiometricRecord> segment(BiometricRecord biometricRecord, List<BiometricType> modalitiesToSegment,
                                             Map<String, String> flags) {
        SFaceMatcher m = matcher;
        Response<BiometricRecord> invalid = checkRequest(m, biometricRecord, modalitiesToSegment);
        return invalid != null ? invalid : ok(biometricRecord);
    }

    /**
     * Converts between formats; only a conversion to the same format (returning the record unchanged) is
     * supported.
     *
     * @param sample             the record
     * @param sourceFormat       format of the record
     * @param targetFormat       requested format
     * @param sourceParams       not used
     * @param targetParams       not used
     * @param modalitiesToConvert requested modalities
     * @return the record for a same-format request, else {@link SdkStatus#INVALID_INPUT}
     */
    @Override
    public Response<BiometricRecord> convertFormatV2(BiometricRecord sample, String sourceFormat, String targetFormat,
                                                     Map<String, String> sourceParams, Map<String, String> targetParams,
                                                     List<BiometricType> modalitiesToConvert) {
        if (sample == null) {
            return failure(SdkStatus.MISSING_INPUT, "sample");
        }
        if (sourceFormat == null || !sourceFormat.equalsIgnoreCase(targetFormat)) {
            return failure(SdkStatus.INVALID_INPUT, "format conversion is not supported");
        }
        return ok(sample);
    }

    /**
     * Deprecated MOSIP API without a status: returns the record for a same-format request.
     *
     * @param sample             the record
     * @param sourceFormat       format of the record
     * @param targetFormat       requested format
     * @param sourceParams       not used
     * @param targetParams       not used
     * @param modalitiesToConvert requested modalities
     * @return the record
     * @throws UnsupportedOperationException for any other conversion
     * @deprecated as in {@code IBioApi}; use {@link #convertFormatV2}
     */
    @Deprecated
    @Override
    public BiometricRecord convertFormat(BiometricRecord sample, String sourceFormat, String targetFormat,
                                         Map<String, String> sourceParams, Map<String, String> targetParams,
                                         List<BiometricType> modalitiesToConvert) {
        Response<BiometricRecord> r = convertFormatV2(sample, sourceFormat, targetFormat, sourceParams, targetParams,
                modalitiesToConvert);
        if (r.getStatusCode() != SdkStatus.SUCCESS.code()) {
            throw new UnsupportedOperationException(r.getStatusMessage());
        }
        return r.getResponse();
    }

    private static <T> Response<T> checkRequest(SFaceMatcher m, BiometricRecord sample, List<BiometricType> modalities) {
        if (m == null) {
            return failure(SdkStatus.UNKNOWN_ERROR, null);
        }
        if (sample == null) {
            return failure(SdkStatus.MISSING_INPUT, "sample");
        }
        if (!Birs.wantsFace(modalities)) {
            return failure(SdkStatus.INVALID_INPUT, "only FACE is supported");
        }
        if (Birs.faceSegments(sample).isEmpty()) {
            return failure(SdkStatus.MISSING_INPUT, "FACE segment");
        }
        return null;
    }

    private static SdkStatus statusOf(FaceMatchException e) {
        switch (e.problem()) {
            case NO_FACE:
                return SdkStatus.POOR_DATA_QUALITY;
            case ENGINE:
                return SdkStatus.UNKNOWN_ERROR;
            default:
                return SdkStatus.INVALID_INPUT;
        }
    }

    private static Decision decision(Match match, String error) {
        Decision d = new Decision();
        d.setMatch(match);
        d.setErrors(error == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(error)));
        d.setAnalyticsInfo(new HashMap<>());
        return d;
    }

    private static <T> Response<T> ok(T body) {
        Response<T> r = new Response<>();
        r.setStatusCode(SdkStatus.SUCCESS.code());
        r.setStatusMessage(SdkStatus.SUCCESS.message(null));
        r.setResponse(body);
        return r;
    }

    private static <T> Response<T> failure(SdkStatus status, String detail) {
        Response<T> r = new Response<>();
        r.setStatusCode(status.code());
        r.setStatusMessage(status.message(detail));
        return r;
    }

    private static void addAll(List<String> target, List<String> values) {
        for (String v : values) {
            if (!target.contains(v)) {
                target.add(v);
            }
        }
    }

    private static String format(double threshold) {
        return threshold == Math.rint(threshold) ? Long.toString((long) threshold) : Double.toString(threshold);
    }

    /** Releases the native model sessions. MOSIP's loaders never call this; it is for tests and tools. */
    public synchronized void close() {
        models.writeLock().lock();
        try {
            if (matcher != null) {
                matcher.close();
                matcher = null;
            }
        } finally {
            models.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "SFaceBioApi[" + (matcher == null ? "not initialised" : matcher + ", " + settings) + "]";
    }
}
