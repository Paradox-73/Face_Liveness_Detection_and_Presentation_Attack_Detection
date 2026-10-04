/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.fault;

import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;

/**
 * What the STREAM writer does with one frame, as decided by
 * {@link FaceFaultInjector#decide(FaceFrame)}.
 */
public final class FrameAction {
	/** Kinds of action. */
	public enum Kind {
		/** Write the frame as a normal multipart part. */
		SEND,
		/** Write nothing for this frame and keep the connection open. */
		SKIP,
		/** Write the frame with a Content-Length that differs from its size. */
		SEND_BAD_LENGTH,
		/** Close the connection cleanly and end the stream. */
		DISCONNECT,
		/** Reset the connection and end the stream. */
		RESET
	}

	private static final FrameAction SKIP_ACTION = new FrameAction(Kind.SKIP, null, false, 0);
	private static final FrameAction DISCONNECT_ACTION = new FrameAction(Kind.DISCONNECT, null, false, 0);
	private static final FrameAction RESET_ACTION = new FrameAction(Kind.RESET, null, false, 0);

	private final Kind kind;
	private final FaceFrame frame;
	private final boolean validImage;
	private final int lengthDelta;

	private FrameAction(Kind kind, FaceFrame frame, boolean validImage, int lengthDelta) {
		this.kind = kind;
		this.frame = frame;
		this.validImage = validImage;
		this.lengthDelta = lengthDelta;
	}

	/**
	 * Sends a frame.
	 *
	 * @param frame      the frame to write
	 * @param validImage true when the bytes are an intact JPEG that a capture may
	 *                   reuse
	 * @return the action
	 */
	public static FrameAction send(FaceFrame frame, boolean validImage) {
		return new FrameAction(Kind.SEND, frame, validImage, 0);
	}

	/**
	 * Sends a frame with a wrong Content-Length.
	 *
	 * @param frame       the frame to write
	 * @param lengthDelta bytes added to the true length in the header
	 * @return the action
	 */
	public static FrameAction sendBadLength(FaceFrame frame, int lengthDelta) {
		return new FrameAction(Kind.SEND_BAD_LENGTH, frame, false, lengthDelta);
	}

	/** @return the skip action */
	public static FrameAction skip() {
		return SKIP_ACTION;
	}

	/** @return the clean-close action */
	public static FrameAction disconnect() {
		return DISCONNECT_ACTION;
	}

	/** @return the reset action */
	public static FrameAction reset() {
		return RESET_ACTION;
	}

	/** @return the kind of action */
	public Kind getKind() {
		return kind;
	}

	/** @return the frame to write, or null for SKIP, DISCONNECT and RESET */
	public FaceFrame getFrame() {
		return frame;
	}

	/** @return true when the written bytes are an intact JPEG */
	public boolean isValidImage() {
		return validImage;
	}

	/** @return bytes added to the true length for SEND_BAD_LENGTH */
	public int getLengthDelta() {
		return lengthDelta;
	}
}
