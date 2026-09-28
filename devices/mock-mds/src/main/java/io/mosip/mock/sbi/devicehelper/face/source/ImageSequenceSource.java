package io.mosip.mock.sbi.devicehelper.face.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Plays a folder of JPEG files in file-name order, for automated tests and
 * attack replays. Needs no ffmpeg.
 *
 * <p>
 * Files ending in {@code .jpg} or {@code .jpeg} (any case) are used, sorted by
 * name, so frames should be named with zero-padded numbers such as
 * {@code frame_0001.jpg}. Each frame is read from disk when it is served. With
 * {@code loop} the sequence restarts after the last frame; without it,
 * {@link #next()} returns {@code null} after the last frame and the stream
 * ends. Every {@link #open()} restarts at the first frame, so each STREAM
 * request sees the same frames in the same order.
 * </p>
 */
public class ImageSequenceSource implements FaceFrameSource {
	/** Source type name. */
	public static final String NAME = "SEQUENCE";

	private final Path folder;
	private final double fps;
	private final boolean loop;
	private volatile List<Path> frames = List.of();
	private volatile int frameCount;
	private int index;
	private long sequence;

	/**
	 * Creates the source.
	 *
	 * @param folder folder that holds the JPEG frames
	 * @param fps    frame rate to pace the stream at
	 * @param loop   restart after the last frame when true
	 */
	public ImageSequenceSource(Path folder, double fps, boolean loop) {
		this.folder = folder;
		this.fps = FaceSourceSettings.checkFps(fps);
		this.loop = loop;
	}

	/** {@inheritDoc} */
	@Override
	public synchronized void open() throws IOException {
		if (!Files.isDirectory(folder))
			throw new NoSuchFileException(folder.toString());
		List<Path> found = new ArrayList<>();
		try (Stream<Path> listing = Files.list(folder)) {
			listing.filter(Files::isRegularFile).filter(ImageSequenceSource::isJpegName).sorted().forEach(found::add);
		}
		if (found.isEmpty())
			throw new IOException("No .jpg or .jpeg files in " + folder);
		frames = List.copyOf(found);
		frameCount = found.size();
		index = 0;
		sequence = 0;
	}

	/** {@inheritDoc} */
	@Override
	public synchronized FaceFrame next() throws IOException {
		List<Path> current = frames;
		if (current.isEmpty())
			return null;
		if (index >= current.size()) {
			if (!loop)
				return null;
			index = 0;
		}
		byte[] jpeg = Files.readAllBytes(current.get(index++));
		return new FaceFrame(jpeg, sequence++, System.currentTimeMillis(), NAME);
	}

	/**
	 * Returns the number of frames found by the last {@link #open()}.
	 *
	 * @return the frame count, 0 before the first open
	 */
	public int getFrameCount() {
		return frameCount;
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
		map.put("path", folder.toString());
		map.put("fps", fps);
		map.put("loop", loop);
		map.put("frameCount", frameCount);
		return map;
	}

	/** {@inheritDoc} */
	@Override
	public synchronized void close() {
		frames = List.of();
	}

	private static boolean isJpegName(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".jpg") || name.endsWith(".jpeg");
	}
}
