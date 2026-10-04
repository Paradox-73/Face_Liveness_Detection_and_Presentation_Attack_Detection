/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.policy.ConfigIssue;
import io.mosip.liveness.core.policy.PolicyResolution;
import io.mosip.liveness.core.policy.PolicyResolver;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Runs scenarios as transactions: for each seed, attempts follow the policy's retry rules
 * ({@link LivenessTransaction}) until one passes or the attempts are used up. Writes one JSON line per attempt and
 * collects the summary table.
 */
final class ScenarioRunner {

    /** Extra sessions allowed beyond {@code max_retries}, for endings that do not count as an attempt. */
    static final int EXTRA_SESSIONS = 2;

    private final EngineParts engines;
    private final FramePackStore store;
    private final Map<String, String> globalConfig;
    private final Map<String, String> globalEngineParams;
    private final AttemptRecord.RunInfo run;
    private final PrintStream log;
    private final boolean trackLowLight;

    /** Supplies an engine and its timer for a set of engine parameter overrides. */
    interface EngineParts {
        /**
         * Builds an engine.
         *
         * @param overrides detection parameter overrides ({@code EngineParameter} keys) on top of the shipped and
         *                  signed values
         * @return the driver for that engine
         * @throws IllegalArgumentException if a key is unknown or a value is out of range
         */
        AttemptDriver driver(Map<String, String> overrides);
    }

    /**
     * Creates a runner.
     *
     * @param engines            engine source
     * @param store              frame packs
     * @param globalConfig       configuration overrides applied before each scenario's own (full key names)
     * @param globalEngineParams engine parameter overrides applied before each scenario's own
     * @param run                facts written into every record
     * @param log                progress output
     */
    ScenarioRunner(EngineParts engines, FramePackStore store, Map<String, String> globalConfig,
                   Map<String, String> globalEngineParams, AttemptRecord.RunInfo run, PrintStream log) {
        this(engines, store, globalConfig, globalEngineParams, run, log, true);
    }

    /**
     * Creates a runner.
     *
     * @param engines            engine source
     * @param store              frame packs
     * @param globalConfig       configuration overrides applied before each scenario's own (full key names)
     * @param globalEngineParams engine parameter overrides applied before each scenario's own
     * @param run                facts written into every record
     * @param log                progress output
     * @param trackLowLight      {@code true} to give each session the transaction's low-light count, as the clients
     *                           do, so a dark face can end a session as {@code LOW_LIGHT} without using up an
     *                           attempt; {@code false} reproduces runs made before that was tracked
     */
    ScenarioRunner(EngineParts engines, FramePackStore store, Map<String, String> globalConfig,
                   Map<String, String> globalEngineParams, AttemptRecord.RunInfo run, PrintStream log,
                   boolean trackLowLight) {
        this.trackLowLight = trackLowLight;
        this.engines = Objects.requireNonNull(engines, "engines");
        this.store = Objects.requireNonNull(store, "store");
        this.globalConfig = globalConfig;
        this.globalEngineParams = globalEngineParams;
        this.run = run;
        this.log = log;
    }

    /**
     * Runs every seed of a scenario.
     *
     * @param sc      the scenario
     * @param jsonl   receives one line per attempt
     * @param summary receives one row per transaction
     * @throws IOException     if writing fails
     * @throws EngineException if the engine refuses a session
     */
    void run(Scenario sc, Writer jsonl, SummaryTable summary) throws IOException, EngineException {
        for (String packId : sc.stream().packIds()) {
            store.pack(packId);
        }
        Map<String, String> config = effectiveConfig(sc);
        Map<String, String> params = new TreeMap<>(globalEngineParams);
        params.putAll(sc.engineParams());
        PolicyResolution resolution = new PolicyResolver(ConfigSource.of(config)).resolve(sc.workflow());
        for (ConfigIssue issue : resolution.issues()) {
            log.println("  config issue in " + sc.id() + ": " + issue.describe());
        }
        LivenessPolicy policy = resolution.policy();
        AttemptDriver driver = engines.driver(params);
        String configHash = EngineFactory.sha256Hex(new TreeMap<>(config).toString()).substring(0, 16);
        String paramsHash = EngineFactory.sha256Hex(params.toString()).substring(0, 16);
        for (long seed : sc.seeds()) {
            runTransaction(sc, policy, driver, seed, configHash, paramsHash, jsonl, summary);
        }
    }

    private void runTransaction(Scenario sc, LivenessPolicy policy, AttemptDriver driver,
                                long seed, String configHash, String paramsHash, Writer jsonl, SummaryTable summary)
            throws IOException, EngineException {
        String txnId = sc.id() + "#" + seed;
        LivenessTransaction txn = new LivenessTransaction(policy);
        SeededRandomSource random = new SeededRandomSource(seed);
        List<AttemptResult> attempts = new ArrayList<>();
        int maxSessions = policy.maxRetries() + EXTRA_SESSIONS;
        int lowLightEndings = 0;
        // low-light endings do not use up the session budget: the engine ends at most
        // passive.low_light_max_uncounted of them between two counted failures
        while (txn.status() == LivenessTransaction.Status.IN_PROGRESS
                && attempts.size() - lowLightEndings < maxSessions) {
            int attemptNo = txn.nextAttempt();
            Playlist playlist = new Playlist(sc.stream(), store::pack);
            AttemptResult a = driver.run(policy, attemptNo, random, playlist, sc.fps(), sc.maxStreamMs(),
                    "EVAL-" + sc.subject(),
                    trackLowLight ? txn.lowLightRetries() : SessionRequest.LOW_LIGHT_NOT_TRACKED);
            txn.record(a.result());
            if (a.result().isLowLightRetry()) {
                lowLightEndings++;
            }
            attempts.add(a);
            summary.addTimings(a);
        }
        List<Outcome> outcomes = new ArrayList<>();
        List<String> layers = new ArrayList<>();
        List<String> passive = new ArrayList<>();
        boolean layerOk = true;
        for (AttemptResult a : attempts) {
            Outcome o = Outcome.of(a.result());
            Layer l = Layer.of(a.result());
            outcomes.add(o);
            layers.add(l.id());
            passive.add(a.result().passiveScore().isPresent() ? String.valueOf(a.result().passiveScore().getAsInt())
                    : "-");
            if (o != Outcome.ACCEPT && !sc.expect().layers().isEmpty() && !sc.expect().layers().contains(l)) {
                layerOk = false;
            }
        }
        Outcome txnOutcome = Outcome.ofTransaction(outcomes);
        for (int i = 0; i < attempts.size(); i++) {
            AttemptRecord.TxnInfo info = new AttemptRecord.TxnInfo(txnId, seed, i + 1, policy.maxRetries(),
                    configHash, paramsHash);
            Map<String, Object> rec = AttemptRecord.of(run, sc, info, attempts.get(i),
                    i == attempts.size() - 1 ? txnOutcome : null);
            jsonl.write(Json.write(rec));
            jsonl.write('\n');
        }
        jsonl.flush();
        AttemptResult last = attempts.get(attempts.size() - 1);
        List<String> reasons = new ArrayList<>();
        for (Enum<?> c : last.result().reasonCodes()) {
            reasons.add(c.name());
        }
        SummaryTable.Row row = new SummaryTable.Row(sc.id(), sc.kind(), sc.species(), seed, outcomes, layers, passive,
                String.join("+", reasons), sc.expect().outcome(), layerOk);
        summary.add(row);
        log.printf("  %-44s seed %-4d %-11s %s layers=%s passive=%s %s%n", sc.id(), seed, txnOutcome,
                row.ok() ? "ok" : "MISMATCH(expected " + sc.expect().outcome() + " " + sc.expect().layers() + ")",
                layers, passive, String.join("+", reasons));
    }

    /**
     * Builds the configuration a scenario runs with: the shipped defaults, the runner's global overrides, the
     * scenario's own overrides, and diagnostics switched on so the runner can read the planarity residual.
     *
     * @param sc the scenario
     * @return full key to value
     */
    Map<String, String> effectiveConfig(Scenario sc) {
        Map<String, String> config = new TreeMap<>(LivenessConfigKeys.defaultValues());
        config.putAll(globalConfig);
        config.putAll(sc.config());
        config.put(LivenessConfigKeys.DIAGNOSTIC_ENABLE, LivenessConfigKeys.YES);
        return config;
    }
}
