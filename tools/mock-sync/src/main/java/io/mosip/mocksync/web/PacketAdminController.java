package io.mosip.mocksync.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.ServerKeys;
import io.mosip.mocksync.packet.PacketInspector;

/**
 * Admin API for uploaded registration packets (loopback only, see {@link LoopbackOnlyFilter}): list the packets in
 * {@code <work>/packets} and show the face liveness evidence of one of them ({@link PacketInspector}). Never
 * returns biometric data.
 */
@RestController
@RequestMapping("/admin/packets")
public class PacketAdminController {

	private final Path packetsDir;
	private final ServerKeys keys;

	/**
	 * Creates the controller.
	 *
	 * @param properties MockSync settings (work directory)
	 * @param keys       server keys (REGISTRATION keys decrypt the packets)
	 */
	public PacketAdminController(MockSyncProperties properties, ServerKeys keys) {
		this.packetsDir = Path.of(properties.getWorkDir()).resolve("packets");
		this.keys = keys;
	}

	/**
	 * Lists the uploaded packets, newest first.
	 *
	 * @return name, size and upload time of each packet
	 * @throws IOException if the folder cannot be read
	 */
	@GetMapping
	public List<Map<String, Object>> list() throws IOException {
		if (!Files.isDirectory(packetsDir)) {
			return new ArrayList<>();
		}
		try (Stream<Path> files = Files.list(packetsDir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".zip"))
					.sorted(Comparator.comparing(PacketAdminController::modified).reversed()).map(p -> {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("name", p.getFileName().toString());
						row.put("bytes", p.toFile().length());
						row.put("uploaded", modified(p).toString());
						return row;
					}).collect(Collectors.toList());
		}
	}

	/**
	 * Decrypts one uploaded packet and returns its liveness evidence: {@code LIVENESS_*} entries of every biometric
	 * record, the liveness fields of the packet meta info and the {@code REG-EVT-LV*} audit rows.
	 *
	 * @param name packet file name, or {@code latest}
	 * @return the report, 404 if there is no such packet, 422 if it does not decrypt
	 * @throws IOException if the file cannot be read
	 */
	@GetMapping("/{name:.+}/liveness")
	public ResponseEntity<Map<String, Object>> liveness(@PathVariable String name) throws IOException {
		Path file = "latest".equals(name) ? latest() : packetsDir.resolve(Path.of(name).getFileName().toString());
		if (file == null || !Files.isRegularFile(file)) {
			return ResponseEntity.notFound().build();
		}
		String packetName = file.getFileName().toString();
		try {
			return ResponseEntity.ok(PacketInspector.inspect(packetName, Files.readAllBytes(file),
					keys.registration(PacketInspector.referenceId(packetName)).getPrivateKey()));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("packet", packetName);
			error.put("error", "packet could not be decrypted: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
		}
	}

	private Path latest() throws IOException {
		if (!Files.isDirectory(packetsDir)) {
			return null;
		}
		try (Stream<Path> files = Files.list(packetsDir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".zip"))
					.max(Comparator.comparing(PacketAdminController::modified)).orElse(null);
		}
	}

	private static Instant modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toInstant();
		} catch (IOException e) {
			return Instant.EPOCH;
		}
	}
}
