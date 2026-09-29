package io.mosip.registration.controller.liveness;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Resolves the face liveness message keys ({@code liveness.*}, the catalogue shared with the Android client) and the
 * desktop screen labels ({@code liveness.ui.*}) to display text.
 *
 * <p>Texts come from the client's {@code labels_<lang>.properties} bundle. A key missing from that bundle falls back
 * to the English bundle, then to the key itself, so a missing translation never breaks a screen. Placeholders
 * {@code {0}}, {@code {1}} are filled with {@link MessageFormat}.
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

	private static final String MISSING_BUNDLE_BASE = "labels";

	private final ResourceBundle primary;
	private final ResourceBundle fallback;

	/**
	 * Creates a resolver.
	 *
	 * @param primary  the labels bundle of the display language; may be {@code null}
	 * @param fallback the English labels bundle; may be {@code null}
	 */
	public LivenessTexts(ResourceBundle primary, ResourceBundle fallback) {
		this.primary = primary;
		this.fallback = fallback;
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
		if (pattern == null) {
			return key;
		}
		if (args == null || args.length == 0) {
			return pattern;
		}
		return new MessageFormat(escapeQuotes(pattern)).format(args);
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

	/** Keeps apostrophes in translations (French "l'eclairage") literal for {@link MessageFormat}. */
	private static String escapeQuotes(String pattern) {
		return pattern.replace("'", "''");
	}
}
