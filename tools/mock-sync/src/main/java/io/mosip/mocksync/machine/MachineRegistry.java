package io.mosip.mocksync.machine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.ClientCrypto;
import io.mosip.mocksync.crypto.ClientType;

/**
 * Registered machines, kept in {@code <work>/machines.json}. A machine must be known before its first login,
 * because the login response is encrypted to the machine's public key and no earlier request carries it.
 */
@Component
public class MachineRegistry {

	private static final Logger LOGGER = LoggerFactory.getLogger(MachineRegistry.class);
	private static final int FIRST_MACHINE_ID = 10001;

	private final Path file;
	private final MockSyncProperties properties;
	private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private final List<Machine> machines = new ArrayList<>();

	/**
	 * Loads the registry from the work directory.
	 *
	 * @param properties MockSync settings
	 */
	public MachineRegistry(MockSyncProperties properties) {
		this.properties = properties;
		this.file = Path.of(properties.getWorkDir()).resolve("machines.json");
		load();
	}

	/**
	 * Registers a machine or replaces the keys of an existing machine with the same name.
	 *
	 * @param name          machine name; stored in lower case
	 * @param publicKey     encryption public key, base64 or base64url
	 * @param signPublicKey signing public key, base64 or base64url; null means the same as the encryption key
	 * @param clientType    key type; null means TPM for TPM-format keys, else LOCAL
	 * @param registeredBy  source label for the admin listing
	 * @return the stored machine
	 */
	public synchronized Machine register(String name, String publicKey, String signPublicKey, ClientType clientType,
			String registeredBy) {
		if (name == null || name.isBlank() || publicKey == null || publicKey.isBlank()) {
			throw new IllegalArgumentException("name and publicKey are required");
		}
		byte[] encKey = ClientCrypto.decodeBase64Any(publicKey);
		byte[] signKey = signPublicKey == null || signPublicKey.isBlank() ? encKey
				: ClientCrypto.decodeBase64Any(signPublicKey);
		ClientType type = ClientCrypto.isTpmKey(encKey) ? ClientType.TPM
				: (clientType == null || clientType == ClientType.TPM ? ClientType.LOCAL : clientType);

		String lowerName = name.trim().toLowerCase(Locale.ROOT);
		Machine machine = findByName(lowerName).orElseGet(() -> {
			Machine created = new Machine();
			created.setName(lowerName);
			created.setMachineId(nextMachineId());
			created.setCenterId(properties.getCenterId());
			machines.add(created);
			return created;
		});
		machine.setClientType(type);
		machine.setPublicKey(ClientCrypto.base64Url(encKey));
		machine.setSignPublicKey(ClientCrypto.base64Url(signKey));
		machine.setKeyIndex(ClientCrypto.fingerprint(encKey));
		machine.setSignKeyIndex(ClientCrypto.fingerprint(signKey));
		machine.setActive(true);
		machine.setRegisteredAt(Instant.now().toString());
		machine.setRegisteredBy(registeredBy);
		save();
		LOGGER.info("Machine {} registered as {} ({}, keyIndex {})", lowerName, machine.getMachineId(), type,
				machine.getKeyIndex());
		return machine;
	}

	/**
	 * Registers a machine from the text of a desktop client's {@code .mosipkeys/readme.txt} (local keys only).
	 *
	 * @param readme file content with {@code MachineName:}, {@code PublicKey:} and {@code KeyIndex:} lines
	 * @return the stored machine
	 */
	public Machine registerFromReadme(String readme) {
		String name = null;
		String key = null;
		String index = null;
		for (String line : readme.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("MachineName:")) {
				name = trimmed.substring("MachineName:".length()).trim();
			} else if (trimmed.startsWith("PublicKey:")) {
				key = trimmed.substring("PublicKey:".length()).trim();
			} else if (trimmed.startsWith("KeyIndex:")) {
				index = trimmed.substring("KeyIndex:".length()).trim();
			}
		}
		Machine machine = register(name, key, null, ClientType.LOCAL, "readme");
		if (index != null && !index.equalsIgnoreCase(machine.getKeyIndex())) {
			LOGGER.warn("readme KeyIndex {} differs from the computed {}", index, machine.getKeyIndex());
		}
		return machine;
	}

	/**
	 * Finds a machine by the fingerprint of its encryption key.
	 *
	 * @param keyIndex key index as sent by the client
	 * @return machine
	 */
	public synchronized Optional<Machine> findByKeyIndex(String keyIndex) {
		return keyIndex == null ? Optional.empty()
				: machines.stream().filter(m -> keyIndex.equalsIgnoreCase(m.getKeyIndex())).findFirst();
	}

	/**
	 * Finds a machine by the fingerprint of its signing key (login JWT {@code kid}).
	 *
	 * @param signKeyIndex signing key fingerprint
	 * @return machine
	 */
	public synchronized Optional<Machine> findBySignKeyIndex(String signKeyIndex) {
		return signKeyIndex == null ? Optional.empty()
				: machines.stream().filter(m -> signKeyIndex.equalsIgnoreCase(m.getSignKeyIndex())).findFirst();
	}

	/**
	 * Finds a machine by name, ignoring case.
	 *
	 * @param name machine name
	 * @return machine
	 */
	public synchronized Optional<Machine> findByName(String name) {
		return name == null ? Optional.empty()
				: machines.stream().filter(m -> name.equalsIgnoreCase(m.getName())).findFirst();
	}

	/**
	 * Returns all machines.
	 *
	 * @return copy of the list
	 */
	public synchronized List<Machine> all() {
		return new ArrayList<>(machines);
	}

	/**
	 * Removes a machine.
	 *
	 * @param name machine name
	 * @return true if a machine was removed
	 */
	public synchronized boolean remove(String name) {
		boolean removed = machines.removeIf(m -> m.getName().equalsIgnoreCase(name));
		if (removed) {
			save();
		}
		return removed;
	}

	private String nextMachineId() {
		int max = machines.stream().mapToInt(m -> Integer.parseInt(m.getMachineId())).max().orElse(FIRST_MACHINE_ID - 1);
		return String.valueOf(max + 1);
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			machines.addAll(mapper.readValue(file.toFile(), new TypeReference<List<Machine>>() {
			}));
			LOGGER.info("Loaded {} registered machine(s) from {}", machines.size(), file.toAbsolutePath());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + file, e);
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			mapper.writeValue(temp.toFile(), machines);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + file, e);
		}
	}
}
