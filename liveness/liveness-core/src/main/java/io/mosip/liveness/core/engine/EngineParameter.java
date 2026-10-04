package io.mosip.liveness.core.engine;

/**
 * A tunable detection parameter of the default engine, with its default and allowed range.
 *
 * <p>These are not operator settings: docs/design/configuration.md section 12 keeps detection thresholds in the signed
 * {@code engineParams} of the model bundle, so a machine's configuration cannot weaken them. The
 * {@link #key()} is the name used in that file. Operator settings (thresholds, timeouts, challenge pool) live in
 * {@link io.mosip.liveness.core.config.LivenessPolicy}. Angles are degrees, times milliseconds, brightness grey
 * levels 0-255, ratios plain numbers. Defaults marked "to calibrate" are starting values until measured on the
 * development split (docs/eval/calibration-notes.md).
 */
public enum EngineParameter {

    // ---- Acquisition quality gate ----

    /** Consecutive good frames that end acquisition (design: 5). */
    ACQUIRE_CONSECUTIVE_FRAMES("acquire.consecutive_frames", 5, 1, 30, true),
    /** Lowest detector confidence of the subject's face. */
    MIN_FACE_CONFIDENCE("quality.min_face_confidence", 0.5, 0, 1, false),
    /** Largest head pitch accepted while acquiring. */
    MAX_PITCH_DEG("quality.max_pitch_deg", 20, 0, 90, false),
    /** Largest head roll accepted while acquiring. */
    MAX_ROLL_DEG("quality.max_roll_deg", 25, 0, 90, false),
    /** Darkest accepted mean grey level of the face. */
    MIN_BRIGHTNESS("quality.min_brightness", 50, 0, 255, false),
    /**
     * Mean grey level of the passive frames below which a passive PAD failure is treated as a lighting problem:
     * the session ends with {@code LOW_LIGHT} ("Improve lighting"), not counted, up to
     * {@code passive.low_light_max_uncounted} times in a row. Lies above {@code quality.min_brightness}, the
     * TOO_DARK floor; a value at or below that floor turns the rule off. Calibrated on 2026-10-04 with the
     * analyser's face brightness on run/frame-packs: the synthetic dim genuine packs s3-dim-* measure 66.5-69.5, every
     * attack pack 82.1 or more (m-stream-static 82.1, a-s2-* 108-143, a-s3-* 145-162), so 75 leaves about 6 grey
     * levels on each side. The dim packs are darkened recordings; recheck on real dim-room recordings.
     */
    LOW_LIGHT_BRIGHTNESS("quality.low_light_brightness", 75, 0, 255, false),
    /** Brightest accepted mean grey level of the face. */
    MAX_BRIGHTNESS("quality.max_brightness", 225, 0, 255, false),
    /**
     * Lowest accepted sharpness (variance of the Laplacian of the grey face crop). The scale depends on the
     * analyser, so the value belongs to the model bundle: the ONNX bundle sets 40 in its signed
     * {@code engineParams} (blurred test pack 11-13, genuine packs 63 and above).
     */
    MIN_SHARPNESS("quality.min_sharpness", 10, 0, 1_000_000, false),
    /** Share of the face box that may lie outside the image before the face counts as out of the area. */
    OUT_OF_AREA_TOLERANCE("quality.out_of_area_tolerance", 0.1, 0, 1, false),

    // ---- Passive ----

    /**
     * Fraction of scores dropped at each end before averaging the per-frame PAD scores. 0.5 (the default) is
     * the median of docs/design/decision-flow.md section 1; lower values give a trimmed mean.
     */
    PASSIVE_TRIM_FRACTION("passive.trim_fraction", 0.5, 0, 0.5, false),
    /**
     * Good frames that are enough for the passive decision when {@code passive.timeout_ms} runs out before
     * {@code passive.min_frames} were collected (slow devices). Fewer frames than this end the session with
     * {@code PASSIVE_TIMEOUT}; the old default of {@code passive.min_frames} was 8.
     */
    PASSIVE_TIMEOUT_MIN_FRAMES("passive.timeout_min_frames", 8, 1, 300, true),
    /**
     * Passive PAD failures in low light ({@code quality.low_light_brightness}) that end uncounted before the next
     * one counts as a PAD failure again; the count restarts after a counted failure. Bounds the extra attempts that
     * darkening the scene can buy. 0 turns the rule off.
     */
    LOW_LIGHT_MAX_UNCOUNTED("passive.low_light_max_uncounted", 3, 0, 10, true),
    /** Embeddings averaged into the session reference face (same-person and binding checks). */
    REFERENCE_FRAMES("identity.reference_frames", 3, 1, 10, true),

    // ---- Active: timing ----

    /** Shortest random wait before a prompt (design: U(400,1500) ms). */
    CHALLENGE_DELAY_MIN_MS("active.delay_min_ms", 400, 0, 5_000, true),
    /** Longest random wait before a prompt. */
    CHALLENGE_DELAY_MAX_MS("active.delay_max_ms", 1_500, 0, 5_000, true),
    /** Time the face must stay neutral before a prompt. */
    NEUTRAL_HOLD_MS("active.neutral_hold_ms", 300, 0, 3_000, true),
    /** Largest absolute yaw of a neutral face. */
    NEUTRAL_MAX_YAW_DEG("active.neutral_max_yaw_deg", 10, 1, 45, false),
    /**
     * Largest absolute pitch of a neutral face. Equal to {@code quality.max_pitch_deg} (20): a face the quality gate
     * admits is not refused as "not neutral" for its pitch alone, which made a camera mounted above or below eye
     * level (12-20 degrees) end every attempt in {@code NEUTRAL_TIMEOUT}. Look-up and look-down are measured from the
     * neutral pitch, so a tilted neutral face still needs the full {@code active.look_pitch_deg} movement. The yaw
     * limit stays tighter than the gate because head turns are measured from it and the identity reference prefers
     * frontal faces.
     */
    NEUTRAL_MAX_PITCH_DEG("active.neutral_max_pitch_deg", 20, 1, 45, false),
    /** How long "Action detected" is shown after a challenge. */
    PASSED_DISPLAY_MS("active.passed_display_ms", 600, 0, 5_000, true),

    // ---- Active: action detection ----

    /** Yaw change from the neutral face that counts as a head turn (design: 20). */
    TURN_YAW_DEG("active.turn_yaw_deg", 20, 5, 60, false),
    /** Pitch change from the neutral face that counts as looking up or down (design: 15). */
    LOOK_PITCH_DEG("active.look_pitch_deg", 15, 5, 60, false),
    /**
     * Share of the trigger level at which an action counts as started. The onset time is the start of the
     * uninterrupted run above this level that leads to the trigger, so a noisy spike before it does not count.
     */
    ONSET_FRACTION("active.onset_fraction", 0.5, 0.1, 1, false),
    /** Eye aspect ratio, as a share of the neutral value, below which the eyes count as closed. */
    BLINK_CLOSE_RATIO("active.blink_close_ratio", 0.65, 0.1, 0.95, false),
    /** Eye aspect ratio, as a share of the neutral value, above which closed eyes count as open again. */
    BLINK_OPEN_RATIO("active.blink_open_ratio", 0.85, 0.1, 1, false),
    /** Shortest accepted eye closure of a blink (design: 80 ms). */
    BLINK_MIN_CLOSED_MS("active.blink_min_closed_ms", 80, 0, 1_000, true),
    /** Longest accepted eye closure of a blink (design: 500 ms). */
    BLINK_MAX_CLOSED_MS("active.blink_max_closed_ms", 500, 50, 3_000, true),
    /** Lowest mean eye aspect ratio of open eyes (neutral-face check). */
    EYES_OPEN_MIN_EAR("active.eyes_open_min_ear", 0.15, 0, 1, false),
    /** Relative increase of the mouth width ratio over the neutral value that counts as a smile; to calibrate. */
    SMILE_RATIO_INCREASE("active.smile_ratio_increase", 0.12, 0.01, 1, false),
    /** Smile probability that counts as a smile when the analyser gives one instead of a mouth ratio. */
    SMILE_SCORE_THRESHOLD("active.smile_score_threshold", 0.5, 0.05, 1, false),
    /**
     * Largest yaw change from the neutral face at which the smile is measured. Beyond it the mouth width ratio
     * is not a smile measure (the inter-eye distance shrinks faster than the mouth width when the head turns:
     * +25% at 43 degrees without a smile), so the smile counts as unmeasured.
     */
    SMILE_MAX_YAW_DEG("active.smile_max_yaw_deg", 15, 1, 90, false),
    /** Horizontal gaze offset that counts as looking left or right. */
    GAZE_THRESHOLD("active.gaze_threshold", 0.35, 0.05, 1, false),
    /** Frames in a row a non-requested action must show before it fails the challenge. */
    CONTRARY_MIN_FRAMES("active.contrary_min_frames", 2, 1, 10, true),
    /**
     * Analysed frames in a row with more than one face that end the challenges with {@code MULTI_FACE} (not counted,
     * "More than one face detected"). A single frame is not enough, so a passing detector glitch does not end it.
     */
    ACTIVE_MULTI_FACE_FRAMES("active.multi_face_frames", 3, 1, 30, true),
    /** During challenges, PAD runs on every n-th analysed frame. */
    ACTIVE_PAD_STRIDE("active.pad_stride", 2, 1, 10, true),
    /**
     * Largest absolute yaw of a frame scored by PAD during the challenges. The PAD models were trained on
     * near-frontal faces and score a genuine face at 40-65 degrees of yaw as 0.15-0.3, so such frames are left
     * out of the active PAD score.
     */
    ACTIVE_PAD_MAX_YAW_DEG("active.pad_max_yaw_deg", 30, 5, 90, false),
    /** Lowest analysed frame rate at which a head turn is required in the draw (design: 5 fps). */
    HEAD_TURN_MIN_FPS("active.head_turn_min_fps", 5, 0, 60, false),

    // ---- Frame integrity ----

    /**
     * Mean absolute grey-level difference between consecutive 64x64 point-sampled thumbnails below which two
     * frames count as identical; to calibrate on the target cameras.
     */
    FROZEN_MEAN_ABS_DIFF("integrity.frozen_mean_abs_diff", 0.5, 0, 50, false),
    /**
     * Frames searched for a repeated fingerprint sequence. A loop is found once its period plus
     * {@code integrity.loop_match_frames} fits in the window, so 300 catches replayed clips of up to 294 frames
     * (9.8 s at 30 fps, 19.6 s at 15 fps). Cost at 300, measured on 2026-10-04: 2,400 bytes of hashes per session
     * and at most about 25 microseconds of search per frame.
     */
    LOOP_WINDOW_FRAMES("integrity.loop_window_frames", 300, 8, 600, true),
    /** Consecutive frames that must repeat an earlier sequence to count as a loop. */
    LOOP_MATCH_FRAMES("integrity.loop_match_frames", 6, 3, 60, true),
    /** Largest Hamming distance between two 64-bit difference hashes that still counts as the same image. */
    LOOP_HASH_MAX_DISTANCE("integrity.loop_hash_max_distance", 3, 0, 16, true),
    /** Smallest total hash change inside a matched sequence; a still scene is frozen, not looped. */
    LOOP_MIN_MOTION_BITS("integrity.loop_min_motion_bits", 12, 1, 640, true),
    /** Lowest box overlap (intersection over union) between consecutive frames of one tracked face. */
    TRACKING_MIN_IOU("integrity.tracking_min_iou", 0.3, 0, 1, false),
    /** Longest gap between frames of one tracked face (raised to 2.5 frame intervals on slow streams). */
    TRACKING_MAX_GAP_MS("integrity.tracking_max_gap_ms", 500, 50, 10_000, true),
    /** Stream time observed before the frame rate is checked against {@code integrity.min_fps}. */
    FPS_WINDOW_MS("integrity.fps_window_ms", 2_000, 500, 30_000, true),
    /** Share below {@code integrity.min_fps} tolerated for timing jitter (3 fps nominal may measure 2.9). */
    FPS_TOLERANCE("integrity.fps_tolerance", 0.1, 0, 0.5, false),
    /** Undecodable frames in a row that end the session with {@code INVALID_FRAMES}. */
    MAX_CORRUPT_FRAMES("integrity.max_corrupt_frames", 10, 1, 1_000, true),

    // ---- Device and engine watchdogs ----

    /** No frame for this long while observing ends the session with a device error (design: 2 s). */
    NO_FRAME_TIMEOUT_MS("device.no_frame_timeout_ms", 2_000, 200, 60_000, true),
    /** No valid frame within this time after start ends the session with a device error (design: 3 s). */
    FIRST_FRAME_TIMEOUT_MS("device.first_frame_timeout_ms", 3_000, 200, 60_000, true),
    /** Frame analysis slower than this counts as a slow inference. */
    INFERENCE_SLOW_MS("engine.inference_slow_ms", 500, 10, 60_000, true),
    /** Slow inferences in a row that end the session with an engine error (design: 3). */
    INFERENCE_SLOW_COUNT("engine.inference_slow_count", 3, 1, 100, true),

    // ---- Planarity ----

    /** 1 to run the planarity check on head turns, 0 to skip it. */
    PLANARITY_ENABLED("planarity.enabled", 1, 0, 1, true),
    /**
     * Lowest landmark residual (RMS after the best plane mapping, divided by the inter-eye distance) of a real
     * head turn; smaller means a flat photo or screen. To calibrate on the development split.
     */
    PLANARITY_MIN_RESIDUAL("planarity.min_residual", 0.02, 0, 1, false),

    // ---- Capture binding ----

    /** Time allowed for the signed capture after it starts; part of the pass token lifetime. */
    CAPTURE_TIMEOUT_MS("binding.capture_timeout_ms", 10_000, 1_000, 120_000, true),
    /** Tolerated difference between the device clock and the client clock. */
    CLOCK_SKEW_TOLERANCE_MS("binding.clock_skew_ms", 2_000, 0, 60_000, true),
    /** Largest yaw, pitch and roll of the frame chosen for storage (assumed ISO/IEC 19794-5 frontal limit). */
    CAPTURE_FRONTAL_MAX_DEG("binding.capture_frontal_max_deg", 5, 1, 45, false);

    private final String key;
    private final double defaultValue;
    private final double min;
    private final double max;
    private final boolean integer;

    EngineParameter(String key, double defaultValue, double min, double max, boolean integer) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.integer = integer;
    }

    /**
     * Returns the name of the parameter in the model bundle's {@code engineParams}.
     *
     * @return for example {@code "active.turn_yaw_deg"}
     */
    public String key() {
        return key;
    }

    /**
     * Returns the shipped default.
     *
     * @return the default value
     */
    public double defaultValue() {
        return defaultValue;
    }

    /**
     * Returns the smallest allowed value.
     *
     * @return the minimum
     */
    public double min() {
        return min;
    }

    /**
     * Returns the largest allowed value.
     *
     * @return the maximum
     */
    public double max() {
        return max;
    }

    /**
     * Tells whether only whole numbers are allowed.
     *
     * @return {@code true} for counts, milliseconds and switches
     */
    public boolean isInteger() {
        return integer;
    }

    /**
     * Finds a parameter by its key.
     *
     * @param key the key; may be {@code null}
     * @return the parameter, or {@code null} if the key is unknown
     */
    static EngineParameter fromKey(String key) {
        for (EngineParameter p : values()) {
            if (p.key.equals(key)) {
                return p;
            }
        }
        return null;
    }
}
