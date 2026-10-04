/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.ui;

import io.mosip.liveness.core.ChallengeType;

/**
 * Icon shown with a UI state. Head-turn arrows show the physical direction and are not mirrored in
 * right-to-left layouts.
 */
public enum PromptIcon {

    /** No icon. */
    NONE,
    /** Face oval guide (positioning). */
    FACE_OVAL,
    /** Progress indicator (checking, verifying). */
    PROGRESS,
    /** Blink prompt. */
    BLINK,
    /** Smile prompt. */
    SMILE,
    /** Arrow for turning the head to the subject's left. */
    TURN_LEFT,
    /** Arrow for turning the head to the subject's right. */
    TURN_RIGHT,
    /** Arrow for looking up. */
    LOOK_UP,
    /** Arrow for looking down. */
    LOOK_DOWN,
    /** Eyes looking to the subject's left. */
    GAZE_LEFT,
    /** Eyes looking to the subject's right. */
    GAZE_RIGHT,
    /** Check mark (action detected, success). */
    CHECK,
    /** Hold-still symbol. */
    HOLD_STILL,
    /** Warning symbol (guidance, retry). */
    WARNING,
    /** Error symbol (failure). */
    ERROR,
    /** Camera with a strike-through (device problems). */
    CAMERA_OFF,
    /** Lock (no attempts left). */
    LOCKED;

    /**
     * Returns the prompt icon of a challenge.
     *
     * @param type the challenge
     * @return the icon with the same name
     */
    public static PromptIcon forChallenge(ChallengeType type) {
        return valueOf(type.name());
    }
}
