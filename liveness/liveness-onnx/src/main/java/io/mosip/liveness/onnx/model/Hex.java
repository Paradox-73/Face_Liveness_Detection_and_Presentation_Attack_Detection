package io.mosip.liveness.onnx.model;

/**
 * Lower-case hexadecimal encoding.
 */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    /**
     * Encodes bytes as lower-case hex.
     *
     * @param data bytes
     * @return two hex digits per byte
     */
    public static String encode(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[2 * i] = DIGITS[(data[i] >> 4) & 0xf];
            out[2 * i + 1] = DIGITS[data[i] & 0xf];
        }
        return new String(out);
    }

    /**
     * Returns the SHA-256 of some bytes as hex.
     *
     * @param data bytes
     * @return 64 lower-case hex digits
     */
    public static String sha256(byte[] data) {
        return encode(ManifestSignature.sha256(data));
    }
}
