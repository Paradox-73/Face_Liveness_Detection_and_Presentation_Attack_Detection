package io.mosip.liveness.onnx.model;

/**
 * Identifiers of the models in the standard bundle ({@code models/manifest.json}).
 */
public final class ModelIds {

    /** YuNet face detector (box, five landmarks, score). */
    public static final String YUNET = "yunet";
    /** MediaPipe Face Mesh V2 landmark model (478 points). */
    public static final String FACE_MESH = "facemesh";
    /** MiniFASNetV2 passive PAD model, crop scale 2.7. */
    public static final String MINIFASNET_V2 = "minifasnet-v2";
    /** MiniFASNetV1SE passive PAD model, crop scale 4.0. */
    public static final String MINIFASNET_V1SE = "minifasnet-v1se";
    /** SFace face recognition model (128-value embedding). */
    public static final String SFACE = "sface";

    private ModelIds() {
    }
}
