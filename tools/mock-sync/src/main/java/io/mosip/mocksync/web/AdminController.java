package io.mosip.mocksync.web;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.mosip.mocksync.crypto.ClientType;
import io.mosip.mocksync.crypto.ServerKeys;
import io.mosip.mocksync.data.ConfigService;
import io.mosip.mocksync.data.UserStore;
import io.mosip.mocksync.machine.Machine;
import io.mosip.mocksync.machine.MachineRegistry;

/**
 * Admin API (loopback only, see {@link LoopbackOnlyFilter}): register machines, change config values that clients
 * pick up on their next config sync, and list test users. The scripts in {@code tools/mock-sync/scripts} call it.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

	private final MachineRegistry machines;
	private final ConfigService config;
	private final UserStore users;
	private final ServerKeys keys;

	/**
	 * Creates the controller.
	 *
	 * @param machines machine registry
	 * @param config   config service
	 * @param users    operator accounts
	 * @param keys     server keys
	 */
	public AdminController(MachineRegistry machines, ConfigService config, UserStore users, ServerKeys keys) {
		this.machines = machines;
		this.config = config;
		this.users = users;
		this.keys = keys;
	}

	/**
	 * Lists registered machines.
	 *
	 * @return machines
	 */
	@GetMapping("/machines")
	public List<Machine> listMachines() {
		return machines.all();
	}

	/**
	 * Registers a machine or replaces its keys.
	 *
	 * @param request {@code name}, {@code publicKey}, optional {@code signPublicKey} and {@code clientType}
	 *                (TPM, ANDROID, LOCAL)
	 * @return stored machine
	 */
	@PostMapping(path = "/machines", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Machine registerMachine(@RequestBody Map<String, String> request) {
		String type = request.get("clientType");
		return machines.register(request.get("name"), request.get("publicKey"), request.get("signPublicKey"),
				type == null || type.isBlank() ? null : ClientType.valueOf(type.trim().toUpperCase()), "admin");
	}

	/**
	 * Registers a desktop machine from its {@code .mosipkeys/readme.txt} (local, non-TPM keys).
	 *
	 * @param readme file content
	 * @return stored machine
	 */
	@PostMapping(path = "/machines/readme", consumes = MediaType.TEXT_PLAIN_VALUE)
	public Machine registerFromReadme(@RequestBody String readme) {
		return machines.registerFromReadme(readme);
	}

	/**
	 * Removes a machine.
	 *
	 * @param name machine name
	 * @return 204 or 404
	 */
	@DeleteMapping("/machines/{name}")
	public ResponseEntity<Void> removeMachine(@PathVariable String name) {
		return machines.remove(name) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	/**
	 * Shows the admin overrides and the effective liveness keys.
	 *
	 * @return {@code overrides} and {@code liveness} maps
	 */
	@GetMapping("/config")
	public Map<String, Object> config() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("overrides", config.overrides());
		result.put("liveness", config.registrationConfiguration().entrySet().stream()
				.filter(e -> e.getKey().startsWith("mosip.registration.liveness.")
						|| e.getKey().startsWith("mosip.registration.mds.")
						|| e.getKey().startsWith("mosip.registration.android."))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, java.util.TreeMap::new)));
		return result;
	}

	/**
	 * Returns the value clients receive for one key.
	 *
	 * @param key config key
	 * @return key and value, or 404 when the key is not served
	 */
	@GetMapping("/config/{key:.+}")
	public ResponseEntity<Map<String, String>> configValue(@PathVariable String key) {
		String value = config.effectiveValue(key);
		return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of("key", key, "value", value));
	}

	/**
	 * Sets one or more overrides; clients receive them on their next config sync.
	 *
	 * @param values key/value pairs
	 * @return all overrides after the change
	 */
	@PutMapping(path = "/config", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, String> setConfig(@RequestBody Map<String, String> values) {
		values.forEach(config::setOverride);
		return config.overrides();
	}

	/**
	 * Removes one override.
	 *
	 * @param key config key
	 * @return 204 or 404
	 */
	@DeleteMapping("/config/{key:.+}")
	public ResponseEntity<Void> removeConfig(@PathVariable String key) {
		return config.removeOverride(key) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	/**
	 * Lists the test operator accounts (without passwords).
	 *
	 * @return user ID, name and roles
	 */
	@GetMapping("/users")
	public List<Map<String, Object>> users() {
		return users.all().stream().map(user -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("userId", user.getUserId());
			row.put("name", user.getName());
			row.put("roles", user.getRoles());
			return row;
		}).collect(Collectors.toList());
	}

	/**
	 * Downloads the HTTPS certificate to import into the client's Java trust store.
	 *
	 * @return PEM certificate
	 * @throws IOException if the file cannot be read
	 */
	@GetMapping(path = "/tls-certificate", produces = "application/x-pem-file")
	public byte[] tlsCertificate() throws IOException {
		return Files.readAllBytes(keys.getKeysDir().resolve("tls-cert.pem"));
	}

	/**
	 * Maps bad admin input to HTTP 400.
	 *
	 * @param e the exception
	 * @return error message
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
	}
}
