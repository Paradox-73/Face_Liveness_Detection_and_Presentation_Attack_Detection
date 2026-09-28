package io.mosip.liveness.core.ui;

/**
 * How a UI state is styled. Each client maps it to text, icon and colour together, never colour alone.
 */
public enum Severity {

    /** Neutral progress or instruction. */
    INFO,
    /** Positive feedback (action detected, success). */
    SUCCESS,
    /** Guidance the person can act on (no face, poor lighting, retry available). */
    WARNING,
    /** A failure that ends the attempt. */
    ERROR
}
