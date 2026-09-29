package io.mosip.liveness.facematch;

import io.mosip.liveness.core.vision.FaceEmbedding;

import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The face template this SDK writes into the BIR {@code BDB} from {@code extractTemplate}: one SFace embedding.
 *
 * <p>Binary layout, big-endian (539 bytes for SFace):
 * <table>
 *   <caption>Template format version 1</caption>
 *   <tr><th>Offset</th><th>Size</th><th>Field</th></tr>
 *   <tr><td>0</td><td>4</td><td>magic {@code "SFTE"} (0x53 0x46 0x54 0x45)</td></tr>
 *   <tr><td>4</td><td>1</td><td>format version, 1</td></tr>
 *   <tr><td>5</td><td>1</td><td>length L of the model identifier</td></tr>
 *   <tr><td>6</td><td>L</td><td>model identifier, UTF-8, for example {@code sface@2021dec-fp16w}</td></tr>
 *   <tr><td>6+L</td><td>2</td><td>dimension D (128 for SFace)</td></tr>
 *   <tr><td>8+L</td><td>4*D</td><td>unit-length embedding, IEEE 754 float32</td></tr>
 * </table>
 *
 * <p>The magic cannot start an ISO/IEC 19794-5 record ({@code "FAC\0"}), a JPEG, a PNG or a JPEG 2000 file, so a
 * BDB is recognised as a template without any other hint. Templates of another model identifier are never
 * compared. A template is biometric data: never log it.
 */
public final class FaceTemplate {

    /** Current format version. */
    public static final int VERSION = 1;

    private static final byte[] MAGIC = {'S', 'F', 'T', 'E'};
    private static final int MAX_DIMENSION = 4096;

    private FaceTemplate() {
    }

    /**
     * Tells whether a BDB is a template of this SDK.
     *
     * @param bdb BDB bytes; may be {@code null}
     * @return {@code true} if it starts with the template magic
     */
    public static boolean isTemplate(byte[] bdb) {
        if (bdb == null || bdb.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bdb[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes a template.
     *
     * @param embedding the embedding
     * @return the template bytes
     * @throws IllegalArgumentException if the model identifier is longer than 255 bytes
     */
    public static byte[] encode(FaceEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        byte[] model = embedding.modelId().getBytes(StandardCharsets.UTF_8);
        if (model.length > 255) {
            throw new IllegalArgumentException("model identifier too long");
        }
        float[] vector = embedding.vector();
        ByteBuffer out = ByteBuffer.allocate(MAGIC.length + 2 + model.length + 2 + 4 * vector.length);
        out.put(MAGIC).put((byte) VERSION).put((byte) model.length).put(model).putShort((short) vector.length);
        for (float v : vector) {
            out.putFloat(v);
        }
        return out.array();
    }

    /**
     * Reads a template.
     *
     * @param bdb template bytes
     * @return the embedding
     * @throws IllegalArgumentException if the bytes are not a valid template of a supported version
     */
    public static FaceEmbedding decode(byte[] bdb) {
        if (!isTemplate(bdb)) {
            throw new IllegalArgumentException("not a face template");
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(bdb);
            ((Buffer) in).position(MAGIC.length);
            int version = in.get() & 0xff;
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported face template version " + version);
            }
            byte[] model = new byte[in.get() & 0xff];
            in.get(model);
            int dimension = in.getShort() & 0xffff;
            if (dimension == 0 || dimension > MAX_DIMENSION || in.remaining() != 4 * dimension) {
                throw new IllegalArgumentException("face template has an inconsistent length");
            }
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = in.getFloat();
            }
            return FaceEmbedding.of(new String(model, StandardCharsets.UTF_8), vector);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated face template", e);
        }
    }
}
