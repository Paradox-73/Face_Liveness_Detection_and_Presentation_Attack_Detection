/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.auth;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.mosip.registration.clientmanager.entity.UserBiometric;
import io.mosip.registration.clientmanager.repository.UserBiometricRepository;
import io.mosip.registration.clientmanager.service.LoginService;

/**
 * {@link OperatorDirectory} over ARC's {@link LoginService} (roles, lockout) and {@link UserBiometricRepository}
 * (the {@code user_biometric} rows written at operator onboarding).
 */
public final class ArcOperatorDirectory implements OperatorDirectory {

    /** Biometric type code of face rows in {@code user_biometric}. */
    static final String FACE = "FACE";
    /** Upper bound of failures recorded to reach the lockout count (ARC's default count is 50). */
    static final int MAX_LOCK_STEPS = 100;

    private final LoginService loginService;
    private final UserBiometricRepository biometrics;

    /**
     * Creates the directory.
     *
     * @param loginService ARC's login service
     * @param biometrics   ARC's operator biometric repository
     */
    public ArcOperatorDirectory(LoginService loginService, UserBiometricRepository biometrics) {
        this.loginService = Objects.requireNonNull(loginService, "loginService");
        this.biometrics = Objects.requireNonNull(biometrics, "biometrics");
    }

    @Override
    public List<String> roles(String userId) {
        List<String> roles = loginService.getRolesByUserId(userId);
        return roles == null ? Collections.emptyList() : roles;
    }

    @Override
    public List<byte[]> enrolledFaces(String userId) {
        List<byte[]> faces = new ArrayList<>();
        List<UserBiometric> rows = biometrics.findAllOperatorBiometrics(FACE);
        if (rows == null) {
            return faces;
        }
        for (UserBiometric row : rows) {
            boolean usable = row.getBioTemplate() != null && row.getBioTemplate().length > 0
                    && !Boolean.TRUE.equals(row.getIsDeleted());
            if (usable && userId != null && userId.equalsIgnoreCase(row.getUsrId())) {
                faces.add(row.getBioTemplate());
            }
        }
        Log.d("Liveness", "Enrolled faces: " + faces.size() + " of " + rows.size() + " face rows");
        return faces;
    }

    @Override
    public boolean isLocked(String userId) {
        return loginService.isUserLocked(userId);
    }

    @Override
    public void recordFailure(String userId) {
        loginService.recordFailedLoginAttempt(userId);
    }

    /**
     * Locks the account. ARC has no direct lock call: its lock starts when the failure count reaches
     * {@code mosip.registration.invalid_login_count}, so failures are recorded until the account is locked.
     *
     * @param userId user ID
     */
    @Override
    public void lock(String userId) {
        for (int i = 0; i < MAX_LOCK_STEPS && !loginService.isUserLocked(userId); i++) {
            loginService.recordFailedLoginAttempt(userId);
        }
    }
}
