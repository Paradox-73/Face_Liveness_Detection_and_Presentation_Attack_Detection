/**
 * The signed model bundle: {@link io.mosip.liveness.onnx.model.ModelRegistry} verifies {@code manifest.jws}
 * (RS256, detached) against built-in trust anchors, blocks bundles older than {@code model.min_version}, and
 * releases model bytes only after their SHA-256 matches the manifest. Failures are fail-closed
 * {@link io.mosip.liveness.core.EngineException}s with model-integrity error codes.
 */
package io.mosip.liveness.onnx.model;
