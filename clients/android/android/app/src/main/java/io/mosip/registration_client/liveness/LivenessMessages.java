package io.mosip.registration_client.liveness;

import android.content.Context;
import android.content.res.Configuration;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.registration.livenessmanager.LivenessRuntime;
import io.mosip.registration.livenessmanager.ui.MessageResolver;

/**
 * Text of the liveness engine's message keys in a given language, for the Flutter screens. Uses the same string
 * resources and synced guidance overrides as the capture screen, so a message reads the same on the capture
 * screen, in the Flutter screens and on the desktop client.
 */
@Singleton
public class LivenessMessages {

    private final LivenessRuntime runtime;

    /**
     * Creates the resolver. (ARC's component binds {@code Context} twice, so the context is a call argument.)
     *
     * @param runtime liveness runtime (configuration for the synced overrides)
     */
    @Inject
    public LivenessMessages(LivenessRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Returns a message.
     *
     * @param context  any context of the app
     * @param language ARC language code
     * @param key      engine message key; {@code null} gives {@code null}
     * @param args     values for {@code {0}}, {@code {1}}
     * @return the text, or {@code null} for a {@code null} key
     */
    public String text(Context context, String language, String key, List<String> args) {
        if (key == null) {
            return null;
        }
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(MessageResolver.localeOf(language));
        Context localized = context.createConfigurationContext(configuration);
        return new MessageResolver(localized.getResources(), runtime.config(), language).text(key, args);
    }
}
