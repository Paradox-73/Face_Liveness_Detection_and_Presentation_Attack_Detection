package io.mosip.registration.livenessmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.policy.PolicyResolution;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.livenessmanager.audit.LogcatSinks;
import io.mosip.registration.livenessmanager.config.GlobalParamConfigSource;
import io.mosip.registration.livenessmanager.device.AndroidKeystoreSigner;
import io.mosip.registration.livenessmanager.device.DeviceSigner;
import io.mosip.registration.livenessmanager.engine.EngineLoader;
import io.mosip.registration.livenessmanager.image.AndroidImageDecoder;

/**
 * The process-wide pieces the liveness screen and service share: configuration, audit and diagnostic sinks,
 * image decoder, engine loader, device identity and key.
 *
 * <p>Inside ARC the Dagger {@code LivenessModule} installs it with ARC's configuration and audit service when the
 * app starts. When the capture screen is started on its own (a debug launch from adb before ARC was opened), a
 * standalone runtime is used instead: shipped default configuration and audits written to Logcat only.
 */
public final class LivenessRuntime {

    private static final String PREFS = "io.mosip.registration.livenessmanager";
    private static final String PREF_SERIAL = "device.serial";
    private static volatile LivenessRuntime installed;
    private static volatile LivenessRuntime standalone;

    private final Context context;
    private final ConfigSource config;
    private final AuditSink audit;
    private final DiagnosticSink diagnostics;
    private final AndroidImageDecoder decoder;
    private final EngineLoader engines;
    private final Clock clock;
    private final boolean isStandalone;
    private volatile DeviceSigner signer;

    private LivenessRuntime(Context context, ConfigSource config, AuditSink audit, boolean isStandalone) {
        this.context = context.getApplicationContext();
        this.config = Objects.requireNonNull(config, "config");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.diagnostics = LogcatSinks.diagnostics();
        this.decoder = new AndroidImageDecoder();
        this.engines = EngineLoader.onnx(this.context.getAssets(), config, decoder);
        this.clock = Clock.system();
        this.isStandalone = isStandalone;
    }

    /**
     * Installs the runtime of the app (called by the Dagger module).
     *
     * @param context any context
     * @param config  ARC's liveness configuration
     * @param audit   ARC's audit sink
     * @return the installed runtime
     */
    public static synchronized LivenessRuntime install(Context context, ConfigSource config, AuditSink audit) {
        if (installed == null) {
            installed = new LivenessRuntime(context, config, audit, false);
        }
        return installed;
    }

    /**
     * Creates a runtime without installing it (tests and tools).
     *
     * @param context any context
     * @param config  configuration
     * @param audit   audit sink
     * @return a new runtime
     */
    static LivenessRuntime create(Context context, ConfigSource config, AuditSink audit) {
        return new LivenessRuntime(context, config, audit, false);
    }

    /**
     * Returns the installed runtime, or a standalone one when ARC has not installed it in this process.
     *
     * @param context any context
     * @return the runtime
     */
    public static LivenessRuntime get(Context context) {
        LivenessRuntime r = installed;
        if (r != null) {
            return r;
        }
        synchronized (LivenessRuntime.class) {
            if (installed != null) {
                return installed;
            }
            if (standalone == null) {
                Log.w(LogcatSinks.TAG, "Liveness runs without ARC's configuration and audit database");
                standalone = new LivenessRuntime(context, ConfigSource.empty(), LogcatSinks.audit(), true);
            }
            return standalone;
        }
    }

    /**
     * Resolves the policy of a workflow and audits configuration changes ({@code REG-EVT-LV15}) since the last
     * resolution, and values that were corrected to their floors or ranges.
     *
     * @param workflow the workflow
     * @return the resolution
     */
    public PolicyResolution resolve(Workflow workflow) {
        PolicyResolution resolution = new PolicyResolver(config).resolve(workflow);
        if (config instanceof GlobalParamConfigSource) {
            List<String> changed = ((GlobalParamConfigSource) config).changedKeysSinceLastCheck();
            if (!changed.isEmpty()) {
                audit.emit(new AuditPayloadBuilder(workflow, "", clock).configApplied(String.join(",", changed)));
            }
        }
        resolution.configAppliedEvent(clock.now()).ifPresent(audit::emit);
        return resolution;
    }

    /**
     * Tells whether this device must not run liveness: the key {@code android.block_emulator} is {@code Y} and
     * the app runs on an emulator.
     *
     * @return {@code true} if blocked
     */
    public boolean isBlockedEmulator() {
        boolean block = LivenessConfigKeys.YES.equalsIgnoreCase(config.get(LivenessConfigKeys.ANDROID_BLOCK_EMULATOR)
                .orElse(LivenessConfigKeys.DEFAULT_ANDROID_BLOCK_EMULATOR));
        return block && isEmulator();
    }

    /**
     * Returns the stable serial number of the built-in face camera of this installation.
     *
     * @return a random identifier created on first use
     */
    public synchronized String deviceSerial() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String serial = prefs.getString(PREF_SERIAL, null);
        if (serial == null) {
            serial = "BUILTIN-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(Locale.ROOT);
            prefs.edit().putString(PREF_SERIAL, serial).apply();
        }
        return serial;
    }

    /**
     * Returns the built-in device key (Android Keystore; see {@link AndroidKeystoreSigner} for the trust model).
     *
     * @return the signer
     */
    public DeviceSigner deviceSigner() {
        DeviceSigner s = signer;
        if (s == null) {
            synchronized (this) {
                if (signer == null) {
                    signer = new AndroidKeystoreSigner(new File(context.getFilesDir(), "liveness"), deviceSerial());
                }
                s = signer;
            }
        }
        return s;
    }

    /**
     * Returns the configuration.
     *
     * @return the configuration source
     */
    public ConfigSource config() {
        return config;
    }

    /**
     * Returns the audit sink.
     *
     * @return the sink
     */
    public AuditSink audit() {
        return audit;
    }

    /**
     * Returns the diagnostic sink (Logcat, tag {@code Liveness}).
     *
     * @return the sink
     */
    public DiagnosticSink diagnostics() {
        return diagnostics;
    }

    /**
     * Returns the image decoder.
     *
     * @return the decoder
     */
    public AndroidImageDecoder decoder() {
        return decoder;
    }

    /**
     * Returns the engine loader.
     *
     * @return the loader
     */
    public EngineLoader engines() {
        return engines;
    }

    /**
     * Returns the clock.
     *
     * @return the system clock
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Tells whether this runtime runs without ARC's configuration and database.
     *
     * @return {@code true} for a standalone debug launch
     */
    public boolean isStandalone() {
        return isStandalone;
    }

    private static boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE;
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator")
                || hardware.contains("goldfish") || hardware.contains("ranchu")
                || (Build.PRODUCT != null && Build.PRODUCT.contains("sdk"));
    }
}
