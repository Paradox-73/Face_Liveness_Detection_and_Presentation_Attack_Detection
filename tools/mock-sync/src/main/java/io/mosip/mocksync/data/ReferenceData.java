package io.mosip.mocksync.data;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.config.MockSyncProperties;

/**
 * Read-only access to the committed reference data under the data directory:
 * <ul>
 * <li>{@code upstream/mosip-data/<table>.json}: mosip-data 1.2.0.1 master tables converted by
 * {@code scripts/fetch-data.py} (one object per spreadsheet row, keys are column names);</li>
 * <li>{@code upstream/mosip-config/*}: mosip-config v1.2.0.1 files;</li>
 * <li>{@code client-seed/initial-global-params.properties}, {@code mock-sync-defaults.properties},
 * {@code users.json}: MockSync's own files.</li>
 * </ul>
 */
@Component
public class ReferenceData {

	private final Path dataDir;
	private final ObjectMapper mapper = new ObjectMapper();
	private final Map<String, List<Map<String, Object>>> tables = new ConcurrentHashMap<>();

	/**
	 * Creates the reader.
	 *
	 * @param properties MockSync settings (data directory)
	 */
	public ReferenceData(MockSyncProperties properties) {
		this.dataDir = Path.of(properties.getDataDir());
		if (!Files.isDirectory(dataDir.resolve("upstream"))) {
			throw new IllegalStateException("Reference data not found under " + dataDir.toAbsolutePath()
					+ " (start MockSync from tools/mock-sync or set mocksync.data-dir)");
		}
	}

	/**
	 * Returns the rows of one mosip-data table.
	 *
	 * @param table table name, e.g. {@code registration_center}
	 * @return rows (column name to value); empty when the snapshot has no such table
	 */
	public List<Map<String, Object>> table(String table) {
		return tables.computeIfAbsent(table, name -> {
			Path file = dataDir.resolve("upstream").resolve("mosip-data").resolve(name + ".json");
			if (!Files.exists(file)) {
				return Collections.emptyList();
			}
			try {
				Map<String, Object> snapshot = mapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
				});
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> rows = (List<Map<String, Object>>) snapshot.get("rows");
				return Collections.unmodifiableList(rows);
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot read " + file, e);
			}
		});
	}

	/**
	 * Reads a properties file from the data directory, keeping file order.
	 *
	 * @param relativePath path under the data directory
	 * @return key/value map in file order; empty if the file does not exist
	 */
	public Map<String, String> properties(String relativePath) {
		Path file = dataDir.resolve(relativePath);
		Map<String, String> ordered = new LinkedHashMap<>();
		if (!Files.exists(file)) {
			return ordered;
		}
		Properties props = new Properties() {
			private static final long serialVersionUID = 1L;

			@Override
			public synchronized Object put(Object key, Object value) {
				ordered.put(String.valueOf(key), String.valueOf(value));
				return super.put(key, value);
			}
		};
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(reader);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + file, e);
		}
		return ordered;
	}

	/**
	 * Reads a file from the data directory as bytes.
	 *
	 * @param relativePath path under the data directory
	 * @return file bytes
	 */
	public byte[] bytes(String relativePath) {
		try {
			return Files.readAllBytes(dataDir.resolve(relativePath));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + relativePath, e);
		}
	}

	/**
	 * Reads a JSON file from the data directory.
	 *
	 * @param relativePath path under the data directory
	 * @param type         target type
	 * @param <T>          result type
	 * @return parsed value
	 */
	public <T> T json(String relativePath, TypeReference<T> type) {
		try {
			return mapper.readValue(dataDir.resolve(relativePath).toFile(), type);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + relativePath, e);
		}
	}

	/**
	 * Returns the data directory.
	 *
	 * @return path
	 */
	public Path getDataDir() {
		return dataDir;
	}
}
