package io.mosip.liveness.core.image;

import java.util.Arrays;
import java.util.Objects;

/**
 * A decoded, upright image in a raw {@link PixelFormat}, independent of any platform image class.
 *
 * <p>This is the input of every model adapter ({@link io.mosip.liveness.core.vision.FaceAnalyzer},
 * {@link io.mosip.liveness.core.vision.PassivePad}, {@link io.mosip.liveness.core.vision.FaceEmbedder}).
 * Rotation from the camera has already been applied; coordinates start at the top-left pixel.
 *
 * <p><b>Ownership of the pixel array.</b> For speed the array is not copied: the factory takes ownership of
 * the array it is given and {@link #bytes()} / {@link #ints()} return that same array. Producers must not reuse
 * the array after creating the buffer, and consumers must only read it, except that the owner of a session may
 * overwrite it with zeros when the session ends ({@link #wipe()}; design section 4.20). {@link #toString()}
 * never prints pixel data.
 */
public final class ImageBuffer {

    private final int width;
    private final int height;
    private final PixelFormat format;
    private final byte[] bytes;
    private final int[] ints;

    private ImageBuffer(int width, int height, PixelFormat format, byte[] bytes, int[] ints) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.bytes = bytes;
        this.ints = ints;
    }

    /**
     * Wraps byte pixels in a raw byte format.
     *
     * @param width  width in pixels; positive
     * @param height height in pixels; positive
     * @param format a raw, byte-backed format (not encoded, not {@link PixelFormat#ARGB8888_INT})
     * @param pixels tightly packed pixels of exactly {@code format.requiredLength(width, height)} bytes; not copied
     * @return the image buffer
     * @throws IllegalArgumentException if the format or the array length does not fit
     */
    public static ImageBuffer ofBytes(int width, int height, PixelFormat format, byte[] pixels) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(pixels, "pixels");
        if (format.isEncoded() || format.isIntPacked()) {
            throw new IllegalArgumentException("not a raw byte format: " + format);
        }
        checkLength(pixels.length, format.requiredLength(width, height));
        return new ImageBuffer(width, height, format, pixels, null);
    }

    /**
     * Wraps pixels packed as one {@code 0xAARRGGBB} int each ({@link PixelFormat#ARGB8888_INT}).
     *
     * @param width  width in pixels; positive
     * @param height height in pixels; positive
     * @param pixels exactly {@code width * height} ints, top row first; not copied
     * @return the image buffer
     * @throws IllegalArgumentException if the array length does not fit
     */
    public static ImageBuffer ofArgbInts(int width, int height, int[] pixels) {
        Objects.requireNonNull(pixels, "pixels");
        checkLength(pixels.length, PixelFormat.ARGB8888_INT.requiredLength(width, height));
        return new ImageBuffer(width, height, PixelFormat.ARGB8888_INT, null, pixels);
    }

    private static void checkLength(int actual, int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("pixel array length " + actual + " but expected " + expected);
        }
    }

    /**
     * Returns the width.
     *
     * @return width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height.
     *
     * @return height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the pixel format.
     *
     * @return a raw format
     */
    public PixelFormat format() {
        return format;
    }

    /**
     * Tells whether the pixels are held as ints ({@link PixelFormat#ARGB8888_INT}).
     *
     * @return {@code true} for an int-backed buffer
     */
    public boolean isIntBacked() {
        return ints != null;
    }

    /**
     * Returns the byte pixels (shared, not copied; read only).
     *
     * @return the byte array
     * @throws IllegalStateException if the buffer is int-backed
     */
    public byte[] bytes() {
        if (bytes == null) {
            throw new IllegalStateException("int-backed buffer; use ints()");
        }
        return bytes;
    }

    /**
     * Returns the int pixels (shared, not copied; read only).
     *
     * @return the int array
     * @throws IllegalStateException if the buffer is byte-backed
     */
    public int[] ints() {
        if (ints == null) {
            throw new IllegalStateException("byte-backed buffer; use bytes()");
        }
        return ints;
    }

    /**
     * Overwrites the pixel array with zeros. Called by the owner when the image is no longer needed, so face
     * pixels do not linger in memory after a session.
     */
    public void wipe() {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        } else {
            Arrays.fill(ints, 0);
        }
    }

    /**
     * Describes size and format only; pixel data is never included.
     *
     * @return a short description
     */
    @Override
    public String toString() {
        return "ImageBuffer[" + width + "x" + height + " " + format + "]";
    }
}
