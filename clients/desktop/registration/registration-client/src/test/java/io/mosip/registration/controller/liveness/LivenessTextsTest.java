package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.text.NumberFormat;
import java.util.ListResourceBundle;
import java.util.Locale;

import org.junit.Test;

import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.ui.MessageKeys;

/** Text lookup, fallbacks and placeholders. */
public class LivenessTextsTest {

	private static final class Bundle extends ListResourceBundle {
		private final Object[][] contents;

		Bundle(Object[][] contents) {
			this.contents = contents;
		}

		@Override
		protected Object[][] getContents() {
			return contents;
		}
	}

	@Test
	public void primaryThenFallbackThenKey() {
		LivenessTexts texts = new LivenessTexts(new Bundle(new Object[][] { { "a", "primary a" } }),
				new Bundle(new Object[][] { { "a", "fallback a" }, { "b", "fallback b" } }));

		assertEquals("primary a", texts.text("a"));
		assertEquals("fallback b", texts.text("b"));
		assertEquals("missing.key", texts.text("missing.key"));
		assertTrue(texts.hasOwnText("a"));
		assertFalse(texts.hasOwnText("b"));
	}

	@Test
	public void placeholdersAreFilledAndApostrophesKept() {
		LivenessTexts texts = new LivenessTexts(new Bundle(new Object[][] {
				{ "k", "Tentative {0} sur {1} : l'essai" } }), null);

		assertEquals("Tentative 2 sur 3 : l'essai", texts.text("k", 2, 3));
		assertEquals("Tentative {0} sur {1} : l'essai", texts.text("k"));
	}

	@Test
	public void nullBundlesNeverThrow() {
		LivenessTexts texts = new LivenessTexts(null, null);

		assertEquals("liveness.ui.retry", texts.text(LivenessTexts.UI_RETRY));
	}

	@Test
	public void configuredGuidanceReplacesTheBundleTextForItsLanguageOnly() {
		java.util.Map<String, String> synced = new java.util.HashMap<>();
		synced.put("mosip.registration.liveness.guidance.liveness.guide.lighting.eng", "Face the window, please");
		synced.put("mosip.registration.liveness.guidance.liveness.ui.step.eng", "Action {0}/{1}");
		synced.put("mosip.registration.liveness.guidance.liveness.guide.lighting.fra", " ");
		ConfigSource config = key -> java.util.Optional.ofNullable(synced.get(key));
		Bundle bundle = new Bundle(new Object[][] { { "liveness.guide.lighting", "Move to a brighter place" },
				{ "liveness.ui.step", "Step {0} of {1}" } });
		LivenessTexts plain = new LivenessTexts(bundle, null);

		assertEquals("Face the window, please", plain.withOverrides(config, "eng").text("liveness.guide.lighting"));
		assertEquals("placeholders work in a replacement", "Action 1/2",
				plain.withOverrides(config, " ENG ").text("liveness.ui.step", 1, 2));
		assertEquals("a blank replacement is ignored", "Move to a brighter place",
				plain.withOverrides(config, "fra").text("liveness.guide.lighting"));
		assertEquals("no replacement for Arabic", "Move to a brighter place",
				plain.withOverrides(config, "ara").text("liveness.guide.lighting"));
		assertEquals("null language means eng", "Face the window, please",
				plain.withOverrides(config, null).text("liveness.guide.lighting"));
		assertEquals("the original resolver is unchanged", "Move to a brighter place",
				plain.text("liveness.guide.lighting"));
		assertEquals("a replacement also works for a key no bundle has", "x",
				plain.withOverrides(key -> java.util.Optional.of("x"), "eng").text("missing.key"));
	}

	@Test
	public void clientLanguageCodesMapToBundleLocales() {
		assertEquals(Locale.ENGLISH, LivenessTexts.localeOf("eng"));
		assertEquals(Locale.FRENCH, LivenessTexts.localeOf("fra"));
		assertEquals("ar", LivenessTexts.localeOf("ara").getLanguage());
		assertEquals(Locale.ENGLISH, LivenessTexts.localeOf(null));
		assertEquals(Locale.ENGLISH, LivenessTexts.localeOf("xyz"));
	}

	private static final String ARABIC_ONE = "\u0645\u062d\u0627\u0648\u0644\u0629 \u0648\u0627\u062d\u062f\u0629";
	private static final String ARABIC_TWO = "\u0645\u062d\u0627\u0648\u0644\u062a\u064a\u0646";
	private static final String ARABIC_FEW = "\u0645\u062d\u0627\u0648\u0644\u0627\u062a";
	private static final String ARABIC_MANY = "\u0645\u062d\u0627\u0648\u0644\u0629";
	private static final String ARABIC_AFTER = "\u0628\u0639\u062f ";

	/** How {@link java.text.MessageFormat} writes a count in this JVM's locale. */
	private static String count(int n) {
		return NumberFormat.getInstance().format(n);
	}

	@Test
	public void pluralEnglishAttemptCountReadsCorrectlyForEveryCount() {
		LivenessTexts english = LivenessTexts.forLanguage("eng");

		assertTrue(english.text(MessageKeys.RETRY_MAX_RESIDENT, "1").contains("after " + count(1) + " attempt. "));
		for (int n = 2; n <= 12; n++) {
			String engineArg = english.text(MessageKeys.RETRY_MAX_RESIDENT, Integer.toString(n));
			assertTrue(engineArg, engineArg.contains("after " + count(n) + " attempts. "));
			assertEquals("a number and its text form read the same", engineArg,
					english.text(MessageKeys.RETRY_MAX_RESIDENT, n));
		}
		assertTrue(english.text(MessageKeys.RETRY_MAX_RESIDENT, 1).endsWith("the centre's recovery process."));
	}

	@Test
	public void pluralFrenchUsesTheSingularForOneAndKeepsApostrophes() {
		LivenessTexts french = LivenessTexts.forLanguage("fra");

		String one = french.text(MessageKeys.RETRY_MAX_RESIDENT, "1");
		assertTrue(one, one.contains("apr\u00e8s " + count(1) + " tentative. "));
		assertTrue(one, one.startsWith("La capture du visage n'a pas abouti"));
		assertTrue(one, one.contains("d'appliquer"));
		for (int n = 2; n <= 12; n++) {
			String text = french.text(MessageKeys.RETRY_MAX_RESIDENT, Integer.toString(n));
			assertTrue(text, text.contains("apr\u00e8s " + count(n) + " tentatives. "));
		}
	}

	@Test
	public void pluralArabicUsesTheFormsForOneTwoThreeToTenAndElevenOrMore() {
		LivenessTexts arabic = LivenessTexts.forLanguage("ara");

		assertTrue(arabic.text(MessageKeys.RETRY_MAX_RESIDENT, "1").contains(ARABIC_AFTER + ARABIC_ONE + ". "));
		assertTrue(arabic.text(MessageKeys.RETRY_MAX_RESIDENT, "2").contains(ARABIC_AFTER + ARABIC_TWO + ". "));
		for (int n = 3; n <= 10; n++) {
			String text = arabic.text(MessageKeys.RETRY_MAX_RESIDENT, Integer.toString(n));
			assertTrue(text, text.contains(ARABIC_AFTER + count(n) + " " + ARABIC_FEW + ". "));
		}
		for (int n : new int[] { 11, 12, 25, 99 }) {
			String text = arabic.text(MessageKeys.RETRY_MAX_RESIDENT, Integer.toString(n));
			assertTrue(text, text.contains(ARABIC_AFTER + count(n) + " " + ARABIC_MANY + ". "));
		}
	}

	@Test
	public void pluralNoLivenessTextShowsARawPatternForAnyCount() {
		for (String lang : new String[] { "eng", "fra", "ara" }) {
			LivenessTexts texts = LivenessTexts.forLanguage(lang);
			for (int n = 1; n <= 12; n++) {
				for (String key : new String[] { MessageKeys.RETRY_MAX_RESIDENT, MessageKeys.RETRY_AVAILABLE,
						LivenessTexts.UI_ATTEMPTS, LivenessTexts.UI_STEP }) {
					String text = texts.text(key, Integer.toString(n), "3");
					assertFalse(lang + " " + key + " " + n + ": " + text, text.contains("{") || text.contains("choice"));
				}
			}
			assertFalse(lang, texts.text(MessageKeys.RETRY_MAX_RESIDENT, "1").contains("1 attempts"));
		}
	}

	@Test
	public void pluralConversionLeavesPatternsWithoutChoiceAlone() {
		LivenessTexts texts = new LivenessTexts(new Bundle(new Object[][] { { "v", "Models {0} version {1}" },
				{ "c", "{0,choice,1#one file|1<{0} files} in {1}" } }), null);

		assertEquals("a version number is not regrouped", "Models m version 20261004",
				texts.text("v", "m", "20261004"));
		assertEquals("one file in 20261004", texts.text("c", " 1 ", "20261004"));
		assertEquals(count(4) + " files in x", texts.text("c", "4", "x"));
	}

	@Test
	public void pluralTextWhereACountIsExpectedShowsThePatternInsteadOfFailing() {
		LivenessTexts texts = new LivenessTexts(new Bundle(new Object[][] {
				{ "c", "after {0,choice,1#{0} attempt|1<{0} attempts}" } }), null);

		assertEquals("after {0,choice,1#{0} attempt|1<{0} attempts}", texts.text("c", "several"));
	}
}
