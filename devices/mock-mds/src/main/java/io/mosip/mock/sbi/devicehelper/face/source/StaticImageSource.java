package io.mosip.mock.sbi.devicehelper.face.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves one JPEG file as every frame: the stock MockMDS behaviour.
 *
 * <p>
 * The file is read again for every frame, as the stock code does, so an
 * external program that replaces the file (for example ffmpeg with
 * {@code -update 1 -atomic_writing 1}) still changes the stream. The whole file
 * is read, unlike the stock single {@code read()} call.
 * </p>
 */
public class StaticImageSource implements FaceFrameSource {
	/** Source type name. */
	public static final String NAME = "STATIC";

	private final Path file;
	private final double fps;
	private long sequence;

	/**
	 * Creates the source.
	 *
	 * @param file JPEG file to serve
	 * @param fps  frame rate to pace the stream at
	 */
	public StaticImageSource(Path file, double fps) {
		this.file = file;
		this.fps = FaceSourceSettings.checkFps(fps);
	}

	/** {@inheritDoc} */
	@Override
	public void open() throws IOException {
		if (!Files.isRegularFile(file))
			throw new NoSuchFileException(file.toString());
		sequence = 0;
	}

	/** {@inheritDoc} */
	@Override
	public FaceFrame next() throws IOException {
		if (!Files.isRegularFile(file))
			return null;
		byte[] jpeg = Files.readAllBytes(file);
		if (jpeg.length == 0)
			return null;
		return new FaceFrame(jpeg, sequence++, System.currentTimeMillis(), NAME);
	}

	/** {@inheritDoc} */
	@Override
	public double getFps() {
		return fps;
	}

	/** {@inheritDoc} */
	@Override
	public String getName() {
		return NAME;
	}

	/** {@inheritDoc} */
	@Override
	public Map<String, Object> describe() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("source", NAME);
		map.put("path", file.toString());
		map.put("fps", fps);
		return map;
	}

	/** {@inheritDoc} */
	@Override
	public void close() {
		// nothing held open between frames
	}
}
