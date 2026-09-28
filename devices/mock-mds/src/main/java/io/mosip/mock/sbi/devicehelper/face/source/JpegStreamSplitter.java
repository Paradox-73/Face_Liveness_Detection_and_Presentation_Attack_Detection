package io.mosip.mock.sbi.devicehelper.face.source;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Splits a byte stream of back-to-back JPEG images (for example ffmpeg
 * {@code -f image2pipe -vcodec mjpeg} output) into single images.
 *
 * <p>
 * A plain search for the end marker {@code FFD9} can cut a frame early when an
 * embedded thumbnail contains its own end marker, so this class walks the JPEG
 * segment structure: marker segments are skipped by their length field, and
 * only entropy-coded data is scanned byte by byte, where {@code FF00} (stuffed
 * byte) and {@code FFD0..FFD7} (restart markers) are data. Bytes before a start
 * marker {@code FFD8} are discarded. A frame cut off by end of input is
 * dropped.
 * </p>
 */
public class JpegStreamSplitter {
	private static final int MARKER_PREFIX = 0xFF;
	private static final int SOI = 0xD8;
	private static final int EOI = 0xD9;
	private static final int SOS = 0xDA;
	private static final int NO_MARKER = -2;

	private final int maxFrameBytes;

	/**
	 * Creates a splitter.
	 *
	 * @param maxFrameBytes largest accepted image; a longer image is discarded
	 *                      and the search restarts at the next start marker
	 */
	public JpegStreamSplitter(int maxFrameBytes) {
		this.maxFrameBytes = maxFrameBytes;
	}

	/**
	 * Reads the next complete JPEG image from the stream. Blocks until an image
	 * is complete or the stream ends.
	 *
	 * @param in the byte stream
	 * @return the image bytes, from {@code FFD8} to {@code FFD9}, or {@code null}
	 *         at end of stream
	 * @throws IOException if reading fails
	 */
	public byte[] readNext(InputStream in) throws IOException {
		while (true) {
			if (!seekStartOfImage(in))
				return null;
			try {
				byte[] image = readImageBody(in);
				if (image != null)
					return image;
			} catch (EOFException ex) {
				return null;
			}
		}
	}

	private static boolean seekStartOfImage(InputStream in) throws IOException {
		int previous = -1;
		int current;
		while ((current = in.read()) != -1) {
			if (previous == MARKER_PREFIX && current == SOI)
				return true;
			previous = current;
		}
		return false;
	}

	/**
	 * Reads the rest of an image after its start marker.
	 *
	 * @return the image, or null when the data is not a valid JPEG or too large
	 */
	private byte[] readImageBody(InputStream in) throws IOException {
		LimitedBuffer out = new LimitedBuffer(maxFrameBytes);
		out.write(MARKER_PREFIX);
		out.write(SOI);
		int pending = NO_MARKER;
		while (true) {
			int marker = pending != NO_MARKER ? pending : readMarker(in, out);
			pending = NO_MARKER;
			if (marker < 0 || out.isOverflow())
				return null;
			if (marker == EOI)
				return out.toByteArray();
			if (marker == SOI || isStandalone(marker))
				continue;
			copySegment(in, out);
			if (marker == SOS)
				pending = scanEntropyData(in, out);
		}
	}

	/** Reads {@code FF xx} (skipping fill bytes) and returns xx, or -1 if not a marker. */
	private static int readMarker(InputStream in, ByteArrayOutputStream out) throws IOException {
		int b = readByte(in);
		if (b != MARKER_PREFIX)
			return -1;
		out.write(b);
		do {
			b = readByte(in);
		} while (b == MARKER_PREFIX);
		out.write(b);
		return b;
	}

	private static void copySegment(InputStream in, ByteArrayOutputStream out) throws IOException {
		int high = readByte(in);
		int low = readByte(in);
		out.write(high);
		out.write(low);
		int length = (high << 8) | low;
		for (int i = 2; i < length; i++)
			out.write(readByte(in));
	}

	/** Copies entropy-coded bytes and returns the code of the marker that ends them. */
	private static int scanEntropyData(InputStream in, LimitedBuffer out) throws IOException {
		while (!out.isOverflow()) {
			int b = readByte(in);
			out.write(b);
			if (b != MARKER_PREFIX)
				continue;
			int next = readByte(in);
			while (next == MARKER_PREFIX)
				next = readByte(in);
			out.write(next);
			if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7))
				return next;
		}
		return -1;
	}

	private static boolean isStandalone(int marker) {
		return marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7);
	}

	private static int readByte(InputStream in) throws IOException {
		int b = in.read();
		if (b < 0)
			throw new EOFException("JPEG cut off by end of stream");
		return b;
	}

	/** Byte buffer that stops growing past a limit. */
	private static final class LimitedBuffer extends ByteArrayOutputStream {
		private final int limit;
		private boolean overflow;

		LimitedBuffer(int limit) {
			super(64 * 1024);
			this.limit = limit;
		}

		@Override
		public synchronized void write(int b) {
			if (count >= limit) {
				overflow = true;
				return;
			}
			super.write(b);
		}

		boolean isOverflow() {
			return overflow;
		}
	}
}
