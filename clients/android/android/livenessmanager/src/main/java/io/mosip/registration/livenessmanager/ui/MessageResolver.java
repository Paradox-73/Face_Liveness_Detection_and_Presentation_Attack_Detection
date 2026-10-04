/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.ui;

import android.content.res.Resources;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.engine.LivenessUiStateMapper;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.R;

/**
 * Turns the engine's message keys ({@link MessageKeys}) into text: the module's Android string resource in the
 * screen's language, replaced by the synced text {@code mosip.registration.liveness.guidance.<key>.<lang>} when
 * one is configured (the same rule as the desktop client), then {@code {0}}, {@code {1}} filled with the state's
 * arguments.
 *
 * <p>Messages that count something ({@link #PLURAL_KEYS}, for example "after 1 attempt" / "after 3 attempts") are
 * Android {@code <plurals>} resources: the quantity form is chosen from the first argument with the plural rules of
 * the screen's language (Arabic has six forms, French counts 0 and 1 as singular).
 */
public final class MessageResolver {

    /**
     * Android-only message: the resident's face was stored under a supervisor override (shown by ARC's Flutter
     * screens under the Scan button).
     */
    public static final String OVERRIDE_USED = "liveness.override.used";
    /** Message keys that exist only on Android, in addition to {@link MessageKeys#all()}. */
    public static final java.util.List<String> ANDROID_ONLY_KEYS =
            Collections.unmodifiableList(java.util.Arrays.asList(OVERRIDE_USED));

    /** Message keys whose text is a {@code <plurals>} resource; the first argument is the count. */
    public static final java.util.List<String> PLURAL_KEYS =
            Collections.unmodifiableList(java.util.Arrays.asList(MessageKeys.RETRY_MAX_RESIDENT));

    /**
     * Quantity used when a plural message has no numeric count: 100 selects the general ("other") form in all six
     * shipped languages.
     */
    static final int UNKNOWN_QUANTITY = 100;

    private static final Map<String, Integer> IDS = buildIds();
    private static final Map<String, Integer> PLURAL_IDS = buildPluralIds();

    private final Resources resources;
    private final ConfigSource config;
    private final String language;

    /**
     * Creates a resolver.
     *
     * @param resources resources already set to the screen's locale
     * @param config    liveness configuration (for guidance overrides)
     * @param language  ARC language code, for example {@code eng}
     */
    public MessageResolver(Resources resources, ConfigSource config, String language) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.config = Objects.requireNonNull(config, "config");
        this.language = Objects.requireNonNull(language, "language");
    }

    /**
     * Returns the string or plurals resource of a message key.
     *
     * @param key message key
     * @return the resource ID ({@code R.plurals} for {@link #isPlural plural} keys), or {@code null} for an
     *         unknown key
     */
    public static Integer resourceId(String key) {
        Integer id = IDS.get(key);
        return id != null ? id : PLURAL_IDS.get(key);
    }

    /**
     * Tells whether a message key is a {@code <plurals>} resource.
     *
     * @param key message key
     * @return {@code true} for the keys of {@link #PLURAL_KEYS}
     */
    public static boolean isPlural(String key) {
        return PLURAL_IDS.containsKey(key);
    }

    /**
     * Returns the keys that have a resource.
     *
     * @return all mapped keys: the {@link MessageKeys#all()} keys and {@link #ANDROID_ONLY_KEYS}, each with its
     *         string resource, or its plurals resource for {@link #PLURAL_KEYS}
     */
    public static Map<String, Integer> mappedKeys() {
        Map<String, Integer> all = new HashMap<>(IDS);
        all.putAll(PLURAL_IDS);
        return Collections.unmodifiableMap(all);
    }

    /**
     * Returns the Android locale of an ARC language code.
     *
     * @param arcLanguage three-letter ARC code ({@code eng, fra, ara, hin, kan, tam}) or a two-letter code
     * @return the locale
     */
    public static Locale localeOf(String arcLanguage) {
        String code = arcLanguage == null ? "" : arcLanguage.trim().toLowerCase(Locale.ROOT);
        switch (code) {
            case "fra":
            case "fre":
                return new Locale("fr");
            case "ara":
                return new Locale("ar");
            case "hin":
                return new Locale("hi");
            case "kan":
                return new Locale("kn");
            case "tam":
                return new Locale("ta");
            case "eng":
            case "":
                return Locale.ENGLISH;
            default:
                return new Locale(code.length() > 2 ? code.substring(0, 2) : code);
        }
    }

    /**
     * Returns the text of a message.
     *
     * @param key  message key
     * @param args values for {@code {0}}, {@code {1}}
     * @return the text; the key itself if it is unknown (never empty)
     */
    public String text(String key, List<String> args) {
        Integer id = IDS.get(key);
        Integer plural = PLURAL_IDS.get(key);
        String bundle;
        if (plural != null) {
            bundle = resources.getQuantityString(plural, quantity(args));
        } else {
            bundle = id == null ? key : resources.getString(id);
        }
        return fill(LivenessUiStateMapper.resolveText(config, key, language, bundle), args);
    }

    /**
     * Returns the count of a plural message: its first argument as a whole number.
     *
     * @param args the message arguments
     * @return the count, or {@link #UNKNOWN_QUANTITY} when the first argument is missing or not a number
     */
    static int quantity(List<String> args) {
        if (args == null || args.isEmpty() || args.get(0) == null) {
            return UNKNOWN_QUANTITY;
        }
        try {
            return Integer.parseInt(args.get(0).trim());
        } catch (NumberFormatException e) {
            return UNKNOWN_QUANTITY;
        }
    }

    /**
     * Returns a screen label with arguments.
     *
     * @param id   string resource
     * @param args values for {@code {0}}, {@code {1}}
     * @return the text
     */
    public String label(int id, String... args) {
        return fill(resources.getString(id), java.util.Arrays.asList(args));
    }

    static String fill(String text, List<String> args) {
        String out = text;
        for (int i = 0; args != null && i < args.size(); i++) {
            out = out.replace("{" + i + "}", String.valueOf(args.get(i)));
        }
        return out;
    }

    private static Map<String, Integer> buildIds() {
        Map<String, Integer> m = new HashMap<>();
        m.put(MessageKeys.STATUS_CHECKING, R.string.liveness_status_checking);
        m.put(MessageKeys.CHALLENGE_BLINK, R.string.liveness_challenge_blink);
        m.put(MessageKeys.CHALLENGE_SMILE, R.string.liveness_challenge_smile);
        m.put(MessageKeys.CHALLENGE_TURN_LEFT, R.string.liveness_challenge_turn_left);
        m.put(MessageKeys.CHALLENGE_TURN_RIGHT, R.string.liveness_challenge_turn_right);
        m.put(MessageKeys.CHALLENGE_LOOK_UP, R.string.liveness_challenge_look_up);
        m.put(MessageKeys.CHALLENGE_LOOK_DOWN, R.string.liveness_challenge_look_down);
        m.put(MessageKeys.CHALLENGE_GAZE_LEFT, R.string.liveness_challenge_gaze_left);
        m.put(MessageKeys.CHALLENGE_GAZE_RIGHT, R.string.liveness_challenge_gaze_right);
        m.put(MessageKeys.FEEDBACK_CONTINUE, R.string.liveness_feedback_continue);
        m.put(MessageKeys.FEEDBACK_DETECTED, R.string.liveness_feedback_detected);
        m.put(MessageKeys.FEEDBACK_HOLD_STILL, R.string.liveness_feedback_hold_still);
        m.put(MessageKeys.FEEDBACK_LOOK_STRAIGHT, R.string.liveness_feedback_look_straight);
        m.put(MessageKeys.FEEDBACK_SUCCESS, R.string.liveness_feedback_success);
        m.put(MessageKeys.FAIL_LIVENESS, R.string.liveness_fail_liveness);
        m.put(MessageKeys.FAIL_PAD, R.string.liveness_fail_pad);
        m.put(MessageKeys.GUIDE_LOOK_CAMERA, R.string.liveness_guide_look_camera);
        m.put(MessageKeys.GUIDE_FACE_VISIBLE, R.string.liveness_guide_face_visible);
        m.put(MessageKeys.GUIDE_LIGHTING, R.string.liveness_guide_lighting);
        m.put(MessageKeys.GUIDE_IN_AREA, R.string.liveness_guide_in_area);
        m.put(MessageKeys.GUIDE_FOLLOW_ACTION, R.string.liveness_guide_follow_action);
        m.put(MessageKeys.RETRY_AVAILABLE, R.string.liveness_retry_available);
        m.put(MessageKeys.RETRY_MAX_AUTH, R.string.liveness_retry_max_auth);
        m.put(MessageKeys.OVERRIDE_CONFIRM, R.string.liveness_override_confirm);
        m.put(MessageKeys.ERROR_NO_FACE, R.string.liveness_error_no_face);
        m.put(MessageKeys.ERROR_MULTIPLE_FACES, R.string.liveness_error_multiple_faces);
        m.put(MessageKeys.ERROR_POOR_QUALITY, R.string.liveness_error_poor_quality);
        m.put(MessageKeys.ERROR_TIMEOUT, R.string.liveness_error_timeout);
        m.put(MessageKeys.ERROR_DEVICE_UNAVAILABLE, R.string.liveness_error_device_unavailable);
        m.put(MessageKeys.ERROR_DEVICE_DISCONNECTED, R.string.liveness_error_device_disconnected);
        m.put(MessageKeys.ERROR_DEVICE_NO_STREAM, R.string.liveness_error_device_no_stream);
        m.put(MessageKeys.ERROR_CAMERA_UNAVAILABLE, R.string.liveness_error_camera_unavailable);
        m.put(MessageKeys.ERROR_CAMERA_PERMISSION, R.string.liveness_error_camera_permission);
        m.put(MessageKeys.ERROR_FRAMES_INVALID, R.string.liveness_error_frames_invalid);
        m.put(MessageKeys.ERROR_ENGINE, R.string.liveness_error_engine);
        m.put(MessageKeys.ERROR_NOT_SUPERVISOR, R.string.liveness_error_not_supervisor);
        m.put(MessageKeys.ERROR_EMULATOR, R.string.liveness_error_emulator);
        m.put(MessageKeys.TITLE_SUPERVISOR, R.string.liveness_title_supervisor);
        m.put(MessageKeys.OFFLINE_BADGE, R.string.liveness_offline_badge);
        m.put(MessageKeys.CONFIG_UPDATED, R.string.liveness_config_updated);
        m.put(OVERRIDE_USED, R.string.liveness_override_used);
        return m;
    }

    private static Map<String, Integer> buildPluralIds() {
        Map<String, Integer> m = new HashMap<>();
        m.put(MessageKeys.RETRY_MAX_RESIDENT, R.plurals.liveness_retry_max_resident);
        return m;
    }
}
