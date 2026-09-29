package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import io.mosip.liveness.core.ui.MessageKeys;

/**
 * Key parity of the face liveness texts: every shared message key and every desktop screen label exists in the
 * English, French and Arabic bundles, with the same placeholders, and matches the catalogue in
 * {@code docs/ux/message-catalogue.csv}.
 */
public class LivenessBundleParityTest {

	private static final List<String> LANGUAGES = Arrays.asList("en", "fr", "ar");
	private static final List<String> UI_KEYS = Arrays.asList(LivenessTexts.UI_TITLE, LivenessTexts.UI_RETRY,
			LivenessTexts.UI_CANCEL, LivenessTexts.UI_CLOSE, LivenessTexts.UI_OVERRIDE, LivenessTexts.UI_STEP,
			LivenessTexts.UI_ATTEMPTS, LivenessTexts.UI_PREVIEW, LivenessTexts.UI_BUSY);
	private static final List<String> MESSAGE_KEYS = Arrays.asList("LIVENESS_CAPTURE_REFUSED",
			"LIVENESS_MAX_ATTEMPTS_AUTH");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\}");
	private static final Path CATALOGUE = Paths.get("..", "..", "..", "..", "docs", "ux", "message-catalogue.csv");

	@Test
	public void everySharedKeyAndScreenLabelIsInEveryLabelBundle() throws IOException {
		for (String lang : LANGUAGES) {
			Properties labels = load("labels_" + lang + ".properties");
			for (String key : expectedLabelKeys()) {
				String text = labels.getProperty(key);
				assertNotNull("labels_" + lang + " misses " + key, text);
				assertFalse("labels_" + lang + " has an empty " + key, text.trim().isEmpty());
			}
		}
	}

	@Test
	public void livenessKeysAreTheSameInAllLabelBundles() throws IOException {
		Set<String> english = livenessKeys(load("labels_en.properties"));
		for (String lang : LANGUAGES) {
			assertEquals("liveness keys of labels_" + lang, english, livenessKeys(load("labels_" + lang + ".properties")));
		}
	}

	@Test
	public void placeholdersMatchEnglish() throws IOException {
		Properties english = load("labels_en.properties");
		for (String lang : LANGUAGES) {
			Properties labels = load("labels_" + lang + ".properties");
			for (String key : livenessKeys(english)) {
				assertEquals("placeholders of " + key + " in " + lang, placeholders(english.getProperty(key)),
						placeholders(labels.getProperty(key)));
			}
		}
	}

	@Test
	public void alertMessagesExistInEveryMessageBundleWithAType() throws IOException {
		for (String lang : LANGUAGES) {
			Properties messages = load("messages_" + lang + ".properties");
			for (String key : MESSAGE_KEYS) {
				String text = messages.getProperty(key);
				assertNotNull("messages_" + lang + " misses " + key, text);
				assertTrue(key + " in " + lang + " has no #TYPE# suffix", text.contains("#TYPE#"));
			}
		}
	}

	@Test
	public void translationsDifferFromEnglish() throws IOException {
		Properties english = load("labels_en.properties");
		for (String lang : Arrays.asList("fr", "ar")) {
			Properties labels = load("labels_" + lang + ".properties");
			for (String key : Arrays.asList(MessageKeys.STATUS_CHECKING, MessageKeys.CHALLENGE_BLINK,
					MessageKeys.FAIL_LIVENESS, MessageKeys.FAIL_PAD, LivenessTexts.UI_RETRY)) {
				assertFalse(key + " is not translated in " + lang,
						english.getProperty(key).equals(labels.getProperty(key)));
			}
		}
	}

	@Test
	public void bundlesMatchTheCatalogue() throws IOException {
		if (!Files.exists(CATALOGUE)) {
			return; // module built outside the repository
		}
		List<String> lines = Files.readAllLines(CATALOGUE, StandardCharsets.UTF_8);
		assertEquals("key,bundle,source,en,fr,ar", lines.get(0));
		for (String line : lines.subList(1, lines.size())) {
			List<String> cells = parseCsvLine(line);
			String key = cells.get(0);
			String bundle = cells.get(1);
			for (int i = 0; i < LANGUAGES.size(); i++) {
				Properties props = load(bundle + "_" + LANGUAGES.get(i) + ".properties");
				String text = props.getProperty(key);
				assertNotNull(bundle + "_" + LANGUAGES.get(i) + " misses catalogue key " + key, text);
				String expected = cells.get(3 + i);
				assertEquals(key + " (" + LANGUAGES.get(i) + ")", expected,
						"messages".equals(bundle) ? text.substring(0, text.indexOf("#TYPE#")) : text);
			}
		}
	}

	private static List<String> expectedLabelKeys() {
		List<String> keys = new ArrayList<>(MessageKeys.all());
		keys.addAll(UI_KEYS);
		return keys;
	}

	private static Set<String> livenessKeys(Properties props) {
		Set<String> keys = new TreeSet<>();
		for (String key : props.stringPropertyNames()) {
			if (key.startsWith("liveness.")) {
				keys.add(key);
			}
		}
		return keys;
	}

	private static Set<String> placeholders(String text) {
		Set<String> found = new TreeSet<>();
		Matcher m = PLACEHOLDER.matcher(text);
		while (m.find()) {
			found.add(m.group());
		}
		return found;
	}

	private static Properties load(String name) throws IOException {
		Properties props = new Properties();
		try (InputStream in = LivenessBundleParityTest.class.getClassLoader().getResourceAsStream(name)) {
			assertNotNull("missing bundle " + name, in);
			props.load(in);
		}
		return props;
	}

	private static List<String> parseCsvLine(String line) {
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					cell.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					cell.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				cells.add(cell.toString());
				cell.setLength(0);
			} else {
				cell.append(c);
			}
		}
		cells.add(cell.toString());
		return cells;
	}
}
