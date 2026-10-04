/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * The attempt loop both registration clients run around a liveness session: {@link
 * io.mosip.liveness.core.run.LivenessSessionDriver} opens the sessions of one transaction, moves stream frames into
 * them on one worker thread, hands the capture over to the client, records each result in the transaction and
 * reports an {@link io.mosip.liveness.core.run.AttemptOutcome} per attempt.
 *
 * <p>Like the rest of the core this package has no runtime dependencies and no platform classes; the clients supply
 * the executor, the listener dispatch, the clock and the signed capture through hooks.
 */
package io.mosip.liveness.core.run;
