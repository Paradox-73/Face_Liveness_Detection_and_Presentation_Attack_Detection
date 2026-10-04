/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.config.ConfigSource;

/**
 * A {@link ConfigSource} whose values replace those of a base source. Debug builds use it for values passed to
 * the capture screen from adb (demos and tests); release builds never create one.
 */
public final class OverlayConfigSource implements ConfigSource {

    private final ConfigSource base;
    private final Map<String, String> overlay;

    /**
     * Creates the overlay.
     *
     * @param base    source for keys the overlay does not set
     * @param overlay replacement values; copied
     */
    public OverlayConfigSource(ConfigSource base, Map<String, String> overlay) {
        this.base = Objects.requireNonNull(base, "base");
        this.overlay = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(overlay, "overlay")));
    }

    @Override
    public Optional<String> get(String key) {
        String value = overlay.get(key);
        if (value != null && !value.trim().isEmpty()) {
            return Optional.of(value.trim());
        }
        return base.get(key);
    }
}
