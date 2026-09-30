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

import org.springframework.stereotype.Component;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.policy.PolicyResolution;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.context.ApplicationContext;

/**
 * Liveness configuration of the desktop client: a {@link ConfigSource} over {@link ApplicationContext#map()}.
 *
 * <p>The map holds the synced {@code GLOBAL_PARAM} values, the permitted local overrides and the
 * {@code spring.properties} defaults, so the same values apply online and offline (design section 4.11). Values
 * are read at each call, which means a config sync takes effect at the next session. Missing, blank or
 * malformed values never cause an exception: the core {@link PolicyResolver} applies the shipped defaults,
 * clamps values to their floors and ranges, and reports each correction as a {@code ConfigIssue}.
 *
 * <p>Thread-safe.
 */
@Component
public class LivenessConfig implements ConfigSource {

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

	private final Supplier<Map<String, Object>> values;
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
	 * Reads one value. Never throws and never returns {@code null}.
	 *
	 * @param key full configuration key
	 * @return the trimmed value, or empty when the key is missing, blank or the map is unavailable
	 */
	@Override
	public Optional<String> get(String key) {
		if (key == null) {
			return Optional.empty();
		}
		Object raw;
		try {
			Map<String, Object> map = values.get();
			raw = map == null ? null : map.get(key);
		} catch (RuntimeException e) {
			raw = null;
		}
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
