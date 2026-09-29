package io.mosip.liveness.facematch;

import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.constant.ProcessedLevelType;
import io.mosip.kernel.biometrics.constant.PurposeType;
import io.mosip.kernel.biometrics.constant.QualityType;
import io.mosip.kernel.biometrics.entities.BDBInfo;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.biometrics.entities.RegistryIDType;
import io.mosip.liveness.onnx.model.ModelSources;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Test data: MockMDS face records (MPL-2.0 files of the mock device in {@code devices/mock-mds}) and the MockMDS
 * stream frame, read in place; ISO/IEC 19794-5 records built here; and MOSIP BIRs as the Registration Client
 * builds them.
 *
 * <p>People: the registration and authentication face records and the stream frame show person A (the two records
 * hold the same photo, lossless and lossy; the stream frame is a different photo); the exception photo shows
 * person B.
 */
public final class SdkTestData {

    /** A shared matcher; loading YuNet and SFace once keeps the tests fast. */
    private static SFaceMatcher matcher;

    private SdkTestData() {
    }

    /**
     * Returns the MockMDS registration face record of person A (JPEG 2000 lossless, 240x320).
     *
     * @return the record bytes
     */
    public static byte[] registrationFaceIso() {
        return mockMds("Profile/Default/Registration/Face.iso");
    }

    /**
     * Returns the MockMDS authentication face record of person A: the registration photo again, JPEG 2000 lossy.
     *
     * @return the record bytes
     */
    public static byte[] authFaceIso() {
        return mockMds("Profile/Default/Auth/Face.iso");
    }

    /**
     * Returns the MockMDS exception photo record of person B (JPEG 2000, 600x400).
     *
     * @return the record bytes
     */
    public static byte[] exceptionPhotoIso() {
        return mockMds("Profile/Default/Registration/Exception_Photo.iso");
    }

    /**
     * Returns the MockMDS stream frame of person A (JPEG, 490x360).
     *
     * @return the JPEG bytes
     */
    public static byte[] streamFrameJpeg() {
        return read(new File(System.getProperty("facematch.faces.dir",
                "../liveness-onnx/src/test/resources/faces"), "mockmds-stream-0.jpeg"));
    }

    /**
     * Returns a JPEG without a face (a smooth grey gradient).
     *
     * @return the JPEG bytes
     */
    public static byte[] blankJpeg() {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        for (int x = 0; x < 320; x++) {
            g.setColor(new Color(90 + x / 4, 90 + x / 4, 90 + x / 4));
            g.drawLine(x, 0, x, 239);
        }
        g.dispose();
        return jpeg(img);
    }

    /**
     * Encodes an image as JPEG.
     *
     * @param img the image
     * @return JPEG bytes
     */
    public static byte[] jpeg(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds an ISO/IEC 19794-5:2011 record around an encoded image.
     *
     * @param image         encoded image
     * @param imageDataType 0 JPEG, 1 or 2 JPEG 2000, 3 PNG
     * @param width         declared width
     * @param height        declared height
     * @param qualities     quality scores, one block each
     * @param landmarks     number of landmark points (zero-filled)
     * @return the record
     */
    public static byte[] iso2011(byte[] image, int imageDataType, int width, int height, int[] qualities,
                                 int landmarks) {
        int representation = 4 + 9 + 1 + 2 + 2 + 1 + 5 * qualities.length + 2 + 15 + 8 * landmarks + 11 + 4
                + image.length;
        ByteBuffer b = ByteBuffer.allocate(17 + representation);
        b.put(new byte[] {'F', 'A', 'C', 0, '0', '3', '0', 0}).putInt(17 + representation).putShort((short) 1)
                .put((byte) 0).putShort((short) 0);
        b.putInt(representation).put(new byte[9]).put((byte) 5).putShort((short) 0).putShort((short) 0);
        b.put((byte) qualities.length);
        for (int q : qualities) {
            b.put((byte) q).putShort((short) 1).putShort((short) 1);
        }
        b.putShort((short) landmarks).put(new byte[15]).put(new byte[8 * landmarks]);
        b.put((byte) 1).put((byte) imageDataType).putShort((short) width).putShort((short) height)
                .put(new byte[5]).putInt(image.length).put(image);
        return b.array();
    }

    /**
     * Builds an ISO/IEC 19794-5:2005 record around an encoded image.
     *
     * @param image       encoded image
     * @param width       declared width
     * @param height      declared height
     * @param featurePts  number of feature points (zero-filled)
     * @return the record
     */
    public static byte[] iso2005(byte[] image, int width, int height, int featurePts) {
        int block = 20 + 8 * featurePts + 12 + image.length;
        ByteBuffer b = ByteBuffer.allocate(14 + block);
        b.put(new byte[] {'F', 'A', 'C', 0, '0', '1', '0', 0}).putInt(14 + block).putShort((short) 1);
        b.putInt(block).putShort((short) featurePts).put(new byte[14]).put(new byte[8 * featurePts]);
        b.put((byte) 1).put((byte) 0).putShort((short) width).putShort((short) height).put(new byte[4])
                .putShort((short) 77).put(image);
        return b.array();
    }

    /**
     * Builds a FACE BIR as the desktop client's {@code BIRBuilder} does.
     *
     * @param bdb   the BDB (face record, image or template)
     * @param level processed level
     * @return the BIR
     */
    public static BIR faceBir(byte[] bdb, ProcessedLevelType level) {
        return bir(BiometricType.FACE, bdb, level);
    }

    /**
     * Builds a BIR of any modality.
     *
     * @param type  modality
     * @param bdb   the BDB
     * @param level processed level
     * @return the BIR
     */
    public static BIR bir(BiometricType type, byte[] bdb, ProcessedLevelType level) {
        BDBInfo info = new BDBInfo();
        info.setType(new ArrayList<>(Collections.singletonList(type)));
        info.setSubtype(new ArrayList<>());
        info.setFormat(new RegistryIDType("Mosip", "8"));
        info.setQuality(new QualityType(new RegistryIDType("HMAC", "SHA-256"), 80L, null));
        info.setPurpose(PurposeType.IDENTIFY);
        info.setLevel(level);
        BIR bir = new BIR();
        bir.setBdbInfo(info);
        bir.setBdb(bdb);
        return bir;
    }

    /**
     * Wraps BIRs in a record.
     *
     * @param birs the segments
     * @return the record
     */
    public static BiometricRecord record(BIR... birs) {
        BiometricRecord r = new BiometricRecord();
        r.setSegments(new ArrayList<>(Arrays.asList(birs)));
        return r;
    }

    /**
     * Returns the list of requested modalities.
     *
     * @param types modalities
     * @return a mutable list
     */
    public static List<BiometricType> modalities(BiometricType... types) {
        return new ArrayList<>(Arrays.asList(types));
    }

    /**
     * Returns a matcher over the repository model bundle with the ImageIO decoder, shared by the tests.
     *
     * @return the matcher
     * @throws Exception if the models cannot be loaded
     */
    public static synchronized SFaceMatcher matcher() throws Exception {
        if (matcher == null) {
            matcher = SFaceMatcher.load(ModelSources.directory(modelsDir()),
                    new io.mosip.liveness.facematch.imageio.ImageIoFaceImageDecoder());
        }
        return matcher;
    }

    /**
     * Returns the repository model bundle directory.
     *
     * @return {@code models/}
     */
    public static File modelsDir() {
        return new File(System.getProperty("liveness.models.dir", "../../models"));
    }

    private static byte[] mockMds(String path) {
        return read(new File(System.getProperty("facematch.mockmds.dir", "../../devices/mock-mds"), path));
    }

    private static byte[] read(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
