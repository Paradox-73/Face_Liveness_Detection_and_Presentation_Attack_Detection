/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

/**
 * The five face landmarks a face detector reports, in this fixed order (the YuNet output order).
 *
 * <p>Left and right are the subject's own. In an image that is not mirrored, the subject's right eye appears
 * on the left side of the image. Adapters must map their detector's output to this order.
 */
public enum Landmark5 {

    /** Centre of the subject's right eye. */
    RIGHT_EYE,
    /** Centre of the subject's left eye. */
    LEFT_EYE,
    /** Tip of the nose. */
    NOSE_TIP,
    /** Right corner of the subject's mouth. */
    MOUTH_RIGHT,
    /** Left corner of the subject's mouth. */
    MOUTH_LEFT
}
