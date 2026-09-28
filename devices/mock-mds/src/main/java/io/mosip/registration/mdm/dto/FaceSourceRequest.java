package io.mosip.registration.mdm.dto;

import lombok.Data;

/**
 * Body of {@code POST /admin/source}: selects the mock face device's frame
 * source and, optionally, its capture mode. Fields left null keep their
 * current value where that makes sense (see LIVENESS-EXTENSIONS.md).
 */
@Data
public class FaceSourceRequest {
	/** Device type: Face or Biometric Device; optional. */
	private String type;

	/** STATIC, SEQUENCE or FFMPEG; null keeps the current source. */
	private String source;

	/** JPEG file (STATIC) or folder of JPEG files (SEQUENCE). */
	private String path;

	/** Frames per second, in (0, 60]. */
	private Double fps;

	/** Restart SEQUENCE or FFMPEG file input at the end. */
	private Boolean loop;

	/** Webcam device name for FFMPEG, for example "Integrated Camera". */
	private String camera;

	/** Video file for FFMPEG. */
	private String file;

	/** ffmpeg webcam input format: dshow, v4l2 or avfoundation. */
	private String inputFormat;

	/** FFMPEG output width in pixels. */
	private Integer width;

	/** RCAPTURE image: FRAME (current stream frame) or PROFILE (stock Face.iso). */
	private String captureMode;
}
