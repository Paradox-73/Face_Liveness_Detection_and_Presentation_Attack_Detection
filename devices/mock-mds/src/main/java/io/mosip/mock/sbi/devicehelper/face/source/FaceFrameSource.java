package io.mosip.mock.sbi.devicehelper.face.source;

import java.io.IOException;
import java.util.Map;

/**
 * A supplier of JPEG frames for the mock face device's STREAM and RCAPTURE.
 *
 * <p>
 * Lifecycle: {@link #open()} once, {@link #next()} any number of times,
 * {@link #close()} once. {@link #close()} may be called from another thread
 * while {@link #next()} is blocked; {@link #next()} must then return promptly.
 * Frame pacing is done by the caller, using {@link #getFps()}.
 * </p>
 */
public interface FaceFrameSource extends AutoCloseable {

	/**
	 * Prepares the source, for example lists a folder or starts a process.
	 *
	 * @throws IOException if the source cannot produce frames
	 */
	void open() throws IOException;

	/**
	 * Returns the next frame.
	 *
	 * @return the next frame, or {@code null} when the source has ended
	 * @throws IOException if reading fails
	 */
	FaceFrame next() throws IOException;

	/**
	 * Returns the nominal frame rate the caller should pace the stream at.
	 *
	 * @return frames per second, greater than 0
	 */
	double getFps();

	/**
	 * Returns the short type name of the source.
	 *
	 * @return {@code STATIC}, {@code SEQUENCE} or {@code FFMPEG}
	 */
	String getName();

	/**
	 * Describes the source for the admin state response. Must not contain image
	 * data.
	 *
	 * @return a map of setting names to values
	 */
	Map<String, Object> describe();

	/**
	 * Releases files, processes and threads held by the source.
	 */
	@Override
	void close();
}
