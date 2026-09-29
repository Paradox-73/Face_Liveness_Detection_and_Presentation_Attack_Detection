package io.mosip.registration.livenessmanager.auth;

import java.util.List;

/**
 * What face authentication needs to know about registration staff, all from the client's local database, so it
 * works offline.
 */
public interface OperatorDirectory {

    /**
     * Returns the user's roles.
     *
     * @param userId user ID
     * @return role codes, for example {@code REGISTRATION_SUPERVISOR}; empty for an unknown user
     */
    List<String> roles(String userId);

    /**
     * Returns the user's enrolled face records (stored at operator onboarding).
     *
     * @param userId user ID
     * @return ISO/IEC 19794-5 face records; empty when none is enrolled
     */
    List<byte[]> enrolledFaces(String userId);

    /**
     * Tells whether the account is locked.
     *
     * @param userId user ID
     * @return {@code true} while locked
     */
    boolean isLocked(String userId);

    /**
     * Counts one failed sign-in towards the lockout ({@code mosip.registration.invalid_login_count}).
     *
     * @param userId user ID
     */
    void recordFailure(String userId);

    /**
     * Locks the account for the configured lockout time ({@code BLOCK_AND_LOCK}).
     *
     * @param userId user ID
     */
    void lock(String userId);
}
