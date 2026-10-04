/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;

/**
 * Liveness configuration of the Android client: a {@link ConfigSource} over ARC's
 * {@link GlobalParamRepository}, with the same {@code mosip.registration.liveness.*} keys as the desktop client.
 *
 * <p>Values arrive through the normal config sync and stay in the local database, so the last synced values
 * apply offline. Keys that a local setting may change ({@link LivenessConfigKeys#isLocalOverrideAllowed}) are
 * read from the repository's merged cache (synced values, then the operator's local settings); every other key
 * (the master switch, {@code policy.*}, the engine, the minimum model version and {@code on_max_failure}) is
 * read from the synced value only, so a local setting can never switch liveness off.
 *
 * <p>Missing, blank or malformed values never cause an exception: the core policy resolver applies the shipped
 * defaults, floors and ranges. Thread-safe.
 */
public final class GlobalParamConfigSource implements ConfigSource {

    /** Reads raw values; lets tests run without a database. */
    public interface Lookup {

        /**
         * Returns the merged value (synced value, then local override).
         *
         * @param key full key
         * @return the value or {@code null}
         */
        String merged(String key);

        /**
         * Returns the synced value only.
         *
         * @param key full key
         * @return the value or {@code null}
         */
        String synced(String key);
    }

    private final Lookup lookup;
    private Map<String, String> lastSnapshot;

    /**
     * Creates the source over ARC's repository.
     *
     * @param repository the global parameter repository
     */
    public GlobalParamConfigSource(GlobalParamRepository repository) {
        this(new Lookup() {
            @Override
            public String merged(String key) {
                return repository.getCachedStringGlobalParam(key);
            }

            @Override
            public String synced(String key) {
                return repository.getGlobalParamValue(key);
            }
        });
        Objects.requireNonNull(repository, "repository");
    }

    /**
     * Creates the source over any lookup (tests and tools).
     *
     * @param lookup value lookup
     */
    public GlobalParamConfigSource(Lookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public Optional<String> get(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = LivenessConfigKeys.isLocalOverrideAllowed(key) ? lookup.merged(key) : lookup.synced(key);
        } catch (RuntimeException e) {
            raw = null;
        }
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /**
     * Returns the liveness keys whose value changed since the previous call (added, removed or modified), for
     * audit {@code REG-EVT-LV15}. The first call records the baseline and reports nothing. Only the keys the
     * core knows ({@link LivenessConfigKeys#defaultValues()}) are compared.
     *
     * @return changed key names in sorted order; empty if nothing changed
     */
    public synchronized List<String> changedKeysSinceLastCheck() {
        Map<String, String> current = new TreeMap<>();
        for (String key : LivenessConfigKeys.defaultValues().keySet()) {
            get(key).ifPresent(v -> current.put(key, v));
        }
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
}
