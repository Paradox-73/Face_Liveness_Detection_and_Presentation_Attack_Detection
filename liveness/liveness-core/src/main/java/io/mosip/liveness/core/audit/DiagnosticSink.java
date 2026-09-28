package io.mosip.liveness.core.audit;

/**
 * Receiver of diagnostic records, written only when {@code diagnostic.enable=Y}.
 *
 * <p>The desktop client logs {@link DiagnosticRecord#toLogLine()} at DEBUG to {@code logs/registration.log};
 * the Android client to Logcat with tag {@code Liveness}. Implementations must not throw.
 */
public interface DiagnosticSink {

    /**
     * Records one diagnostic entry.
     *
     * @param record the record
     */
    void log(DiagnosticRecord record);

    /**
     * Returns a sink that discards every record.
     *
     * @return a no-op sink
     */
    static DiagnosticSink noop() {
        return record -> {
        };
    }
}
