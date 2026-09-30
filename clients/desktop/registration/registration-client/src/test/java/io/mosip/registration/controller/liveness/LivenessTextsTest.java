package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ListResourceBundle;
import java.util.Locale;

import org.junit.Test;

import io.mosip.liveness.core.config.ConfigSource;

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
}
