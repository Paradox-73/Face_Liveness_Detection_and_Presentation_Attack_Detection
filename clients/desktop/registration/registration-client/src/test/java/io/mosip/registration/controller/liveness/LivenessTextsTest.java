package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ListResourceBundle;
import java.util.Locale;

import org.junit.Test;

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
	public void clientLanguageCodesMapToBundleLocales() {
		assertEquals(Locale.ENGLISH, LivenessTexts.localeOf("eng"));
		assertEquals(Locale.FRENCH, LivenessTexts.localeOf("fra"));
		assertEquals("ar", LivenessTexts.localeOf("ara").getLanguage());
		assertEquals(Locale.ENGLISH, LivenessTexts.localeOf(null));
		assertEquals(Locale.ENGLISH, LivenessTexts.localeOf("xyz"));
	}
}
