package io.mosip.liveness.onnx.image;

import java.util.Objects;

/**
 * How pixels become model input values: the plane order and the normalisation
 * {@code value = (pixel - mean) * scale}, applied to every channel. Tensors are written planar (CHW).
 *
 * <p>The constants are the exact preprocessing of each bundled model. Getting one wrong does not fail loudly:
 * MiniFASNet fed {@code /255} values calls a live face an attack, so unit tests pin these values.
 */
public final class TensorFormat {

    /**
     * BGR, raw 0-255 values: YuNet and MiniFASNet (the upstream MiniFASNet {@code to_tensor} does not divide by
     * 255).
     */
    public static final TensorFormat BGR_RAW = new TensorFormat(ChannelOrder.BGR, 0f, 1f);

    /** RGB, raw 0-255 values: SFace (OpenCV {@code FaceRecognizerSF} uses {@code swapRB=true}, scale 1). */
    public static final TensorFormat RGB_RAW = new TensorFormat(ChannelOrder.RGB, 0f, 1f);

    /** RGB scaled to 0-1: MediaPipe Face Mesh V2. */
    public static final TensorFormat RGB_UNIT = new TensorFormat(ChannelOrder.RGB, 0f, 1f / 255f);

    private final ChannelOrder order;
    private final float mean;
    private final float scale;

    private TensorFormat(ChannelOrder order, float mean, float scale) {
        this.order = order;
        this.mean = mean;
        this.scale = scale;
    }

    /**
     * Creates a format.
     *
     * @param order plane order of the tensor
     * @param mean  value subtracted from every channel
     * @param scale factor applied after the subtraction; finite and not zero
     * @return the format
     * @throws IllegalArgumentException if a value is not finite or the scale is zero
     */
    public static TensorFormat of(ChannelOrder order, float mean, float scale) {
        Objects.requireNonNull(order, "order");
        if (Float.isNaN(mean) || Float.isInfinite(mean) || Float.isNaN(scale) || Float.isInfinite(scale)
                || scale == 0f) {
            throw new IllegalArgumentException("mean and scale must be finite and scale not zero");
        }
        return new TensorFormat(order, mean, scale);
    }

    /**
     * Returns the plane order.
     *
     * @return the order of the three planes
     */
    public ChannelOrder order() {
        return order;
    }

    /**
     * Returns the value subtracted from each channel.
     *
     * @return the mean
     */
    public float mean() {
        return mean;
    }

    /**
     * Returns the factor applied after the subtraction.
     *
     * @return the scale
     */
    public float scale() {
        return scale;
    }

    /**
     * Normalises one channel value.
     *
     * @param pixel channel value 0-255, possibly interpolated
     * @return the tensor value
     */
    public float normalize(float pixel) {
        return (pixel - mean) * scale;
    }

    @Override
    public String toString() {
        return "TensorFormat[" + order + ", mean=" + mean + ", scale=" + scale + "]";
    }
}
