package io.mosip.liveness.core.engine;

/**
 * A tunable detection parameter of the default engine, with its default and allowed range.
 *
 * <p>These are not operator settings: design section 4.7 keeps detection thresholds in the signed
 * {@code engineParams} of the model bundle, so a machine's configuration cannot weaken them. The
 * {@link #key()} is the name used in that file. Operator settings (thresholds, timeouts, challenge pool) live in
 * {@link io.mosip.liveness.core.config.LivenessPolicy}. Angles are degrees, times milliseconds, brightness grey
 * levels 0-255, ratios plain numbers. Defaults marked "to calibrate" are starting values until measured on the
 * development split (design section 10.6).
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
    /** Brightest accepted mean grey level of the face. */
    MAX_BRIGHTNESS("quality.max_brightness", 225, 0, 255, false),
    /** Lowest accepted sharpness (variance of the Laplacian of the grey face crop); to calibrate per analyser. */
    MIN_SHARPNESS("quality.min_sharpness", 10, 0, 1_000_000, false),
    /** Share of the face box that may lie outside the image before the face counts as out of the area. */
    OUT_OF_AREA_TOLERANCE("quality.out_of_area_tolerance", 0.1, 0, 1, false),

    // ---- Passive ----

    /**
     * Fraction of scores dropped at each end before averaging the per-frame PAD scores. 0.5 (the default) is
     * the median of design section 4.6; lower values give a trimmed mean.
     */
    PASSIVE_TRIM_FRACTION("passive.trim_fraction", 0.5, 0, 0.5, false),
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
    /** Largest absolute pitch of a neutral face. */
    NEUTRAL_MAX_PITCH_DEG("active.neutral_max_pitch_deg", 12, 1, 45, false),
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
    /** Horizontal gaze offset that counts as looking left or right. */
    GAZE_THRESHOLD("active.gaze_threshold", 0.35, 0.05, 1, false),
    /** Frames in a row a non-requested action must show before it fails the challenge. */
    CONTRARY_MIN_FRAMES("active.contrary_min_frames", 2, 1, 10, true),
    /** During challenges, PAD runs on every n-th analysed frame. */
    ACTIVE_PAD_STRIDE("active.pad_stride", 2, 1, 10, true),
    /** Lowest analysed frame rate at which a head turn is required in the draw (design: 5 fps). */
    HEAD_TURN_MIN_FPS("active.head_turn_min_fps", 5, 0, 60, false),

    // ---- Frame integrity ----

    /**
     * Mean absolute grey-level difference between consecutive 64x64 point-sampled thumbnails below which two
     * frames count as identical; to calibrate on the target cameras.
     */
    FROZEN_MEAN_ABS_DIFF("integrity.frozen_mean_abs_diff", 0.5, 0, 50, false),
    /** Frames searched for a repeated fingerprint sequence (design: 30). */
    LOOP_WINDOW_FRAMES("integrity.loop_window_frames", 30, 8, 600, true),
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
