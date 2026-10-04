/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import io.mosip.registration.clientmanager.entity.UserBiometric;
import io.mosip.registration.clientmanager.repository.UserBiometricRepository;
import io.mosip.registration.clientmanager.service.LoginService;

public class ArcOperatorDirectoryTest {

    private final LoginService login = mock(LoginService.class);
    private final UserBiometricRepository biometrics = mock(UserBiometricRepository.class);
    private final ArcOperatorDirectory directory = new ArcOperatorDirectory(login, biometrics);

    @Test
    public void enrolledFacesAreTheUsersUsableFaceRows() {
        when(biometrics.findAllOperatorBiometrics(ArcOperatorDirectory.FACE)).thenReturn(Arrays.asList(
                row("Officer1", new byte[] {1}, false),
                row("officer2", new byte[] {2}, false),
                row("officer1", new byte[0], false),
                row("officer1", new byte[] {3}, true),
                row("officer1", new byte[] {4}, null)));

        List<byte[]> faces = directory.enrolledFaces("officer1");

        assertEquals(2, faces.size());
        assertEquals(1, faces.get(0)[0]);
        assertEquals(4, faces.get(1)[0]);
    }

    @Test
    public void missingRowsAndRolesAreEmpty() {
        assertTrue(directory.enrolledFaces("officer1").isEmpty());
        assertTrue(directory.roles("officer1").isEmpty());
    }

    @Test
    public void lockRecordsFailuresUntilTheAccountIsLocked() {
        when(login.isUserLocked("officer1")).thenReturn(false, false, false, true);
        directory.lock("officer1");
        verify(login, times(3)).recordFailedLoginAttempt("officer1");
    }

    @Test
    public void lockStopsAfterTheUpperBound() {
        directory.lock("officer1");
        verify(login, times(ArcOperatorDirectory.MAX_LOCK_STEPS)).recordFailedLoginAttempt("officer1");
    }

    private static UserBiometric row(String userId, byte[] template, Boolean deleted) {
        UserBiometric row = new UserBiometric();
        row.setUsrId(userId);
        row.setBioTemplate(template);
        row.setIsDeleted(deleted);
        return row;
    }
}
