package io.mosip.registration.livenessmanager.audit;

import android.util.Log;

import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;

/**
 * Sinks that write to Logcat under the tag {@value #TAG}.
 *
 * <p>The diagnostic sink is the Android side of the engine's diagnostic log (docs/design/android-liveness.md, 13.3):
 * one line per record, with score buckets, flags and timings only. The audit sink is used only when the capture
 * screen runs without ARC's database (a debug launch from adb before the app was opened); inside the app, audits go to
 * {@link ArcAuditSink}.
 */
public final class LogcatSinks {

    /** Logcat tag of every liveness line. */
    public static final String TAG = "Liveness";

    private LogcatSinks() {
    }

    /**
     * Returns the diagnostic sink.
     *
     * @return a sink that writes {@code DiagnosticRecord.toLogLine()} at level INFO
     */
    public static DiagnosticSink diagnostics() {
        return record -> Log.i(TAG, record.toLogLine());
    }

    /**
     * Returns an audit sink that writes each event's ID and JSON description at level INFO.
     *
     * @return the sink
     */
    public static AuditSink audit() {
        return event -> Log.i(TAG, "audit " + event.eventId().id() + " " + event.descriptionJson());
    }
}
