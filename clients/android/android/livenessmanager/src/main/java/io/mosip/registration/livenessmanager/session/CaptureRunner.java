package io.mosip.registration.livenessmanager.session;

/**
 * What the capture screen drives: one capture transaction with its retries. {@link LivenessRunner} runs the
 * liveness check; {@link PhotoCaptureRunner} takes a signed photo when the workflow's liveness policy is off.
 */
public interface CaptureRunner extends AutoCloseable {

    /** Starts the first attempt. */
    void start();

    /**
     * Starts the next attempt after a failed one.
     *
     * @return {@code false} if no retry is available
     */
    boolean retry();

    /** Cancels the running attempt, if any. Safe from any thread. */
    void cancel();

    /** Cancels the running attempt, releases the camera and stops the worker. Idempotent. */
    @Override
    void close();
}
