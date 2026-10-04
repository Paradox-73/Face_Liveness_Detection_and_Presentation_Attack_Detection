/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * The default liveness engine: the session state machine and the algorithms behind it, all platform-neutral.
 *
 * <p>Public entry points:
 * <ul>
 *   <li>{@link io.mosip.liveness.core.engine.DefaultLivenessEngine} - the engine; sessions implement
 *       {@link io.mosip.liveness.core.api.LivenessSession};</li>
 *   <li>{@link io.mosip.liveness.core.engine.EngineParameters} - detection thresholds from the signed model bundle;</li>
 *   <li>{@link io.mosip.liveness.core.engine.LivenessTransaction} - retry counting per workflow and subject;</li>
 *   <li>{@link io.mosip.liveness.core.engine.LivenessUiStateMapper} - message keys and UI states;</li>
 *   <li>{@link io.mosip.liveness.core.engine.AuditPayloadBuilder},
 *       {@link io.mosip.liveness.core.engine.LivenessMetrics} and
 *       {@link io.mosip.liveness.core.engine.DiagnosticLog} - audit, metrics and diagnostics.</li>
 * </ul>
 * The algorithms (quality gate, passive PAD aggregation, decision policy, challenge scheduler, action detectors,
 * frame integrity, planarity, identity tracking and capture binding) are package-private: in particular no API
 * lets a caller choose a challenge.
 */
package io.mosip.liveness.core.engine;
