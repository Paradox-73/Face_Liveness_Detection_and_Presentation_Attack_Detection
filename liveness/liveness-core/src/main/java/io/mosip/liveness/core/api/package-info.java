/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * The engine and session contract used by both clients.
 *
 * <p>A client finds a {@link io.mosip.liveness.core.api.LivenessEngine} through a
 * {@link io.mosip.liveness.core.api.LivenessEngineProvider}, opens a
 * {@link io.mosip.liveness.core.api.LivenessSession} per attempt with a
 * {@link io.mosip.liveness.core.api.SessionRequest}, feeds it stream frames, renders the UI states it reports to
 * its {@link io.mosip.liveness.core.api.LivenessListener}, starts the signed capture on {@code CAPTURE_READY},
 * binds the capture with {@link io.mosip.liveness.core.api.LivenessSession#bind}, and stores the
 * {@link io.mosip.liveness.core.api.LivenessResult} as packet evidence.
 */
package io.mosip.liveness.core.api;
