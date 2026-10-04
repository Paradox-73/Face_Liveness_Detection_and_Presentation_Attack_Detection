/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.match;

import java.util.List;

/**
 * One-to-one face verification: is the face that just passed liveness the face enrolled for this user?
 *
 * <p>Runs on the device, so operator and supervisor face authentication work offline. Implementations must not
 * log or keep image data.
 */
public interface FaceVerifier {

    /**
     * Compares a probe face with the user's enrolled faces.
     *
     * @param probe   the probe: an ISO/IEC 19794-5 face record (what the built-in device signs) or a plain image
     * @param gallery the enrolled faces of one user, in the same formats; may be empty
     * @return the best comparison over the gallery
     */
    FaceMatch verify(byte[] probe, List<byte[]> gallery);
}
