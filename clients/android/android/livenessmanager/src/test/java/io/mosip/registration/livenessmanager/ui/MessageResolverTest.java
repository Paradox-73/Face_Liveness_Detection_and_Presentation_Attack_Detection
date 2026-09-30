package io.mosip.registration.livenessmanager.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeSet;

import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.ui.MessageKeys;

@RunWith(RobolectricTestRunner.class)
public class MessageResolverTest {

    private static final String[] LANGUAGES = {"eng", "fra", "ara", "hin", "kan", "tam"};
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    @Test
    public void everyEngineMessageKeyHasAString() {
        for (String key : MessageKeys.all()) {
            assertNotNull("no string resource for " + key, MessageResolver.resourceId(key));
        }
        for (String key : MessageResolver.ANDROID_ONLY_KEYS) {
            assertNotNull("no string resource for " + key, MessageResolver.resourceId(key));
        }
        assertEquals(MessageKeys.all().size() + MessageResolver.ANDROID_ONLY_KEYS.size(),
                MessageResolver.mappedKeys().size());
    }

    @Test
    public void everyLanguageHasEveryMessageWithTheSamePlaceholders() {
        Resources english = resources("eng");
        for (String lang : LANGUAGES) {
            Resources res = resources(lang);
            for (Map.Entry<String, Integer> e : MessageResolver.mappedKeys().entrySet()) {
                String text = res.getString(e.getValue());
                assertFalse(lang + " " + e.getKey() + " is empty", text.trim().isEmpty());
                assertEquals(lang + " " + e.getKey() + " placeholders", placeholders(english.getString(e.getValue())),
                        placeholders(text));
                if (!"eng".equals(lang) && !e.getKey().equals(MessageKeys.OFFLINE_BADGE)) {
                    assertFalse(lang + " " + e.getKey() + " is not translated",
                            text.equals(english.getString(e.getValue())));
                }
            }
        }
    }

    @Test
    public void fillsArgumentsAndUsesSyncedGuidanceOverride() {
        Resources res = resources("eng");
        MessageResolver plain = new MessageResolver(res, ConfigSource.empty(), "eng");
        assertEquals("Attempt 2 of 3. Select Retry to try again.",
                plain.text(MessageKeys.RETRY_AVAILABLE, Arrays.asList("2", "3")));

        ConfigSource config = ConfigSource.of(Collections.singletonMap(
                LivenessConfigKeys.guidanceKey(MessageKeys.GUIDE_LIGHTING, "eng"), "Move closer to the window."));
        MessageResolver synced = new MessageResolver(res, config, "eng");
        assertEquals("Move closer to the window.", synced.text(MessageKeys.GUIDE_LIGHTING, null));
        assertEquals("Please blink", synced.text(MessageKeys.CHALLENGE_BLINK, null));
    }

    @Test
    public void unknownKeyFallsBackToTheKey() {
        MessageResolver r = new MessageResolver(resources("eng"), ConfigSource.empty(), "eng");
        assertEquals("liveness.unknown", r.text("liveness.unknown", null));
    }

    @Test
    public void mapsArcLanguageCodes() {
        assertEquals("fr", MessageResolver.localeOf("fra").getLanguage());
        assertEquals("ar", MessageResolver.localeOf("ara").getLanguage());
        assertEquals("hi", MessageResolver.localeOf("hin").getLanguage());
        assertEquals("kn", MessageResolver.localeOf("kan").getLanguage());
        assertEquals("ta", MessageResolver.localeOf("tam").getLanguage());
        assertEquals("en", MessageResolver.localeOf("eng").getLanguage());
        assertEquals("en", MessageResolver.localeOf(null).getLanguage());
    }

    @Test
    public void arabicLayoutIsRightToLeft() {
        Configuration c = resources("ara").getConfiguration();
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, c.getLayoutDirection());
    }

    private static Resources resources(String arcLanguage) {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration c = new Configuration(base.getResources().getConfiguration());
        Locale locale = MessageResolver.localeOf(arcLanguage);
        c.setLocale(locale);
        c.setLayoutDirection(locale);
        return base.createConfigurationContext(c).getResources();
    }

    private static TreeSet<String> placeholders(String text) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        assertTrue(out.size() <= 2);
        return out;
    }
}
