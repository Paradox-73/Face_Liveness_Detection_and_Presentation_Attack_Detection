package io.mosip.registration.controller.liveness;

import java.util.EnumMap;
import java.util.Map;

import io.mosip.liveness.core.ui.PromptIcon;

/**
 * Vector outlines (SVG path data, 24 x 24 box) for the prompt icons of the liveness panel. Drawn as paths so they
 * scale with the prompt text and need no image files. Arrows show the physical direction and are never mirrored for
 * right-to-left languages.
 */
final class LivenessIcons {

	private static final String CIRCLE_RING = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM12 4a8 8 0 1 1 0 16a8 8 0 1 1 0-16z";
	private static final String EYE_OUTLINE = "M12 5C6.5 5 2.7 9 1.5 12c1.2 3 5 7 10.5 7s9.3-4 10.5-7C21.3 9 17.5 5 12 5z"
			+ "M12 7c4 0 7 2.8 8.3 5c-1.3 2.2-4.3 5-8.3 5s-7-2.8-8.3-5C5 9.8 8 7 12 7z";

	private static final Map<PromptIcon, String> PATHS = new EnumMap<>(PromptIcon.class);

	static {
		PATHS.put(PromptIcon.FACE_OVAL, "M12 2C7.6 2 5 5.6 5 10.5S7.6 22 12 22s7-6.6 7-11.5S16.4 2 12 2z"
				+ "M12 4c3.1 0 5 2.7 5 6.5S15.1 20 12 20s-5-5.7-5-9.5S8.9 4 12 4z");
		PATHS.put(PromptIcon.PROGRESS, "M6 2h12v5l-4 5l4 5v5H6v-5l4-5l-4-5z");
		PATHS.put(PromptIcon.BLINK, "M2 11c2.5 3.2 6 5.2 10 5.2s7.5-2 10-5.2l-1.6-1.2C18.2 12.5 15.3 14.2 12 14.2"
				+ "s-6.2-1.7-8.4-4.4zM11 17h2v4h-2zM5.2 15.4l1.7 1l-2 3.4l-1.7-1zM18.8 15.4l2 3.4l-1.7 1l-2-3.4z");
		PATHS.put(PromptIcon.SMILE, CIRCLE_RING + "M7 9.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0z"
				+ "M14 9.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0z"
				+ "M7 13.5c1.2 2 3 3 5 3s3.8-1 5-3l-1.6-0.9c-0.9 1.4-2.1 2-3.4 2s-2.5-0.6-3.4-2z");
		PATHS.put(PromptIcon.TURN_LEFT, "M10 5l-7 7l7 7v-4.5h11v-5H10z");
		PATHS.put(PromptIcon.TURN_RIGHT, "M14 5l7 7l-7 7v-4.5H3v-5h11z");
		PATHS.put(PromptIcon.LOOK_UP, "M5 10l7-7l7 7h-4.5v11h-5V10z");
		PATHS.put(PromptIcon.LOOK_DOWN, "M5 14l7 7l7-7h-4.5V3h-5v11z");
		PATHS.put(PromptIcon.GAZE_LEFT, EYE_OUTLINE + "M6.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0z");
		PATHS.put(PromptIcon.GAZE_RIGHT, EYE_OUTLINE + "M12.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0z");
		PATHS.put(PromptIcon.CHECK, "M9 16.2l-4.2-4.2l-1.4 1.4L9 19L21 7l-1.4-1.4z");
		PATHS.put(PromptIcon.HOLD_STILL, "M6 5h4v14H6zM14 5h4v14h-4z");
		PATHS.put(PromptIcon.WARNING, "M1 21h22L12 2zM13 18h-2v-2h2zM13 14h-2v-4h2z");
		PATHS.put(PromptIcon.ERROR, CIRCLE_RING + "M15.6 7L17 8.4L13.4 12L17 15.6L15.6 17L12 13.4L8.4 17L7 15.6"
				+ "L10.6 12L7 8.4L8.4 7L12 10.6z");
		PATHS.put(PromptIcon.CAMERA_OFF, "M21 6.5l-4 4V7c0-0.6-0.4-1-1-1H9.8L21 17.2zM3.3 2L2 3.3L4.7 6H4"
				+ "c-0.6 0-1 0.4-1 1v10c0 0.6 0.4 1 1 1h12c0.2 0 0.4-0.1 0.5-0.2l3.2 3.2l1.3-1.3z");
		PATHS.put(PromptIcon.LOCKED, "M18 8h-1V6A5 5 0 0 0 7 6v2H6c-1.1 0-2 0.9-2 2v10c0 1.1 0.9 2 2 2h12"
				+ "c1.1 0 2-0.9 2-2V10c0-1.1-0.9-2-2-2zM9 6a3 3 0 0 1 6 0v2H9zM12 17a2 2 0 1 1 0-4a2 2 0 0 1 0 4z");
	}

	private LivenessIcons() {
	}

	/**
	 * Returns the outline of an icon.
	 *
	 * @param icon the icon
	 * @return SVG path data; the face oval for {@link PromptIcon#NONE}
	 */
	static String path(PromptIcon icon) {
		String path = PATHS.get(icon);
		return path != null ? path : PATHS.get(PromptIcon.FACE_OVAL);
	}
}
