/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * The signed model bundle: {@link io.mosip.liveness.onnx.model.ModelRegistry} verifies {@code manifest.jws}
 * (RS256, detached) against built-in trust anchors, blocks bundles older than {@code model.min_version}, and
 * releases model bytes only after their SHA-256 matches the manifest. Failures are fail-closed
 * {@link io.mosip.liveness.core.EngineException}s with model-integrity error codes.
 */
package io.mosip.liveness.onnx.model;
