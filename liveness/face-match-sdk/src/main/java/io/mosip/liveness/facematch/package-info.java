/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * MOSIP biometric SDK for FACE on SFace: {@link io.mosip.liveness.facematch.SFaceBioApi} ({@code IBioApiV2}) and
 * the platform-neutral {@link io.mosip.liveness.facematch.SFaceMatcher} behind it.
 *
 * <p>Only {@code SFaceBioApi} and its package-private helpers use MOSIP types; the matcher, template format,
 * quality score and decoder interface depend only on the liveness modules and the JDK, so the same code runs in
 * the desktop and the Android Registration Client.
 */
package io.mosip.liveness.facematch;
