package io.mosip.liveness.onnx.image;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;

import java.util.Objects;

/**
 * An image with 3 bytes per pixel, rows packed top first, in {@link ChannelOrder#RGB} or
 * {@link ChannelOrder#BGR} order. This is the working format of every model adapter.
 *
 * <p>{@link #of(ImageBuffer)} wraps RGB888 and BGR888 buffers without copying; other raw formats are converted
 * once. As with {@link ImageBuffer}, the array is shared, not copied, and must only be read.
 * {@link #toString()} never prints pixel data.
 */
public final class Rgb8Image {

    private final int width;
    private final int height;
    private final byte[] data;
    private final ChannelOrder order;

    private Rgb8Image(int width, int height, byte[] data, ChannelOrder order) {
        this.width = width;
        this.height = height;
        this.data = data;
        this.order = order;
    }

    /**
     * Wraps packed pixels.
     *
     * @param width  width in pixels; positive
     * @param height height in pixels; positive
     * @param data   exactly {@code width * height * 3} bytes; not copied
     * @param order  byte order within a pixel
     * @return the image
     * @throws IllegalArgumentException if the size or array length is wrong
     */
    public static Rgb8Image wrap(int width, int height, byte[] data, ChannelOrder order) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(order, "order");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        if (data.length != Math.multiplyExact(Math.multiplyExact(width, height), 3)) {
            throw new IllegalArgumentException("expected " + (width * height * 3) + " bytes, got " + data.length);
        }
        return new Rgb8Image(width, height, data, order);
    }

    /**
     * Converts any raw {@link ImageBuffer} to a 3-byte image. RGB888 and BGR888 are wrapped without copying;
     * RGBA8888, GRAY8, NV21 and ARGB8888_INT are converted (NV21 with ITU-R BT.601 limited-range coefficients,
     * the Android camera convention).
     *
     * @param image a decoded image
     * @return the image in RGB or BGR order
     * @throws IllegalArgumentException if the image is still encoded
     */
    public static Rgb8Image of(ImageBuffer image) {
        Objects.requireNonNull(image, "image");
        int w = image.width();
        int h = image.height();
        PixelFormat format = image.format();
        switch (format) {
            case RGB888:
                return new Rgb8Image(w, h, image.bytes(), ChannelOrder.RGB);
            case BGR888:
                return new Rgb8Image(w, h, image.bytes(), ChannelOrder.BGR);
            case RGBA8888:
                return new Rgb8Image(w, h, PixelConversions.rgbaToRgb(image.bytes(), w * h), ChannelOrder.RGB);
            case GRAY8:
                return new Rgb8Image(w, h, PixelConversions.grayToRgb(image.bytes(), w * h), ChannelOrder.RGB);
            case NV21:
                return new Rgb8Image(w, h, PixelConversions.nv21ToRgb(image.bytes(), w, h), ChannelOrder.RGB);
            case ARGB8888_INT:
                return new Rgb8Image(w, h, PixelConversions.argbToRgb(image.ints(), w * h), ChannelOrder.RGB);
            default:
                throw new IllegalArgumentException("decode the image first; not a raw format: " + format);
        }
    }

    /**
     * Returns the width.
     *
     * @return pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height.
     *
     * @return pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the packed pixels (shared, not copied; read only).
     *
     * @return {@code width * height * 3} bytes
     */
    public byte[] data() {
        return data;
    }

    /**
     * Returns the byte order within a pixel.
     *
     * @return RGB or BGR
     */
    public ChannelOrder order() {
        return order;
    }

    /**
     * Wraps this image as an {@link ImageBuffer} (RGB888 or BGR888) without copying.
     *
     * @return the image buffer sharing this image's array
     */
    public ImageBuffer toImageBuffer() {
        return ImageBuffer.ofBytes(width, height, order == ChannelOrder.RGB ? PixelFormat.RGB888 : PixelFormat.BGR888,
                data);
    }

    /**
     * Describes size and order only; pixel data is never included.
     *
     * @return a short description
     */
    @Override
    public String toString() {
        return "Rgb8Image[" + width + "x" + height + " " + order + "]";
    }
}
