/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.data;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.mosip.mocksync.config.MockSyncProperties;

/**
 * Builds the two maps the {@code configs/{key_index}} endpoint returns and keeps admin overrides.
 * <p>
 * {@code globalConfiguration} is mosip-config {@code application-default.properties}. {@code registrationConfiguration}
 * is, from lowest to highest precedence: the client's own {@code initial.sql} seed rows (so a sync does not delete
 * them from the client's GLOBAL_PARAM table), mosip-config {@code registration-default.properties},
 * {@code data/mock-sync-defaults.properties} (liveness keys and demo settings) and {@code <work>/config-overrides.properties}
 * (changes made through {@code /admin/config}). {@code ${key}} and {@code ${key:default}} placeholders are resolved
 * against all layers; keys that stay unresolved are dropped, and {@code mosip.hostname} is never sent because the
 * client would start calling that host.
 */
@Component
public class ConfigService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);
	/**
	 * Keys MockSync never sends: the server host (the client would start calling it) and the state keys the desktop
	 * client keeps for itself ({@code GlobalParamServiceImpl.NON_REMOVABLE_PARAMS}). A synced value for a state key
	 * overwrites the client's own value; a synced {@code initial_setup=Y}, for example, makes the client restart
	 * after every online login.
	 */
	private static final Set<String> NEVER_SENT = Set.of("mosip.hostname", "mosip.registration.machinecenterchanged",
			"mosip.registration.initial_setup", "mosip.reg.db.current.version", "mosip.reg.services.version",
			"mosip.registration.is_software_update_available", "mosip.reg.services.current.version",
			"mosip.registration.HTTP_API_READ_TIMEOUT", "mosip.registration.HTTP_API_WRITE_TIMEOUT",
			"mosip.registration.last_software_update", "mosip.registration.regclient_installed_time",
			"mosip.registration.audit_timestamp");
	private static final int MAX_DEPTH = 10;

	private final ReferenceData data;
	private final Path overridesFile;
	private final Map<String, String> overrides = new TreeMap<>();

	/**
	 * Creates the service and loads saved overrides.
	 *
	 * @param data       reference data
	 * @param properties MockSync settings (work directory)
	 */
	public ConfigService(ReferenceData data, MockSyncProperties properties) {
		this.data = data;
		this.overridesFile = Path.of(properties.getWorkDir()).resolve("config-overrides.properties");
		loadOverrides();
	}

	/**
	 * Returns the resolved {@code globalConfiguration} map.
	 *
	 * @return key/value map
	 */
	public synchronized Map<String, String> globalConfiguration() {
		return resolveAll(data.properties("upstream/mosip-config/application-default.properties"), allLayers());
	}

	/**
	 * Returns the resolved {@code registrationConfiguration} map.
	 *
	 * @return key/value map
	 */
	public synchronized Map<String, String> registrationConfiguration() {
		Map<String, String> global = data.properties("upstream/mosip-config/application-default.properties");
		Map<String, String> layered = new LinkedHashMap<>();
		data.properties("client-seed/initial-global-params.properties").forEach((key, value) -> {
			if (!global.containsKey(key)) {
				layered.put(key, value);
			}
		});
		layered.putAll(data.properties("upstream/mosip-config/registration-default.properties"));
		layered.putAll(data.properties("mock-sync-defaults.properties"));
		layered.putAll(overrides);
		return resolveAll(layered, allLayers());
	}

	/**
	 * Returns the value a client receives for one key after all layers and placeholder resolution.
	 *
	 * @param key config key
	 * @return value or null when the key is not sent
	 */
	public synchronized String effectiveValue(String key) {
		String value = registrationConfiguration().get(key);
		return value != null ? value : globalConfiguration().get(key);
	}

	/**
	 * Sets an override. It reaches clients on their next config sync.
	 *
	 * @param key   config key
	 * @param value new value
	 */
	public synchronized void setOverride(String key, String value) {
		if (NEVER_SENT.contains(key)) {
			throw new IllegalArgumentException(key + " is kept by the client and is not served by MockSync");
		}
		overrides.put(key, value);
		saveOverrides();
		LOGGER.info("Config override set: {}", key);
	}

	/**
	 * Removes an override, going back to the value from the reference data.
	 *
	 * @param key config key
	 * @return true if an override was removed
	 */
	public synchronized boolean removeOverride(String key) {
		boolean removed = overrides.remove(key) != null;
		if (removed) {
			saveOverrides();
			LOGGER.info("Config override removed: {}", key);
		}
		return removed;
	}

	/**
	 * Returns the current overrides.
	 *
	 * @return sorted copy
	 */
	public synchronized Map<String, String> overrides() {
		return new TreeMap<>(overrides);
	}

	/**
	 * Resolves {@code ${key}} and {@code ${key:default}} placeholders in one value.
	 *
	 * @param value  raw value
	 * @param lookup values placeholders may refer to
	 * @return resolved value, or null when a placeholder cannot be resolved
	 */
	public static String resolve(String value, Map<String, String> lookup) {
		return resolve(value, lookup, 0);
	}

	private Map<String, String> allLayers() {
		Map<String, String> all = new LinkedHashMap<>();
		putLayer(all, data.properties("client-seed/initial-global-params.properties"));
		putLayer(all, data.properties("upstream/mosip-config/application-default.properties"));
		putLayer(all, data.properties("upstream/mosip-config/registration-default.properties"));
		putLayer(all, data.properties("mock-sync-defaults.properties"));
		putLayer(all, overrides);
		return all;
	}

	/** Adds a layer, skipping {@code key=${key}} lines that only point back to a lower layer. */
	private static void putLayer(Map<String, String> target, Map<String, String> layer) {
		layer.forEach((key, value) -> {
			if (!value.trim().equals("${" + key + "}") || !target.containsKey(key)) {
				target.put(key, value);
			}
		});
	}

	private static Map<String, String> resolveAll(Map<String, String> source, Map<String, String> lookup) {
		Map<String, String> resolved = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : source.entrySet()) {
			if (NEVER_SENT.contains(entry.getKey())) {
				continue;
			}
			String value = resolve(entry.getValue(), lookup, 0);
			if (value != null) {
				resolved.put(entry.getKey(), value);
			}
		}
		return resolved;
	}

	private static String resolve(String value, Map<String, String> lookup, int depth) {
		if (value == null || depth > MAX_DEPTH) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		int index = 0;
		while (index < value.length()) {
			int start = value.indexOf("${", index);
			if (start < 0) {
				out.append(value, index, value.length());
				break;
			}
			int end = matchingBrace(value, start + 2);
			if (end < 0) {
				return null;
			}
			out.append(value, index, start);
			String replacement = placeholder(value.substring(start + 2, end), lookup, depth);
			if (replacement == null) {
				return null;
			}
			out.append(replacement);
			index = end + 1;
		}
		return out.toString();
	}

	private static String placeholder(String expression, Map<String, String> lookup, int depth) {
		int colon = defaultSeparator(expression);
		String key = colon < 0 ? expression : expression.substring(0, colon);
		String raw = lookup.get(key);
		if (raw != null && !raw.equals("${" + key + "}")) {
			return resolve(raw, lookup, depth + 1);
		}
		return colon < 0 ? null : resolve(expression.substring(colon + 1), lookup, depth + 1);
	}

	private static int defaultSeparator(String expression) {
		int nesting = 0;
		for (int i = 0; i < expression.length(); i++) {
			char c = expression.charAt(i);
			if (c == '{') {
				nesting++;
			} else if (c == '}') {
				nesting--;
			} else if (c == ':' && nesting == 0) {
				return i;
			}
		}
		return -1;
	}

	private static int matchingBrace(String value, int from) {
		int nesting = 1;
		for (int i = from; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '{') {
				nesting++;
			} else if (c == '}' && --nesting == 0) {
				return i;
			}
		}
		return -1;
	}

	private void loadOverrides() {
		if (!Files.exists(overridesFile)) {
			return;
		}
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(overridesFile, StandardCharsets.UTF_8)) {
			props.load(reader);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + overridesFile, e);
		}
		props.forEach((key, value) -> overrides.put(String.valueOf(key), String.valueOf(value)));
		LOGGER.info("Loaded {} config override(s) from {}", overrides.size(), overridesFile.toAbsolutePath());
	}

	private void saveOverrides() {
		Properties props = new Properties();
		props.putAll(overrides);
		try {
			Files.createDirectories(overridesFile.toAbsolutePath().getParent());
			try (Writer writer = Files.newBufferedWriter(overridesFile, StandardCharsets.UTF_8)) {
				props.store(writer, "MockSync config overrides (edited through /admin/config)");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + overridesFile, e);
		}
	}
}
