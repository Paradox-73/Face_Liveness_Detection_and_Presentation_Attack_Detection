package io.mosip.registration.livenessmanager.ui;

import io.mosip.liveness.core.ui.Severity;
import io.mosip.registration.livenessmanager.R;

/**
 * The status colours of the capture screen, shared with the desktop client ({@code liveness-panel.css}) and ARC's
 * liveness banner: neutral for information and instructions, success, attention for warnings and guidance, and
 * failure. Each state has a light banner behind the prompt, an accent for the banner border and the icon circle,
 * a prompt text colour and an oval colour for the face guide over the camera preview. Colour is never the only
 * signal: every state also has its own text and icon.
 */
enum StatusPalette {

    /** Information and instructions ({@link Severity#INFO}). */
    NEUTRAL(R.drawable.liveness_status_neutral, R.color.liveness_status_neutral_banner,
            R.color.liveness_status_neutral_accent, R.color.liveness_status_neutral_text,
            R.color.liveness_oval_neutral),
    /** Success ({@link Severity#SUCCESS}). */
    SUCCESS(R.drawable.liveness_status_success, R.color.liveness_status_success_banner,
            R.color.liveness_status_success_accent, R.color.liveness_status_success_text,
            R.color.liveness_oval_success),
    /** Attention: warnings and guidance ({@link Severity#WARNING}). */
    ATTENTION(R.drawable.liveness_status_attention, R.color.liveness_status_attention_banner,
            R.color.liveness_status_attention_accent, R.color.liveness_status_attention_text,
            R.color.liveness_oval_attention),
    /** Failure ({@link Severity#ERROR}). */
    FAILURE(R.drawable.liveness_status_failure, R.color.liveness_status_failure_banner,
            R.color.liveness_status_failure_accent, R.color.liveness_status_failure_text,
            R.color.liveness_oval_failure);

    /** Banner drawable: light background with an accent border on the start side. */
    final int bannerDrawable;
    /** Banner background colour. */
    final int banner;
    /** Border and icon-circle colour. */
    final int accent;
    /** Prompt text colour on the banner. */
    final int text;
    /** Oval outline colour over the camera preview. */
    final int oval;

    StatusPalette(int bannerDrawable, int banner, int accent, int text, int oval) {
        this.bannerDrawable = bannerDrawable;
        this.banner = banner;
        this.accent = accent;
        this.text = text;
        this.oval = oval;
    }

    /**
     * Returns the palette entry of a state's severity.
     *
     * @param severity the severity; {@code null} counts as information
     * @return the status colours
     */
    static StatusPalette of(Severity severity) {
        if (severity == null) {
            return NEUTRAL;
        }
        switch (severity) {
            case SUCCESS:
                return SUCCESS;
            case WARNING:
                return ATTENTION;
            case ERROR:
                return FAILURE;
            default:
                return NEUTRAL;
        }
    }
}
