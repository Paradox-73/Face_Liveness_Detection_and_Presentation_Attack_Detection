/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Pure-Java reader of ISO/IEC 19794-5 face records ({@link io.mosip.liveness.facematch.iso.FaceIsoRecord}). It
 * finds the encoded face image and its header fields; decoding the image is left to the platform. No dependency
 * outside the JDK, so it runs on the desktop and on Android.
 */
package io.mosip.liveness.facematch.iso;
