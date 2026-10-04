/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.FaceQuality;
import io.mosip.liveness.core.vision.HeadPose;
import io.mosip.liveness.core.vision.PadScore;
import io.mosip.liveness.core.vision.PassivePad;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Measures every frame of a pack with the real models, without a session: face count and box, quality, head
 * pose, eye and mouth measures, gaze and the passive PAD probability. Used to label action segments and to
 * calibrate detection thresholds; writes CSV, never images.
 */
final class PackInspector {

    /** CSV header written by {@link #inspect}. */
    static final String HEADER = "pack,frame,faces,confidence,box_x,box_y,box_w,box_h,ied_px,sharpness,brightness,"
            + "yaw_deg,pitch_deg,roll_deg,ear_left,ear_right,mouth_ratio,gaze_h,gaze_v,pad_live,analyze_ms,pad_ms";

    private final ImageIoDecoder decoder;
    private final FaceAnalyzer analyzer;
    private final PassivePad pad;

    PackInspector(ImageIoDecoder decoder, FaceAnalyzer analyzer, PassivePad pad) {
        this.decoder = decoder;
        this.analyzer = analyzer;
        this.pad = pad;
    }

    /**
     * Measures a pack and writes one CSV row per frame.
     *
     * @param pack the pack
     * @param csv  receives the rows (without header)
     * @return a one-line summary of the pack
     * @throws EngineException if a model fails
     */
    String inspect(FrameSequence pack, PrintWriter csv) throws EngineException {
        List<Double> pads = new ArrayList<>();
        List<Double> ieds = new ArrayList<>();
        List<Double> yaws = new ArrayList<>();
        List<Double> pitches = new ArrayList<>();
        List<Double> ears = new ArrayList<>();
        List<Double> mouths = new ArrayList<>();
        int single = 0;
        for (int i = 0; i < pack.size(); i++) {
            ImageBuffer image;
            try {
                image = decoder.decode(pack.frame(i));
            } catch (ImageDecodeException e) {
                csv.printf(Locale.ROOT, "%s,%d,-1%n", pack.id(), i);
                continue;
            }
            long t0 = System.nanoTime();
            FaceObservation obs = analyzer.analyze(image);
            double analyzeMs = (System.nanoTime() - t0) / 1e6;
            StringBuilder row = new StringBuilder();
            row.append(pack.id()).append(',').append(i).append(',').append(obs.faceCount());
            if (!obs.primary().isPresent()) {
                csv.println(row);
                continue;
            }
            DetectedFace f = obs.primary().get();
            if (obs.faceCount() == 1) {
                single++;
            }
            t0 = System.nanoTime();
            PadScore score = pad.score(image, f.box());
            double padMs = (System.nanoTime() - t0) / 1e6;
            FaceBox b = f.box();
            FaceQuality q = f.quality().orElse(null);
            HeadPose p = f.pose().orElse(null);
            double ear = mean(f.leftEyeAspectRatio(), f.rightEyeAspectRatio());
            append(row, b.confidence(), b.x(), b.y(), b.width(), b.height(),
                    q == null ? Double.NaN : q.interEyeDistancePx(), q == null ? Double.NaN : q.sharpness(),
                    q == null ? Double.NaN : q.brightness(), p == null ? Double.NaN : p.yawDeg(),
                    p == null ? Double.NaN : p.pitchDeg(), p == null ? Double.NaN : p.rollDeg(),
                    val(f.leftEyeAspectRatio()), val(f.rightEyeAspectRatio()), val(f.mouthWidthRatio()),
                    val(f.gazeHorizontal()), val(f.gazeVertical()), score.liveProbability(), analyzeMs, padMs);
            csv.println(row);
            pads.add(score.liveProbability() * 100);
            addIfFinite(ieds, q == null ? Double.NaN : q.interEyeDistancePx());
            addIfFinite(yaws, p == null ? Double.NaN : p.yawDeg());
            addIfFinite(pitches, p == null ? Double.NaN : p.pitchDeg());
            addIfFinite(ears, ear);
            addIfFinite(mouths, val(f.mouthWidthRatio()));
        }
        return String.format(Locale.ROOT, "%s: frames=%d single-face=%d pad(median/min/max)=%.1f/%.1f/%.1f "
                        + "ied(median)=%.1f yaw[%.1f..%.1f] pitch[%.1f..%.1f] ear(min/median)=%.3f/%.3f "
                        + "mouth(min/median/max)=%.3f/%.3f/%.3f", pack.id(), pack.size(), single, median(pads),
                min(pads), max(pads), median(ieds), min(yaws), max(yaws), min(pitches), max(pitches), min(ears),
                median(ears), min(mouths), median(mouths), max(mouths));
    }

    private static void append(StringBuilder row, double... values) {
        for (double v : values) {
            row.append(',');
            if (!Double.isNaN(v)) {
                row.append(String.format(Locale.ROOT, "%.4f", v));
            }
        }
    }

    private static double val(OptionalDouble d) {
        return d.isPresent() ? d.getAsDouble() : Double.NaN;
    }

    private static double mean(OptionalDouble a, OptionalDouble b) {
        if (a.isPresent() && b.isPresent()) {
            return (a.getAsDouble() + b.getAsDouble()) / 2;
        }
        return Double.NaN;
    }

    private static void addIfFinite(List<Double> list, double v) {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) {
            list.add(v);
        }
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static double min(List<Double> values) {
        return values.isEmpty() ? Double.NaN : Collections.min(values);
    }

    private static double max(List<Double> values) {
        return values.isEmpty() ? Double.NaN : Collections.max(values);
    }
}
