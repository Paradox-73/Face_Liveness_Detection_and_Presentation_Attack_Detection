/**
 * ONNX Runtime implementations of the liveness core's model contracts:
 * {@link io.mosip.liveness.onnx.OnnxFaceAnalyzer} ({@link io.mosip.liveness.core.vision.FaceAnalyzer}: YuNet plus
 * Face Mesh V2), {@link io.mosip.liveness.onnx.pad.MiniFasPassivePad}
 * ({@link io.mosip.liveness.core.vision.PassivePad}: MiniFASNet V2 + V1SE) and
 * {@link io.mosip.liveness.onnx.embed.SFaceEmbedder} ({@link io.mosip.liveness.core.vision.FaceEmbedder}:
 * SFace). {@link io.mosip.liveness.onnx.OnnxModelSet} loads them from a verified
 * {@link io.mosip.liveness.onnx.model.ModelRegistry}.
 *
 * <p>The same jar runs on the desktop client (ONNX Runtime Java 1.20.0) and on Android
 * ({@code onnxruntime-android}); neither runtime is bundled. No {@code java.awt}, ImageIO or Android classes are
 * used.
 */
package io.mosip.liveness.onnx;
