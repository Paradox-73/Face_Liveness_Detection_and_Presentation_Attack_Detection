/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.engine.LivenessUiStateMapper;

/**
 * Resolves the face liveness message keys ({@code liveness.*}, the catalogue shared with the Android client) and the
 * desktop screen labels ({@code liveness.ui.*}) to display text.
 *
 * <p>Texts come from the client's {@code labels_<lang>.properties} bundle. A key missing from that bundle falls back
 * to the English bundle, then to the key itself, so a missing translation never breaks a screen. A resolver made
 * with {@link #withOverrides(ConfigSource, String)} first looks for a configured replacement text
 * {@code mosip.registration.liveness.guidance.<messageKey>.<lang>} (synced like every other key, so the same text
 * applies offline). Placeholders {@code {0}}, {@code {1}} are filled with {@link MessageFormat}.
 *
 * <p>Counts that change the noun use a {@link java.text.ChoiceFormat} element, for example
 * {@code after {0,choice,1#{0} attempt|1<{0} attempts}}, so every count reads correctly in each language (Arabic has
 * separate forms for 1, 2, 3 to 10 and 11 and more). The engine sends its arguments as text; a whole-number text
 * argument of such a pattern is passed to the choice as a number. A choice branch must not contain an apostrophe: a
 * branch with a placeholder is formatted a second time, which drops it.
 */
public final class LivenessTexts {

	/** Label key of the panel title for resident and operator captures. */
	public static final String UI_TITLE = "liveness.ui.title";
	/** Label key of the Retry button. */
	public static final String UI_RETRY = "liveness.ui.retry";
	/** Label key of the Cancel button. */
	public static final String UI_CANCEL = "liveness.ui.cancel";
	/** Label key of the Close button shown when no further attempt is allowed. */
	public static final String UI_CLOSE = "liveness.ui.close";
	/** Label key of the supervisor override button. */
	public static final String UI_OVERRIDE = "liveness.ui.override";
	/** Label key of the challenge step counter, "Step {0} of {1}". */
	public static final String UI_STEP = "liveness.ui.step";
	/** Label key of the attempt counter, "Attempt {0} of {1}". */
	public static final String UI_ATTEMPTS = "liveness.ui.attempts";
	/** Label key of the accessible description of the preview. */
	public static final String UI_PREVIEW = "liveness.ui.preview";
	/** Label key of the busy message when another face verification is running. */
	public static final String UI_BUSY = "liveness.ui.busy";
	/** Label key of the supervisor override prompt: a second supervisor must verify. */
	public static final String OVERRIDE_PROMPT = "liveness.override.prompt";
	/** Label key of the user ID field of the supervisor override prompt. */
	public static final String OVERRIDE_SUPERVISOR_ID = "liveness.override.supervisor_id";
	/** Label key shown when the logged-in operator tries to approve the override. */
	public static final String OVERRIDE_SAME_USER = "liveness.override.same_user";
	/** Label key shown when the supervisor's face authentication failed. */
	public static final String OVERRIDE_AUTH_FAILED = "liveness.override.auth_failed";
	/** Label key shown when the supervisor authenticated too long before the override. */
	public static final String OVERRIDE_AUTH_EXPIRED = "liveness.override.auth_expired";
	/** Label key shown when the override cannot be granted at all. */
	public static final String OVERRIDE_NOT_ALLOWED = "liveness.override.not_allowed";

	private static final Pattern CHOICE = Pattern.compile("\\{\\s*(\\d{1,9})\\s*,\\s*choice\\s*,");
	private static final Pattern WHOLE_NUMBER = Pattern.compile("-?\\d{1,18}");
	private static final String MISSING_BUNDLE_BASE = "labels";
	private static final String DEFAULT_LANGUAGE = "eng";

	private final ResourceBundle primary;
	private final ResourceBundle fallback;
	private final ConfigSource overrides;
	private final String language;

	/**
	 * Creates a resolver.
	 *
	 * @param primary  the labels bundle of the display language; may be {@code null}
	 * @param fallback the English labels bundle; may be {@code null}
	 */
	public LivenessTexts(ResourceBundle primary, ResourceBundle fallback) {
		this(primary, fallback, null, DEFAULT_LANGUAGE);
	}

	private LivenessTexts(ResourceBundle primary, ResourceBundle fallback, ConfigSource overrides, String language) {
		this.primary = primary;
		this.fallback = fallback;
		this.overrides = overrides;
		this.language = language;
	}

	/**
	 * Returns a resolver that prefers the configured replacement texts
	 * {@code mosip.registration.liveness.guidance.<messageKey>.<lang>} (G4) over the bundle texts, with the same rule
	 * as the Android client ({@link LivenessUiStateMapper#resolveText}). A blank replacement is ignored.
	 *
	 * @param config   configuration with the synced replacement texts; read at each lookup
	 * @param langCode client language code of the display ({@code eng}, {@code fra}, {@code ara}), used as
	 *                 {@code <lang>}; {@code null} means {@code eng}
	 * @return a new resolver over the same bundles
	 */
	public LivenessTexts withOverrides(ConfigSource config, String langCode) {
		String lang = langCode == null || langCode.trim().isEmpty() ? DEFAULT_LANGUAGE
				: langCode.trim().toLowerCase(Locale.ROOT);
		return new LivenessTexts(primary, fallback, config, lang);
	}

	/**
	 * Creates a resolver for a language from the bundles on the class path.
	 *
	 * @param langCode ISO 639-2 language code used by the client ({@code eng}, {@code fra}, {@code ara})
	 * @return the resolver
	 */
	public static LivenessTexts forLanguage(String langCode) {
		return new LivenessTexts(load(localeOf(langCode)), load(Locale.ENGLISH));
	}

	/**
	 * Returns the text of a key with its placeholders filled.
	 *
	 * @param key  message key
	 * @param args placeholder values, in order
	 * @return the text; the key itself when no bundle has it
	 */
	public String text(String key, Object... args) {
		Objects.requireNonNull(key, "key");
		String pattern = lookup(primary, key);
		if (pattern == null) {
			pattern = lookup(fallback, key);
		}
		if (overrides != null) {
			pattern = LivenessUiStateMapper.resolveText(overrides, key, language, pattern);
		}
		if (pattern == null) {
			return key;
		}
		if (args == null || args.length == 0) {
			return pattern;
		}
		try {
			return new MessageFormat(escapeQuotes(pattern)).format(numbersForChoices(pattern, args));
		} catch (IllegalArgumentException e) {
			// a broken translation or a text argument where a count is expected: show the text unformatted
			return pattern;
		}
	}

	/**
	 * Returns the text of a key with string arguments.
	 *
	 * @param key  message key
	 * @param args placeholder values, in order
	 * @return the text
	 */
	public String text(String key, List<String> args) {
		return text(key, args == null ? new Object[0] : args.toArray());
	}

	/**
	 * Returns the texts of several keys.
	 *
	 * @param keys message keys
	 * @return the texts in the same order
	 */
	public List<String> texts(List<String> keys) {
		List<String> result = new ArrayList<>();
		for (String key : keys) {
			result.add(text(key));
		}
		return result;
	}

	/**
	 * Tells whether the display-language bundle has its own text for a key (used by the key parity test).
	 *
	 * @param key message key
	 * @return {@code true} if the primary bundle resolves the key
	 */
	public boolean hasOwnText(String key) {
		return lookup(primary, key) != null;
	}

	/**
	 * Maps a client language code to a locale.
	 *
	 * @param langCode {@code eng}, {@code fra}, {@code ara} or a two-letter code
	 * @return the locale; English when the code is unknown
	 */
	static Locale localeOf(String langCode) {
		if (langCode == null || langCode.trim().isEmpty()) {
			return Locale.ENGLISH;
		}
		String code = langCode.trim().toLowerCase(Locale.ROOT);
		switch (code) {
		case "eng":
			return Locale.ENGLISH;
		case "fra":
		case "fre":
			return Locale.FRENCH;
		case "ara":
			return new Locale("ar");
		default:
			return code.length() == 2 ? new Locale(code) : Locale.ENGLISH;
		}
	}

	private static ResourceBundle load(Locale locale) {
		try {
			return ResourceBundle.getBundle(MISSING_BUNDLE_BASE, locale);
		} catch (MissingResourceException e) {
			return null;
		}
	}

	private static String lookup(ResourceBundle bundle, String key) {
		if (bundle == null) {
			return null;
		}
		try {
			return bundle.getString(key);
		} catch (MissingResourceException e) {
			return null;
		}
	}

	/**
	 * Turns the whole-number text arguments of the pattern's choice elements into numbers, which a choice needs.
	 * Other arguments keep their text, so version numbers and IDs are not regrouped.
	 */
	static Object[] numbersForChoices(String pattern, Object[] args) {
		Matcher choice = CHOICE.matcher(pattern);
		Object[] converted = args;
		while (choice.find()) {
			int index = Integer.parseInt(choice.group(1));
			if (index < args.length && args[index] instanceof String
					&& WHOLE_NUMBER.matcher(((String) args[index]).trim()).matches()) {
				if (converted == args) {
					converted = args.clone();
				}
				converted[index] = Long.valueOf(((String) args[index]).trim());
			}
		}
		return converted;
	}

	/** Keeps apostrophes in translations (French "l'eclairage") literal for {@link MessageFormat}. */
	private static String escapeQuotes(String pattern) {
		return pattern.replace("'", "''");
	}
}
