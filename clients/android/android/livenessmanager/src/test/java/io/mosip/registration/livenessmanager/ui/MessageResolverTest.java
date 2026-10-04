/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
                String text = bundleText(res, e.getKey(), e.getValue());
                String englishText = bundleText(english, e.getKey(), e.getValue());
                assertFalse(lang + " " + e.getKey() + " is empty", text.trim().isEmpty());
                assertEquals(lang + " " + e.getKey() + " placeholders", placeholders(englishText),
                        placeholders(text));
                if (!"eng".equals(lang) && !e.getKey().equals(MessageKeys.OFFLINE_BADGE)) {
                    assertFalse(lang + " " + e.getKey() + " is not translated", text.equals(englishText));
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
    public void pluralAttemptTextUsesTheSingularForOneAttempt() {
        MessageResolver r = new MessageResolver(resources("eng"), ConfigSource.empty(), "eng");
        String one = r.text(MessageKeys.RETRY_MAX_RESIDENT, Collections.singletonList("1"));
        assertTrue(one, one.startsWith("Face capture could not be completed after 1 attempt. "));
        assertFalse(one, one.contains("1 attempts"));
        String three = r.text(MessageKeys.RETRY_MAX_RESIDENT, Collections.singletonList("3"));
        assertTrue(three, three.startsWith("Face capture could not be completed after 3 attempts. "));
    }

    @Test
    public void pluralFormsFollowEachLanguagesRules() {
        assertEquals("tentative.", word(text("fra", "1"), "1 "));
        assertEquals("tentatives.", word(text("fra", "3"), "3 "));
        String arabicOne = text("ara", "1");
        assertTrue(arabicOne, arabicOne.contains("محاولة واحدة"));
        assertFalse(arabicOne, arabicOne.contains("1"));
        assertTrue(text("ara", "2"), text("ara", "2").contains("محاولتين"));
        assertTrue(text("ara", "3"), text("ara", "3").contains("3 محاولات"));
        assertTrue(text("ara", "11"), text("ara", "11").contains("11 محاولة"));
        assertTrue(text("hin", "1"), text("hin", "1").startsWith("1 प्रयास के बाद"));
        assertTrue(text("hin", "3"), text("hin", "3").startsWith("3 प्रयासों के बाद"));
        assertTrue(text("kan", "1"), text("kan", "1").startsWith("1 ಪ್ರಯತ್ನದ ನಂತರವೂ"));
        assertTrue(text("kan", "3"), text("kan", "3").startsWith("3 ಪ್ರಯತ್ನಗಳ ನಂತರವೂ"));
        assertTrue(text("tam", "1"), text("tam", "1").startsWith("1 முயற்சிக்குப் பிறகும்"));
        assertTrue(text("tam", "3"), text("tam", "3").startsWith("3 முயற்சிகளுக்குப் பிறகும்"));
    }

    @Test
    public void pluralKeysAreMappedAndNoAttemptTextReadsOneAttempts() {
        assertTrue(MessageResolver.isPlural(MessageKeys.RETRY_MAX_RESIDENT));
        assertFalse(MessageResolver.isPlural(MessageKeys.RETRY_AVAILABLE));
        assertEquals(MessageResolver.PLURAL_KEYS.size(), MessageResolver.PLURAL_KEYS.stream()
                .filter(k -> MessageResolver.resourceId(k) != null).count());
        for (String lang : LANGUAGES) {
            MessageResolver r = new MessageResolver(resources(lang), ConfigSource.empty(), lang);
            for (String key : MessageResolver.PLURAL_KEYS) {
                String one = r.text(key, Collections.singletonList("1"));
                assertFalse(lang + " " + one, one.contains("1 attempts"));
                assertFalse(lang + " placeholder left: " + one, PLACEHOLDER.matcher(one).find());
            }
        }
    }

    @Test
    public void pluralWithoutACountUsesTheGeneralForm() {
        assertEquals(MessageResolver.UNKNOWN_QUANTITY, MessageResolver.quantity(null));
        assertEquals(MessageResolver.UNKNOWN_QUANTITY, MessageResolver.quantity(Collections.singletonList("x")));
        assertEquals(2, MessageResolver.quantity(Arrays.asList(" 2 ", "3")));
        MessageResolver r = new MessageResolver(resources("eng"), ConfigSource.empty(), "eng");
        assertTrue(r.text(MessageKeys.RETRY_MAX_RESIDENT, null).contains("after {0} attempts."));
    }

    @Test
    public void pluralTextCanStillBeReplacedBySyncedGuidance() {
        ConfigSource config = ConfigSource.of(Collections.singletonMap(
                LivenessConfigKeys.guidanceKey(MessageKeys.RETRY_MAX_RESIDENT, "eng"), "Stopped after {0} tries."));
        MessageResolver synced = new MessageResolver(resources("eng"), config, "eng");
        assertEquals("Stopped after 1 tries.",
                synced.text(MessageKeys.RETRY_MAX_RESIDENT, Collections.singletonList("1")));
    }

    @Test
    public void lookStraightTextExistsInEveryLanguage() {
        MessageResolver english = new MessageResolver(resources("eng"), ConfigSource.empty(), "eng");
        assertEquals("Look directly at the camera and hold still.",
                english.text(MessageKeys.FEEDBACK_LOOK_STRAIGHT, null));
        MessageResolver french = new MessageResolver(resources("fra"), ConfigSource.empty(), "fra");
        assertEquals("Regardez directement la caméra et ne bougez plus.",
                french.text(MessageKeys.FEEDBACK_LOOK_STRAIGHT, null));
        MessageResolver arabic = new MessageResolver(resources("ara"), ConfigSource.empty(), "ara");
        assertEquals("انظر مباشرة إلى الكاميرا واثبت دون حركة.",
                arabic.text(MessageKeys.FEEDBACK_LOOK_STRAIGHT, null));
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

    private static String text(String arcLanguage, String count) {
        return new MessageResolver(resources(arcLanguage), ConfigSource.empty(), arcLanguage)
                .text(MessageKeys.RETRY_MAX_RESIDENT, Collections.singletonList(count));
    }

    /** The word after {@code prefix} in {@code text}. */
    private static String word(String text, String prefix) {
        int start = text.indexOf(prefix);
        assertTrue(text, start >= 0);
        String rest = text.substring(start + prefix.length());
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    /** A message's text as bundled; plural messages give their general form (the count left as {0}). */
    private static String bundleText(Resources res, String key, int id) {
        return MessageResolver.isPlural(key) ? res.getQuantityString(id, MessageResolver.UNKNOWN_QUANTITY)
                : res.getString(id);
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
