/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.engine.LivenessMetrics;
import io.mosip.liveness.core.policy.PolicyResolution;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.livenessmanager.audit.DiagnosticModeTracker;
import io.mosip.registration.livenessmanager.audit.LogcatSinks;
import io.mosip.registration.livenessmanager.config.GlobalParamConfigSource;
import io.mosip.registration.livenessmanager.device.AndroidKeystoreSigner;
import io.mosip.registration.livenessmanager.device.DeviceSigner;
import io.mosip.registration.livenessmanager.engine.AcceleratorCustomizer;
import io.mosip.registration.livenessmanager.engine.AssetModelSource;
import io.mosip.registration.livenessmanager.engine.EngineLoader;
import io.mosip.registration.livenessmanager.engine.ModelBundleImporter;
import io.mosip.registration.livenessmanager.engine.ModelBundleStore;
import io.mosip.registration.livenessmanager.image.AndroidImageDecoder;
import io.mosip.registration.livenessmanager.metrics.MetricsLog;
import io.mosip.registration.livenessmanager.metrics.MetricsReport;

/**
 * The process-wide pieces the liveness screen and service share: configuration, audit and diagnostic sinks,
 * image decoder, engine loader, store and import of model bundles, device identity and key, and the anonymised
 * metrics of all sessions.
 *
 * <p>Inside ARC the Dagger {@code LivenessModule} installs it with ARC's configuration and audit service when the
 * app starts. When the capture screen is started on its own (a debug launch from adb before ARC was opened), a
 * standalone runtime is used instead: shipped default configuration and audits written to Logcat only.
 */
public final class LivenessRuntime {

    private static final String PREFS = "io.mosip.registration.livenessmanager";
    private static final String PREF_SERIAL = "device.serial";
    private static final String PREF_DIAGNOSTIC = "diagnostic.last";
    private static final String METRICS_TAG = "LivenessMetrics";
    private static volatile LivenessRuntime installed;
    private static volatile LivenessRuntime standalone;

    private final Context context;
    private final ConfigSource config;
    private final AuditSink audit;
    private final DiagnosticSink diagnostics;
    private final AndroidImageDecoder decoder;
    private final EngineLoader engines;
    private final ModelBundleStore modelStore;
    private final ModelBundleImporter modelImporter;
    private final LivenessMetrics metrics;
    private final MetricsLog metricsLog;
    private final DiagnosticModeTracker diagnosticMode;
    private final Clock clock;
    private final boolean isStandalone;
    private volatile Supplier<String> userId = () -> "";
    private volatile Function<String, List<String>> roles = id -> Collections.emptyList();
    private volatile DeviceSigner signer;

    private LivenessRuntime(Context context, ConfigSource config, AuditSink audit, boolean isStandalone) {
        this.context = context.getApplicationContext();
        this.config = Objects.requireNonNull(config, "config");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.diagnostics = LogcatSinks.diagnostics();
        this.decoder = new AndroidImageDecoder();
        this.metrics = new LivenessMetrics();
        this.modelStore = new ModelBundleStore(new File(new File(this.context.getFilesDir(), "liveness"),
                "model-store").toPath());
        this.engines = EngineLoader.fromConfig(this.context.getAssets(), config, decoder, metrics,
                AcceleratorCustomizer.ortProviders(), modelStore);
        this.clock = Clock.system();
        this.modelImporter = new ModelBundleImporter(config, modelStore,
                () -> new AssetModelSource(this.context.getAssets()), ModelBundleImporter.over(engines), audit, clock);
        this.isStandalone = isStandalone;
        this.metricsLog = new MetricsLog(new File(new File(this.context.getFilesDir(), "liveness"), "metrics.log"),
                MetricsLog.DEFAULT_MAX_BYTES, e -> Log.w(METRICS_TAG, "Metrics not written: " + e.getMessage()));
        SharedPreferences prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.diagnosticMode = new DiagnosticModeTracker(new DiagnosticModeTracker.Store() {
            @Override
            public String load() {
                return prefs.getString(PREF_DIAGNOSTIC, null);
            }

            @Override
            public void save(String value) {
                prefs.edit().putString(PREF_DIAGNOSTIC, value).apply();
            }
        }, audit, clock, () -> userId.get());
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
        if (!isStandalone) {
            diagnosticMode.observe(workflow, resolution.policy().isDiagnosticEnabled());
        }
        return resolution;
    }

    /**
     * Sets who is signed in, for audit {@code REG-EVT-LV17} (diagnostic mode changed).
     *
     * @param userId returns the signed-in user's ID, or blank
     */
    public void setUserIdSupplier(Supplier<String> userId) {
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    /**
     * Sets how the roles of a user are looked up (ARC's login service), for the model import.
     *
     * @param roles returns the role codes of a user ID; empty for an unknown user
     */
    public void setRolesLookup(Function<String, List<String>> roles) {
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    /**
     * Returns the roles of a user.
     *
     * @param userId user ID
     * @return role codes, for example {@code REGISTRATION_SUPERVISOR}; empty when unknown
     */
    public List<String> rolesOf(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> r = roles.apply(userId.trim());
        return r == null ? Collections.emptyList() : r;
    }

    /**
     * Returns the importer of signed model bundles (secure offline model update).
     *
     * @return the importer over this runtime's engine loader and store
     */
    public ModelBundleImporter modelImporter() {
        return modelImporter;
    }

    /**
     * Returns the signed-in user's ID as ARC last reported it.
     *
     * @return the user ID, or an empty string
     */
    public String currentUserId() {
        String id = userId.get();
        return id == null ? "" : id.trim();
    }

    /**
     * Returns the anonymised metrics of every session in this process (counts, times, retry and failure rates).
     *
     * @return the shared collector
     */
    public LivenessMetrics metrics() {
        return metrics;
    }

    /**
     * Writes the current metrics as one line to {@code files/liveness/metrics.log} and to Logcat (tag
     * {@code LivenessMetrics}), unless {@code metrics.enable} is {@code N}. Called after every attempt; returns at
     * once (the file is written on a background thread).
     *
     * @param enabled the policy's {@code metrics.enable}
     */
    public void publishMetrics(boolean enabled) {
        if (!enabled) {
            return;
        }
        String line = MetricsReport.line(metrics.snapshot(), clock.now());
        Log.i(METRICS_TAG, line);
        metricsLog.append(line);
    }

    /**
     * Returns the metrics log.
     *
     * @return the log writer
     */
    public MetricsLog metricsLog() {
        return metricsLog;
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
