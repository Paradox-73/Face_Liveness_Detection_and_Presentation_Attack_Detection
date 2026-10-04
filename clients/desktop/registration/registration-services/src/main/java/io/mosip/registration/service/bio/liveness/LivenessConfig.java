/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.policy.PolicyResolution;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.dao.GlobalParamDAO;
import io.mosip.registration.dao.LocalConfigDAO;

/**
 * Liveness configuration of the desktop client: a {@link ConfigSource} over {@link ApplicationContext#map()}.
 *
 * <p>The map holds the synced {@code GLOBAL_PARAM} values, the local preferences an operator saved in the
 * settings screen ({@code LOCAL_PREFERENCES}, copied over the synced values at start-up) and the
 * {@code spring.properties} defaults, so the same values apply online and offline. Values
 * are read at each call, which means a config sync takes effect at the next session. Missing, blank or
 * malformed values never cause an exception: the core {@link PolicyResolver} applies the shipped defaults,
 * clamps values to their floors and ranges, and reports each correction as a {@code ConfigIssue}.
 *
 * <p><b>Local preferences.</b> A local value of a liveness key applies only when
 * {@link LivenessConfigKeys#isLocalOverrideAllowed(String)} accepts the key (thresholds, timeouts, attempts,
 * diagnostic mode). For every other liveness key (for example the master switch or a workflow's policy) the synced
 * value applies, or the shipped default when none was synced, so a local preference cannot switch liveness off or
 * weaken a workflow's policy. The list of local liveness preferences is read again at most every
 * {@value #LOCAL_REFRESH_MS} ms.
 *
 * <p>Thread-safe.
 */
@Component
public class LivenessConfig implements ConfigSource {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessConfig.class);

	/** The client's lock time after too many failed logins, in minutes (mosip-config key). */
	static final String INVALID_LOGIN_TIME_KEY = "mosip.registration.invalid_login_time";
	static final long DEFAULT_AUTH_BLOCK_MINUTES = 2;
	static final long RESIDENT_BLOCK_MINUTES = 12 * 60;
	/** The client's number of failed logins before the account locks (mosip-config key). */
	static final String INVALID_LOGIN_COUNT_KEY = "mosip.registration.invalid_login_count";
	static final int DEFAULT_INVALID_LOGIN_COUNT = 3;
	/** Folder that holds model bundles imported with {@link LivenessModelImporter} (desktop-only key). */
	public static final String MODEL_STORE_KEY = LivenessConfigKeys.PREFIX + "model.store_dir";
	/** Default model store folder, relative to the client's working folder. */
	public static final String DEFAULT_MODEL_STORE = "liveness-models";
	/** Longest time between two reads of the local liveness preferences, in milliseconds. */
	static final long LOCAL_REFRESH_MS = 5_000L;

	private final Supplier<Map<String, Object>> values;
	private volatile Supplier<Map<String, String>> localValues = Collections::emptyMap;
	private volatile Supplier<Map<String, Object>> syncedValues = Collections::emptyMap;
	/** Liveness keys with a refused local value, mapped to the synced value that applies instead. */
	private volatile Map<String, Optional<String>> refusedLocal = Collections.emptyMap();
	private volatile long refusedLoadedAt;
	private volatile boolean refusedLoaded;
	private Map<String, String> lastSnapshot;

	/** Creates the configuration backed by the client's application map. */
	public LivenessConfig() {
		this(ApplicationContext::map);
	}

	/**
	 * Creates a configuration backed by the given map supplier (tests and tools).
	 *
	 * @param values supplier of the current key-value map; may return {@code null}
	 */
	public LivenessConfig(Supplier<Map<String, Object>> values) {
		this.values = Objects.requireNonNull(values, "values");
	}

	/**
	 * Creates a configuration over a fixed map (tests and tools).
	 *
	 * @param values key-value pairs; read directly, not copied
	 * @return the configuration
	 */
	public static LivenessConfig of(Map<String, Object> values) {
		Objects.requireNonNull(values, "values");
		return new LivenessConfig(() -> values);
	}

	/**
	 * Connects the client's local preferences and synced global parameters, so that a local value of a liveness
	 * key outside the core allow-list is replaced by the synced value.
	 *
	 * @param localConfigDAO local preferences saved in the settings screen
	 * @param globalParamDAO synced global parameters
	 */
	@Autowired(required = false)
	public void setLocalSettings(LocalConfigDAO localConfigDAO, GlobalParamDAO globalParamDAO) {
		Objects.requireNonNull(localConfigDAO, "localConfigDAO");
		Objects.requireNonNull(globalParamDAO, "globalParamDAO");
		setLocalSettings(localConfigDAO::getLocalConfigurations,
				() -> globalParamDAO.getGlobalParams(LivenessConfigKeys.PREFIX + "%"));
	}

	/**
	 * Sets where the local preferences and the synced global parameters are read (tests and tools).
	 *
	 * @param localValues  supplier of the local preferences (key to value); may return {@code null}
	 * @param syncedValues supplier of the synced global parameters (key to value); may return {@code null}
	 */
	void setLocalSettings(Supplier<Map<String, String>> localValues, Supplier<Map<String, Object>> syncedValues) {
		this.localValues = Objects.requireNonNull(localValues, "localValues");
		this.syncedValues = Objects.requireNonNull(syncedValues, "syncedValues");
		this.refusedLoaded = false;
	}

	/**
	 * Reads one value. Never throws and never returns {@code null}. A local preference of a liveness key outside
	 * the core allow-list is ignored: the synced value is returned instead.
	 *
	 * @param key full configuration key
	 * @return the trimmed value, or empty when the key is missing, blank or the map is unavailable
	 */
	@Override
	public Optional<String> get(String key) {
		if (key == null) {
			return Optional.empty();
		}
		Optional<String> synced = refusedLocal().get(key);
		if (synced != null) {
			return synced;
		}
		Object raw;
		try {
			Map<String, Object> map = values.get();
			raw = map == null ? null : map.get(key);
		} catch (RuntimeException e) {
			raw = null;
		}
		return text(raw);
	}

	/**
	 * Returns the liveness keys whose local preference is ignored because the core allow-list does not accept them.
	 *
	 * @return key names in sorted order; empty when every local liveness preference is allowed
	 */
	public List<String> refusedLocalKeys() {
		List<String> keys = new ArrayList<>(refusedLocal().keySet());
		Collections.sort(keys);
		return keys;
	}

	private Map<String, Optional<String>> refusedLocal() {
		long now = System.nanoTime();
		if (refusedLoaded && now - refusedLoadedAt < LOCAL_REFRESH_MS * 1_000_000L) {
			return refusedLocal;
		}
		synchronized (this) {
			if (!refusedLoaded || now - refusedLoadedAt >= LOCAL_REFRESH_MS * 1_000_000L) {
				Map<String, Optional<String>> refused = loadRefusedLocal();
				if (!refused.keySet().equals(refusedLocal.keySet()) && !refused.isEmpty()) {
					LOGGER.info("Local liveness preferences ignored, synced values apply: {}", refused.keySet());
				}
				refusedLocal = refused;
				refusedLoadedAt = now;
				refusedLoaded = true;
			}
			return refusedLocal;
		}
	}

	private Map<String, Optional<String>> loadRefusedLocal() {
		Map<String, Optional<String>> refused = new TreeMap<>();
		try {
			Map<String, String> local = localValues.get();
			if (local == null || local.isEmpty()) {
				return refused;
			}
			Map<String, Object> synced = null;
			for (Map.Entry<String, String> e : new ArrayList<>(local.entrySet())) {
				String key = e.getKey();
				if (key == null || !key.startsWith(LivenessConfigKeys.PREFIX)
						|| LivenessConfigKeys.isLocalOverrideAllowed(key)) {
					continue;
				}
				if (synced == null) {
					synced = syncedValues.get();
					if (synced == null) {
						synced = Collections.emptyMap();
					}
				}
				refused.put(key, text(synced.get(key)));
			}
		} catch (RuntimeException e) {
			// Database not ready: keep the previous list rather than letting a local value through.
			LOGGER.error("Local liveness preferences could not be read: {}", e.getClass().getSimpleName());
			return new TreeMap<>(refusedLocal);
		}
		return refused;
	}

	private static Optional<String> text(Object raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String text = String.valueOf(raw).trim();
		return text.isEmpty() ? Optional.empty() : Optional.of(text);
	}

	/**
	 * Resolves the policy of one workflow, with defaults, floors and ranges applied.
	 *
	 * @param workflow the workflow
	 * @return the resolution: the policy plus any corrected values
	 */
	public PolicyResolution resolve(Workflow workflow) {
		return new PolicyResolver(this).resolve(workflow);
	}

	/**
	 * Returns the resolved policy of one workflow.
	 *
	 * @param workflow the workflow
	 * @return the policy; mode {@code OFF} when the master switch is off
	 */
	public LivenessPolicy policy(Workflow workflow) {
		return resolve(workflow).policy();
	}

	/**
	 * Tells whether liveness is enforced for a workflow (master switch on and policy not {@code OFF}).
	 *
	 * @param workflow the workflow
	 * @return {@code true} if a face capture in this workflow needs a liveness pass
	 */
	public boolean isEnforced(Workflow workflow) {
		return policy(workflow).isEnabled();
	}

	/**
	 * Tells whether liveness is enforced for at least one of the given workflows.
	 *
	 * @param workflows workflows to check
	 * @return {@code true} if any of them needs a liveness pass
	 */
	public boolean isEnforcedForAny(Workflow... workflows) {
		for (Workflow w : workflows) {
			if (isEnforced(w)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the configured engine identifier.
	 *
	 * @return {@code mosip.registration.liveness.engine}, default {@code mosip-onnx-default}
	 */
	public String engineId() {
		return get(LivenessConfigKeys.ENGINE).orElse(LivenessConfigKeys.DEFAULT_ENGINE);
	}

	/**
	 * Returns the minimum accepted model bundle version.
	 *
	 * @return {@code mosip.registration.liveness.model.min_version}, default {@code 1.0.0}
	 */
	public String modelMinVersion() {
		return get(LivenessConfigKeys.MODEL_MIN_VERSION).orElse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION);
	}

	/**
	 * Returns the folder of imported model bundles.
	 *
	 * @return {@value #MODEL_STORE_KEY}, default {@value #DEFAULT_MODEL_STORE} in the working folder
	 */
	public Path modelStoreDirectory() {
		return Paths.get(get(MODEL_STORE_KEY).orElse(DEFAULT_MODEL_STORE));
	}

	/**
	 * Returns how many failed logins lock an operator account.
	 *
	 * @return {@value #INVALID_LOGIN_COUNT_KEY}, default {@value #DEFAULT_INVALID_LOGIN_COUNT}, at least 1
	 */
	public int invalidLoginCount() {
		try {
			return Math.max(1, Integer.parseInt(get(INVALID_LOGIN_COUNT_KEY)
					.orElse(String.valueOf(DEFAULT_INVALID_LOGIN_COUNT))));
		} catch (NumberFormatException e) {
			return DEFAULT_INVALID_LOGIN_COUNT;
		}
	}

	/**
	 * Returns how long an operator account stays locked after too many failed logins.
	 *
	 * @return {@value #INVALID_LOGIN_TIME_KEY} in minutes, default {@value #DEFAULT_AUTH_BLOCK_MINUTES}, at least 1
	 */
	public int invalidLoginMinutes() {
		return (int) exhaustedBlockMinutes(Workflow.LOGIN_AUTH);
	}

	/**
	 * Tells whether the master switch is on.
	 *
	 * @return {@code mosip.registration.liveness.enable}; an unreadable value keeps the default (on)
	 */
	public boolean isMasterSwitchOn() {
		return yesNo(LivenessConfigKeys.ENABLE, true);
	}

	/**
	 * Tells whether diagnostic log lines are written.
	 *
	 * @return {@code mosip.registration.liveness.diagnostic.enable}, default off
	 */
	public boolean isDiagnosticEnabled() {
		return yesNo(LivenessConfigKeys.DIAGNOSTIC_ENABLE, false);
	}

	/**
	 * Tells whether anonymised metrics are collected.
	 *
	 * @return {@code mosip.registration.liveness.metrics.enable}, default on
	 */
	public boolean isMetricsEnabled() {
		return yesNo(LivenessConfigKeys.METRICS_ENABLE, true);
	}

	/**
	 * Returns how long a person stays blocked in a workflow after a liveness transaction used up its attempts.
	 * Operator and supervisor workflows follow the client's invalid-login lock time
	 * ({@value #INVALID_LOGIN_TIME_KEY}, minutes, default {@value #DEFAULT_AUTH_BLOCK_MINUTES}). Resident
	 * workflows are keyed by the registration ID, which is never reused, so the block simply lasts
	 * {@value #RESIDENT_BLOCK_MINUTES} minutes.
	 *
	 * @param workflow the workflow
	 * @return block duration in minutes, at least 1
	 */
	public long exhaustedBlockMinutes(Workflow workflow) {
		if (!workflow.isAuth()) {
			return RESIDENT_BLOCK_MINUTES;
		}
		try {
			return Math.max(1, Long.parseLong(get(INVALID_LOGIN_TIME_KEY).orElse(String.valueOf(DEFAULT_AUTH_BLOCK_MINUTES))));
		} catch (NumberFormatException e) {
			return DEFAULT_AUTH_BLOCK_MINUTES;
		}
	}

	/**
	 * Returns the liveness keys whose value changed since the previous call (added, removed or modified). The
	 * first call records the baseline and reports nothing. Used for audit {@code REG-EVT-LV15}.
	 *
	 * @return changed key names in sorted order; empty if nothing changed
	 */
	public synchronized List<String> changedKeysSinceLastCheck() {
		Map<String, String> current = snapshot();
		if (lastSnapshot == null) {
			lastSnapshot = current;
			return Collections.emptyList();
		}
		List<String> changed = new ArrayList<>();
		for (Map.Entry<String, String> e : current.entrySet()) {
			if (!e.getValue().equals(lastSnapshot.get(e.getKey()))) {
				changed.add(e.getKey());
			}
		}
		for (String key : lastSnapshot.keySet()) {
			if (!current.containsKey(key)) {
				changed.add(key);
			}
		}
		Collections.sort(changed);
		lastSnapshot = current;
		return changed;
	}

	private Map<String, String> snapshot() {
		Map<String, String> result = new TreeMap<>();
		try {
			Map<String, Object> map = values.get();
			if (map == null) {
				return result;
			}
			for (Map.Entry<String, Object> e : new ArrayList<>(map.entrySet())) {
				if (e.getKey() != null && e.getKey().startsWith(LivenessConfigKeys.PREFIX) && e.getValue() != null) {
					result.put(e.getKey(), String.valueOf(e.getValue()).trim());
				}
			}
		} catch (RuntimeException e) {
			// The application map is a plain HashMap updated by the sync job; a concurrent change only delays
			// the change report to the next call.
			return lastSnapshot == null ? result : lastSnapshot;
		}
		for (Map.Entry<String, Optional<String>> e : refusedLocal().entrySet()) {
			if (e.getValue().isPresent()) {
				result.put(e.getKey(), e.getValue().get());
			} else {
				result.remove(e.getKey());
			}
		}
		return result;
	}

	private boolean yesNo(String key, boolean defaultValue) {
		Optional<String> v = get(key);
		if (!v.isPresent()) {
			return defaultValue;
		}
		String s = v.get().toUpperCase(Locale.ROOT);
		if ("Y".equals(s) || "YES".equals(s) || "TRUE".equals(s)) {
			return true;
		}
		if ("N".equals(s) || "NO".equals(s) || "FALSE".equals(s)) {
			return false;
		}
		return defaultValue;
	}
}
