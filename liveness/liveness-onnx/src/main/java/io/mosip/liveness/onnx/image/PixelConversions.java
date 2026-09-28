package io.mosip.liveness.onnx.image;

/**
 * Conversions from the raw {@link io.mosip.liveness.core.image.PixelFormat}s to packed RGB bytes.
 */
final class PixelConversions {

    private PixelConversions() {
    }

    static byte[] rgbaToRgb(byte[] rgba, int pixels) {
        byte[] out = new byte[pixels * 3];
        for (int i = 0, o = 0, s = 0; i < pixels; i++, o += 3, s += 4) {
            out[o] = rgba[s];
            out[o + 1] = rgba[s + 1];
            out[o + 2] = rgba[s + 2];
        }
        return out;
    }

    static byte[] grayToRgb(byte[] gray, int pixels) {
        byte[] out = new byte[pixels * 3];
        for (int i = 0, o = 0; i < pixels; i++, o += 3) {
            byte g = gray[i];
            out[o] = g;
            out[o + 1] = g;
            out[o + 2] = g;
        }
        return out;
    }

    static byte[] argbToRgb(int[] argb, int pixels) {
        byte[] out = new byte[pixels * 3];
        for (int i = 0, o = 0; i < pixels; i++, o += 3) {
            int p = argb[i];
            out[o] = (byte) (p >> 16);
            out[o + 1] = (byte) (p >> 8);
            out[o + 2] = (byte) p;
        }
        return out;
    }

    /**
     * NV21 (Y plane, then interleaved V and U at half resolution) to RGB with the ITU-R BT.601 limited-range
     * integer coefficients used by Android camera pipelines.
     */
    static byte[] nv21ToRgb(byte[] nv21, int width, int height) {
        byte[] out = new byte[width * height * 3];
        int frame = width * height;
        for (int y = 0; y < height; y++) {
            int uvRow = frame + (y >> 1) * width;
            for (int x = 0, o = y * width * 3; x < width; x++, o += 3) {
                int c = (nv21[y * width + x] & 0xff) - 16;
                int uv = uvRow + (x & ~1);
                int v = (nv21[uv] & 0xff) - 128;
                int u = (nv21[uv + 1] & 0xff) - 128;
                int c298 = 298 * c;
                out[o] = clamp((c298 + 409 * v + 128) >> 8);
                out[o + 1] = clamp((c298 - 100 * u - 208 * v + 128) >> 8);
                out[o + 2] = clamp((c298 + 516 * u + 128) >> 8);
            }
        }
        return out;
    }

    static byte[] swapRedBlue(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i += 3) {
            out[i] = data[i + 2];
            out[i + 1] = data[i + 1];
            out[i + 2] = data[i];
        }
        return out;
    }

    static byte clamp(int v) {
        return (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
    }
}
