/**
 * Pure-Java image handling for the model adapters: {@link io.mosip.liveness.onnx.image.Rgb8Image} (3-byte
 * working image), {@link io.mosip.liveness.onnx.image.ImageOps} (bilinear resize, clamped crop, affine warp,
 * grey conversion, tensor normalisation), {@link io.mosip.liveness.onnx.image.AffineTransform} (including the
 * five-point similarity estimate) and {@link io.mosip.liveness.onnx.image.TensorFormat} (the pinned
 * preprocessing of each model). No {@code java.awt} or Android classes.
 */
package io.mosip.liveness.onnx.image;
