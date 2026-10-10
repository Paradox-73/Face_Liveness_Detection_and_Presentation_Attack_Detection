/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Secure offline model update (bonus B6), shared by the desktop and Android clients:
 * {@link io.mosip.liveness.onnx.bundle.ModelBundleImporter} verifies a signed bundle from a folder or {@code .zip}
 * and switches the engine to it, {@link io.mosip.liveness.onnx.bundle.ModelBundleStore} keeps the imported bundles
 * and the anti-rollback floor. The only package of this module that uses {@code java.nio.file}: both clients
 * import from the local file system, the engine itself never touches files.
 */
package io.mosip.liveness.onnx.bundle;
