/**
 * Thin layer over ONNX Runtime ({@code ai.onnxruntime}): {@link io.mosip.liveness.onnx.runtime.OrtModel} creates
 * a session from verified model bytes and runs it on float tensors;
 * {@link io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions} sets threads and lets each client add execution
 * providers. Only API present in both the desktop runtime (1.20.0) and {@code onnxruntime-android} is used.
 */
package io.mosip.liveness.onnx.runtime;
