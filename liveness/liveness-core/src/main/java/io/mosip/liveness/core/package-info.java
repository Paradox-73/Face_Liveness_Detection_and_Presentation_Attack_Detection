/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Shared vocabulary of the MOSIP face liveness engine: workflows, challenge types, session states, outcomes,
 * reason codes, and the clock and randomness abstractions.
 *
 * <p>The whole {@code io.mosip.liveness.core} tree has no runtime dependencies and uses no JavaFX, Android,
 * AWT, ImageIO or networking classes, so the same jar runs in the desktop Registration Client (Java 11) and
 * the Android Registration Client (API 28). Sub-packages:
 * <ul>
 *   <li>{@code api} - the engine and session contract used by both clients;</li>
 *   <li>{@code device} - the face capture device adapter contract (SBI devices, built-in camera, replay);</li>
 *   <li>{@code image} - platform-neutral pixel buffers and the image decoder contract;</li>
 *   <li>{@code vision} - face analysis, passive PAD and face embedding contracts implemented by model adapters;</li>
 *   <li>{@code config} - configuration keys, defaults and the typed per-workflow policy;</li>
 *   <li>{@code audit} - audit event identifiers, audit events and diagnostic records;</li>
 *   <li>{@code ui} - the UI state both clients render and the message keys.</li>
 * </ul>
 */
package io.mosip.liveness.core;
