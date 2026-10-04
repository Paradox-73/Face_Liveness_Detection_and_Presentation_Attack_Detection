/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.ui;

/**
 * How a UI state is styled. Each client maps it to text, icon and colour together, never colour alone.
 */
public enum Severity {

    /** Neutral progress or instruction. */
    INFO,
    /** Positive feedback (action detected, success). */
    SUCCESS,
    /** Guidance the person can act on (no face, poor lighting, retry available). */
    WARNING,
    /** A failure that ends the attempt. */
    ERROR
}
