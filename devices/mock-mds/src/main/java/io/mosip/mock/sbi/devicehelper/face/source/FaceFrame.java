/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One JPEG frame produced by a {@link FaceFrameSource}.
 *
 * <p>
 * The JPEG bytes are shared, not copied, so callers must not modify the array
 * returned by {@link #getJpeg()}.
 * </p>
 */
public final class FaceFrame {
	private final byte[] jpeg;
	private final long sequence;
	private final long timestampMillis;
	private final String sourceName;

	/**
	 * Creates a frame.
	 *
	 * @param jpeg            encoded JPEG bytes, never null
	 * @param sequence        position of the frame in its source, starting at 0
	 * @param timestampMillis wall-clock time the frame was produced, in
	 *                        milliseconds since the epoch
	 * @param sourceName      short name of the source that produced the frame
	 */
	public FaceFrame(byte[] jpeg, long sequence, long timestampMillis, String sourceName) {
		if (jpeg == null)
			throw new IllegalArgumentException("jpeg must not be null");
		this.jpeg = jpeg;
		this.sequence = sequence;
		this.timestampMillis = timestampMillis;
		this.sourceName = sourceName;
	}

	/**
	 * Returns the encoded JPEG bytes (shared array, do not modify).
	 *
	 * @return the JPEG bytes
	 */
	public byte[] getJpeg() {
		return jpeg;
	}

	/**
	 * Returns the position of the frame in its source.
	 *
	 * @return the sequence number, starting at 0
	 */
	public long getSequence() {
		return sequence;
	}

	/**
	 * Returns the time the frame was produced.
	 *
	 * @return milliseconds since the epoch
	 */
	public long getTimestampMillis() {
		return timestampMillis;
	}

	/**
	 * Returns the short name of the source that produced the frame.
	 *
	 * @return the source name, for example {@code SEQUENCE}
	 */
	public String getSourceName() {
		return sourceName;
	}

	/**
	 * Returns a copy of this frame with different JPEG bytes and the same
	 * metadata. Used by fault injection to send altered bytes.
	 *
	 * @param newJpeg replacement bytes
	 * @return the new frame
	 */
	public FaceFrame withJpeg(byte[] newJpeg) {
		return new FaceFrame(newJpeg, sequence, timestampMillis, sourceName);
	}

	/**
	 * Returns the lower-case hex SHA-256 digest of the JPEG bytes. The digest
	 * identifies a frame in tests and in the admin state without exposing the
	 * image itself.
	 *
	 * @return the hex digest
	 */
	public String sha256() {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
