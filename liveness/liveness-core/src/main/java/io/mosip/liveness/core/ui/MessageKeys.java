package io.mosip.liveness.core.ui;

import io.mosip.liveness.core.ChallengeType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every user-facing liveness message key (design section 11.2). The same keys exist in the desktop resource
 * bundles and the Android ARB files; the single source of the texts is {@code docs/ux/message-catalogue.csv}.
 *
 * <p>Argument placeholders use {@code {0}}, {@code {1}} as in {@link java.text.MessageFormat}. A synced key
 * {@code mosip.registration.liveness.guidance.<messageKey>.<lang>} may replace a text for one language.
 */
public final class MessageKeys {

    /** "Checking face liveness..." */
    public static final String STATUS_CHECKING = "liveness.status.checking";

    /** "Please blink" */
    public static final String CHALLENGE_BLINK = "liveness.challenge.blink";
    /** "Please smile" */
    public static final String CHALLENGE_SMILE = "liveness.challenge.smile";
    /** "Please turn your head to the left" */
    public static final String CHALLENGE_TURN_LEFT = "liveness.challenge.turn_left";
    /** "Please turn your head to the right" */
    public static final String CHALLENGE_TURN_RIGHT = "liveness.challenge.turn_right";
    /** "Please look up" */
    public static final String CHALLENGE_LOOK_UP = "liveness.challenge.look_up";
    /** "Please look down" */
    public static final String CHALLENGE_LOOK_DOWN = "liveness.challenge.look_down";
    /** "Please look to your left without turning your head" */
    public static final String CHALLENGE_GAZE_LEFT = "liveness.challenge.gaze_left";
    /** "Please look to your right without turning your head" */
    public static final String CHALLENGE_GAZE_RIGHT = "liveness.challenge.gaze_right";

    /** "Please continue" */
    public static final String FEEDBACK_CONTINUE = "liveness.feedback.continue";
    /** "Action detected" */
    public static final String FEEDBACK_DETECTED = "liveness.feedback.detected";
    /** "Hold still" */
    public static final String FEEDBACK_HOLD_STILL = "liveness.feedback.hold_still";
    /** "Good, face captured successfully" */
    public static final String FEEDBACK_SUCCESS = "liveness.feedback.success";

    /** "We could not verify face liveness. Please try again." Below-threshold, challenge and timeout failures. */
    public static final String FAIL_LIVENESS = "liveness.fail.liveness";
    /** "Face verification could not be completed. Please try again." Generic message for every suspected attack. */
    public static final String FAIL_PAD = "liveness.fail.pad";

    /** "Look directly at the camera." */
    public static final String GUIDE_LOOK_CAMERA = "liveness.guide.look_camera";
    /** "Ensure your face is clearly visible." */
    public static final String GUIDE_FACE_VISIBLE = "liveness.guide.face_visible";
    /** "Improve lighting." */
    public static final String GUIDE_LIGHTING = "liveness.guide.lighting";
    /** "Keep your face within the capture area." */
    public static final String GUIDE_IN_AREA = "liveness.guide.in_area";
    /** "Follow the prompted action." */
    public static final String GUIDE_FOLLOW_ACTION = "liveness.guide.follow_action";

    /** "Attempt {0} of {1}. Select Retry to try again." */
    public static final String RETRY_AVAILABLE = "liveness.retry.available";
    /** "Face capture could not be completed after {0} attempts. Please ask your supervisor ..." */
    public static final String RETRY_MAX_RESIDENT = "liveness.retry.max_resident";
    /** "Face verification could not be completed. Maximum attempts reached. Use another sign-in method ..." */
    public static final String RETRY_MAX_AUTH = "liveness.retry.max_auth";
    /** "Supervisor override: this face will be flagged for review in the packet. Continue?" */
    public static final String OVERRIDE_CONFIRM = "liveness.override.confirm";

    /** "No face detected. Keep your face within the capture area." */
    public static final String ERROR_NO_FACE = "liveness.error.no_face";
    /** "More than one face detected. Only the person being captured should be in view." */
    public static final String ERROR_MULTIPLE_FACES = "liveness.error.multiple_faces";
    /** "The face image is not clear. Improve lighting and look directly at the camera." */
    public static final String ERROR_POOR_QUALITY = "liveness.error.poor_quality";
    /** "Time is up for this action. Please try again." */
    public static final String ERROR_TIMEOUT = "liveness.error.timeout";
    /** "The face camera is not available. Check that it is connected and select Retry." */
    public static final String ERROR_DEVICE_UNAVAILABLE = "liveness.error.device_unavailable";
    /** "The face camera was disconnected. Reconnect it and select Retry." */
    public static final String ERROR_DEVICE_DISCONNECTED = "liveness.error.device_disconnected";
    /** "This face camera does not support live verification. Connect a supported camera." */
    public static final String ERROR_DEVICE_NO_STREAM = "liveness.error.device_no_stream";
    /** "The camera could not be opened. Close other apps using the camera and try again." */
    public static final String ERROR_CAMERA_UNAVAILABLE = "liveness.error.camera_unavailable";
    /** "Camera access is off for this app. Allow camera access in Settings and select Retry." */
    public static final String ERROR_CAMERA_PERMISSION = "liveness.error.camera_permission";
    /** "The camera stream was interrupted. Please try again." */
    public static final String ERROR_FRAMES_INVALID = "liveness.error.frames_invalid";
    /** "Face verification is unavailable on this machine. Contact your supervisor." */
    public static final String ERROR_ENGINE = "liveness.error.engine";
    /** "This user is not a supervisor. A supervisor must verify." */
    public static final String ERROR_NOT_SUPERVISOR = "liveness.error.not_supervisor";
    /** "Face verification is not allowed on this device." */
    public static final String ERROR_EMULATOR = "liveness.error.emulator";

    /** "Supervisor verification" (panel title in reviewer and approval flows). */
    public static final String TITLE_SUPERVISOR = "liveness.title.supervisor";
    /** "Offline - verification runs on this device" (badge). */
    public static final String OFFLINE_BADGE = "liveness.offline.badge";
    /** "Face verification settings updated." (toast after a sync that changed liveness keys). */
    public static final String CONFIG_UPDATED = "liveness.config.updated";

    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            STATUS_CHECKING,
            CHALLENGE_BLINK, CHALLENGE_SMILE, CHALLENGE_TURN_LEFT, CHALLENGE_TURN_RIGHT, CHALLENGE_LOOK_UP,
            CHALLENGE_LOOK_DOWN, CHALLENGE_GAZE_LEFT, CHALLENGE_GAZE_RIGHT,
            FEEDBACK_CONTINUE, FEEDBACK_DETECTED, FEEDBACK_HOLD_STILL, FEEDBACK_SUCCESS,
            FAIL_LIVENESS, FAIL_PAD,
            GUIDE_LOOK_CAMERA, GUIDE_FACE_VISIBLE, GUIDE_LIGHTING, GUIDE_IN_AREA, GUIDE_FOLLOW_ACTION,
            RETRY_AVAILABLE, RETRY_MAX_RESIDENT, RETRY_MAX_AUTH, OVERRIDE_CONFIRM,
            ERROR_NO_FACE, ERROR_MULTIPLE_FACES, ERROR_POOR_QUALITY, ERROR_TIMEOUT, ERROR_DEVICE_UNAVAILABLE,
            ERROR_DEVICE_DISCONNECTED, ERROR_DEVICE_NO_STREAM, ERROR_CAMERA_UNAVAILABLE, ERROR_CAMERA_PERMISSION,
            ERROR_FRAMES_INVALID, ERROR_ENGINE, ERROR_NOT_SUPERVISOR, ERROR_EMULATOR,
            TITLE_SUPERVISOR, OFFLINE_BADGE, CONFIG_UPDATED));

    private MessageKeys() {
    }

    /**
     * Lists every message key, for bundle-completeness checks.
     *
     * @return an unmodifiable list of the 40 keys
     */
    public static List<String> all() {
        return ALL;
    }

    /**
     * Returns the prompt key of a challenge.
     *
     * @param type the challenge
     * @return for example {@code liveness.challenge.turn_left}
     */
    public static String challengeKey(ChallengeType type) {
        return "liveness.challenge." + type.name().toLowerCase(Locale.ROOT);
    }
}
