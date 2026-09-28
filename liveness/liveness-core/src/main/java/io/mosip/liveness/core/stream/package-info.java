/**
 * Stream plumbing shared by the clients: the SBI MJPEG reader
 * ({@link io.mosip.liveness.core.stream.MjpegFrameReader}), the bounded frame hand-off between the reader thread
 * and the session thread ({@link io.mosip.liveness.core.stream.FrameHandOff}) and a frame-rate meter
 * ({@link io.mosip.liveness.core.stream.FrameRateMeter}). Uses only {@code java.io}; the caller opens the
 * connection.
 */
package io.mosip.liveness.core.stream;
