package io.mosip.liveness.core;

import java.util.Locale;
import java.util.Optional;

/**
 * The client flow that asks for a liveness check.
 *
 * <p>Each workflow has its own policy ({@code mosip.registration.liveness.policy.<id>}) and may override
 * the passive threshold, the number of challenges, the retry limit and the behaviour after the last
 * failed attempt. Five identifiers reuse MOSIP process names; {@link #REGISTRATION} and
 * {@link #EXCEPTION_PHOTO} are added by this project.
 */
public enum Workflow {

    /** Resident face capture during a new registration or update. */
    REGISTRATION("registration", false),

    /** Operator or supervisor face capture while the user is onboarded to the machine. */
    ONBOARD_AUTH("onboard_auth", true),

    /** Operator face login (desktop client only). */
    LOGIN_AUTH("login_auth", true),

    /** Operator face authentication that closes a registration packet. */
    PACKET_AUTH("packet_auth", true),

    /** Supervisor (reviewer) face authentication for a packet with biometric exceptions. */
    EXCEPTION_AUTH("exception_auth", true),

    /** Supervisor face authentication for end-of-day packet approval. */
    EOD_AUTH("eod_auth", true),

    /** Photo of a resident's missing biometric (exception photo); liveness is off by default. */
    EXCEPTION_PHOTO("exception_photo", false);

    private final String id;
    private final boolean auth;

    Workflow(String id, boolean auth) {
        this.id = id;
        this.auth = auth;
    }

    /**
     * Returns the lower-case identifier used in configuration keys, audit payloads and packet evidence.
     *
     * @return the identifier, for example {@code "packet_auth"}
     */
    public String id() {
        return id;
    }

    /**
     * Tells whether this workflow authenticates an operator or supervisor (as opposed to capturing a resident).
     *
     * @return {@code true} for the five authentication workflows
     */
    public boolean isAuth() {
        return auth;
    }

    /**
     * Tells whether this workflow captures a resident's face.
     *
     * @return {@code true} for {@link #REGISTRATION} and {@link #EXCEPTION_PHOTO}
     */
    public boolean isResident() {
        return !auth;
    }

    /**
     * Finds a workflow by its identifier, ignoring case and surrounding spaces.
     *
     * @param id identifier such as {@code "eod_auth"}; may be {@code null}
     * @return the workflow, or empty if the identifier is unknown
     */
    public static Optional<Workflow> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Workflow w : values()) {
            if (w.id.equals(wanted)) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }
}
