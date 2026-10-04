/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mosip.mock.sbi.devicehelper.SBIDeviceHelper;
import io.mosip.mock.sbi.devicehelper.face.FaceFrameFeed;
import io.mosip.mock.sbi.devicehelper.face.SBIFaceHelper;
import io.mosip.mock.sbi.devicehelper.face.fault.FaceFaultInjector;
import io.mosip.mock.sbi.devicehelper.face.fault.FrameAction;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;

/**
 * Writes the face device's STREAM frames for
 * {@code SBIServiceResponse.processLiveStreamInfo}: applies the active fault to
 * each frame, records which frames were sent (for RCAPTURE), and paces the
 * stream. Finger and iris streams keep the stock code path.
 */
public final class FaceStreamWriter {
	private static final Logger LOGGER = LoggerFactory.getLogger(FaceStreamWriter.class);

	/** Writes one normal multipart part; the stock {@code renderJPGImageData}. */
	@FunctionalInterface
	public interface FrameRenderer {
		/**
		 * @param image JPEG bytes to write
		 * @throws IOException if the client connection fails
		 */
		void render(byte[] image) throws IOException;
	}

	private final SBIFaceHelper face;
	private final FaceFrameFeed feed;
	private final FaceFaultInjector faults;
	private final Socket socket;

	private FaceStreamWriter(SBIFaceHelper face, Socket socket) {
		this.face = face;
		this.feed = face.getFrameFeed();
		this.faults = face.getFaultInjector();
		this.socket = socket;
	}

	/**
	 * Returns a writer for the face device, or null for other devices.
	 *
	 * @param deviceHelper the device being streamed
	 * @param socket       the client connection
	 * @return the writer, or null when the device is not the face device
	 */
	public static FaceStreamWriter forDevice(SBIDeviceHelper deviceHelper, Socket socket) {
		if (deviceHelper instanceof SBIFaceHelper faceHelper)
			return new FaceStreamWriter(faceHelper, socket);
		return null;
	}

	/**
	 * Opens the frame source for this stream.
	 *
	 * @return false when the camera is unavailable; the caller answers with
	 *         error 608 before any stream header is written
	 */
	public boolean start() {
		return feed.open();
	}

	/**
	 * Writes one frame, applying the active fault.
	 *
	 * @param image    the frame read by {@code getLiveStream}
	 * @param renderer writes a normal multipart part
	 * @return false when the stream must end (DISCONNECT or RESET fault)
	 * @throws IOException if the client connection fails
	 */
	public boolean writeFrame(byte[] image, FrameRenderer renderer) throws IOException {
		if (face.isHoldingStreamForCapture())
			return true; // an RCAPTURE started after this frame was read; it must not follow the captured one
		FaceFrame frame = feed.getCurrentFrame();
		if (frame == null || frame.getJpeg() != image)
			frame = new FaceFrame(image, -1, System.currentTimeMillis(), "UNKNOWN");
		FrameAction action = faults.decide(frame);
		switch (action.getKind()) {
		case SKIP:
			return true;
		case DISCONNECT:
			LOGGER.info("Face fault DISCONNECT :: closing the stream connection");
			socket.shutdownOutput();
			return false;
		case RESET:
			LOGGER.info("Face fault RESET :: resetting the stream connection");
			socket.setSoLinger(true, 0);
			socket.close();
			return false;
		case SEND_BAD_LENGTH:
			writeWithLength(action.getFrame().getJpeg(), action.getFrame().getJpeg().length + action.getLengthDelta());
			feed.markServed(action.getFrame(), false);
			return true;
		default:
			renderer.render(action.getFrame().getJpeg());
			feed.markServed(action.getFrame(), action.isValidImage());
			return true;
		}
	}

	/**
	 * Returns the pause before the next frame, keeping the stream at the source
	 * frame rate (or the SLOW_FPS rate) plus any fault delay. Replaces the stock
	 * fixed 30 ms sleep for the face device.
	 *
	 * @return milliseconds to sleep
	 */
	public long getPauseMillis() {
		return feed.nextPauseMillis();
	}

	private void writeWithLength(byte[] image, int declaredLength) throws IOException {
		String header = "--BoundaryString\r\n" + "Access-Control-Allow-Origin:*\r\n" + "Content-Type:image/jpeg\r\n"
				+ "Content-Length:" + Math.max(0, declaredLength) + "\r\n\r\n";
		OutputStream out = new BufferedOutputStream(socket.getOutputStream());
		out.write(header.getBytes(StandardCharsets.US_ASCII));
		out.write(image);
		out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}
}
