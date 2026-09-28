package io.mosip.liveness.core.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only access to configuration values by key.
 *
 * <p>The desktop client backs it with {@code ApplicationContext.map()} (synced {@code GLOBAL_PARAM} values plus
 * permitted local overrides); the Android client with {@code GlobalParamRepository}. Keys are listed in
 * {@link LivenessConfigKeys}. A missing key returns empty, never {@code null}, so the policy resolver can apply
 * the default.
 */
public interface ConfigSource {

    /**
     * Reads a value.
     *
     * @param key full configuration key, for example {@code mosip.registration.liveness.passive.threshold}
     * @return the raw value, or empty if the key is not set
     */
    Optional<String> get(String key);

    /**
     * Returns a source with no values.
     *
     * @return an empty source
     */
    static ConfigSource empty() {
        return key -> Optional.empty();
    }

    /**
     * Returns a source backed by a copy of a map (tests, tools and simple clients).
     *
     * @param values key-value pairs; copied; {@code null} values are treated as absent
     * @return the source
     */
    static ConfigSource of(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        final Map<String, String> copy = Collections.unmodifiableMap(new HashMap<>(values));
        return key -> Optional.ofNullable(copy.get(key));
    }
}
