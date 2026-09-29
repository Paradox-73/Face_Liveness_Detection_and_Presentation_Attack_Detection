package io.mosip.liveness.facematch;

import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.constant.ProcessedLevelType;
import io.mosip.kernel.biometrics.entities.BDBInfo;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** Helpers for MOSIP biometric records: finding FACE segments and building template BIRs. */
final class Birs {

    private Birs() {
    }

    /**
     * Tells whether a request asks for FACE: an empty or missing list means every modality in the record.
     *
     * @param modalities requested modalities, or {@code null}
     * @return {@code true} if FACE is requested
     */
    static boolean wantsFace(List<BiometricType> modalities) {
        return modalities == null || modalities.isEmpty() || modalities.contains(BiometricType.FACE);
    }

    /**
     * Returns the requested modalities other than FACE.
     *
     * @param modalities requested modalities, or {@code null}
     * @return the unsupported ones, possibly empty
     */
    static List<BiometricType> unsupported(List<BiometricType> modalities) {
        if (modalities == null) {
            return Collections.emptyList();
        }
        List<BiometricType> others = new ArrayList<>();
        for (BiometricType t : modalities) {
            if (t != null && t != BiometricType.FACE && !others.contains(t)) {
                others.add(t);
            }
        }
        return others;
    }

    /**
     * Tells whether a BIR holds FACE data.
     *
     * @param bir the BIR, may be {@code null}
     * @return {@code true} if its BDB type list contains FACE
     */
    static boolean isFace(BIR bir) {
        return bir != null && bir.getBdbInfo() != null && bir.getBdbInfo().getType() != null
                && bir.getBdbInfo().getType().contains(BiometricType.FACE);
    }

    /**
     * Returns the FACE segments of a record.
     *
     * @param record the record, may be {@code null}
     * @return the FACE BIRs in record order, possibly empty
     */
    static List<BIR> faceSegments(BiometricRecord record) {
        List<BIR> faces = new ArrayList<>();
        if (record != null && record.getSegments() != null) {
            for (BIR bir : record.getSegments()) {
                if (isFace(bir)) {
                    faces.add(bir);
                }
            }
        }
        return faces;
    }

    /**
     * Returns a record with the same header as another and new segments.
     *
     * @param header   record whose version, CBEFF version, BIR info and others are copied
     * @param segments the segments
     * @return the new record
     */
    static BiometricRecord recordLike(BiometricRecord header, List<BIR> segments) {
        BiometricRecord out = new BiometricRecord(header.getVersion(), header.getCbeffversion(), header.getBirInfo());
        out.setSegments(segments);
        if (header.getOthers() != null) {
            out.setOthers(new HashMap<>(header.getOthers()));
        }
        return out;
    }

    /**
     * Returns a copy of a BIR with a template as its BDB and level {@code PROCESSED}. Everything else (format,
     * quality, subtype, purpose, SB and others) is kept, so the Registration Client stores and reads it as it
     * does any extracted template.
     *
     * @param source   the image BIR
     * @param template the template bytes
     * @return the template BIR
     */
    static BIR withTemplate(BIR source, byte[] template) {
        BIR out = new BIR();
        out.setVersion(source.getVersion());
        out.setCbeffversion(source.getCbeffversion());
        out.setBirInfo(source.getBirInfo());
        out.setBdbInfo(processedCopy(source.getBdbInfo()));
        out.setBdb(template);
        out.setSb(source.getSb());
        out.setSbInfo(source.getSbInfo());
        out.setBirs(source.getBirs());
        if (source.getOthers() != null) {
            out.setOthers(new HashMap<>(source.getOthers()));
        }
        return out;
    }

    private static BDBInfo processedCopy(BDBInfo in) {
        BDBInfo out = new BDBInfo();
        out.setChallengeResponse(in.getChallengeResponse());
        out.setIndex(in.getIndex());
        out.setFormat(in.getFormat());
        out.setEncryption(in.getEncryption());
        out.setCreationDate(in.getCreationDate());
        out.setNotValidBefore(in.getNotValidBefore());
        out.setNotValidAfter(in.getNotValidAfter());
        out.setType(in.getType());
        out.setSubtype(in.getSubtype());
        out.setProduct(in.getProduct());
        out.setCaptureDevice(in.getCaptureDevice());
        out.setFeatureExtractionAlgorithm(in.getFeatureExtractionAlgorithm());
        out.setComparisonAlgorithm(in.getComparisonAlgorithm());
        out.setCompressionAlgorithm(in.getCompressionAlgorithm());
        out.setPurpose(in.getPurpose());
        out.setQuality(in.getQuality());
        out.setLevel(ProcessedLevelType.PROCESSED);
        return out;
    }
}
