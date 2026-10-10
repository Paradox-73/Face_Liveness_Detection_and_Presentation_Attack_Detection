/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Liveness attempt counts saved in the app's private SharedPreferences, so that restarting the app does not give a
 * fresh set of attempts. Writes use {@code commit()}: the count is on disk before the next screen can open. An entry
 * is kept {@value #KEEP_MS} ms after its last change (the desktop's resident block time), then dropped.
 */
public final class SavedAttempts {

    /** How long an entry is kept after its last change: 12 hours. */
    static final long KEEP_MS = 12L * 60 * 60 * 1000;

    private final SharedPreferences prefs;

    /**
     * Opens one saved set.
     *
     * @param context any context of the app
     * @param name    preferences file name
     */
    public SavedAttempts(Context context, String name) {
        this(context.getSharedPreferences(Objects.requireNonNull(name, "name"), Context.MODE_PRIVATE));
    }

    SavedAttempts(SharedPreferences prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs");
    }

    /**
     * Reads the entries that have not expired and removes the others.
     *
     * @param now current time, epoch ms
     * @return key to saved value
     */
    synchronized Map<String, String> load(long now) {
        Map<String, String> live = new HashMap<>();
        SharedPreferences.Editor expired = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String text = e.getValue() instanceof String ? (String) e.getValue() : "";
            int cut = text.indexOf(';');
            long until;
            try {
                until = cut < 0 ? 0 : Long.parseLong(text.substring(0, cut));
            } catch (NumberFormatException ex) {
                until = 0;
            }
            if (until > now) {
                live.put(e.getKey(), text.substring(cut + 1));
            } else {
                expired.remove(e.getKey());
            }
        }
        expired.commit();
        return live;
    }

    /**
     * Saves or replaces an entry.
     *
     * @param key   entry key
     * @param value entry value
     * @param now   current time, epoch ms
     */
    synchronized void put(String key, String value, long now) {
        prefs.edit().putString(key, (now + KEEP_MS) + ";" + value).commit();
    }

    /**
     * Removes an entry.
     *
     * @param key entry key
     */
    synchronized void remove(String key) {
        prefs.edit().remove(key).commit();
    }
}
