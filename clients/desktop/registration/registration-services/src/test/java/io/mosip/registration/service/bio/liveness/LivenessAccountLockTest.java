/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import io.mosip.liveness.core.Workflow;
import io.mosip.registration.dto.UserDTO;
import io.mosip.registration.service.login.LoginService;

/**
 * Tests {@link LivenessAccountLock}: {@code BLOCK_AND_LOCK} counts a used-up face authentication as a failed login
 * with the client's invalid-login count and time, and never touches resident workflows.
 */
public class LivenessAccountLockTest {

	private final Map<String, Object> values = new HashMap<>();
	private final LoginService login = Mockito.mock(LoginService.class);
	private final UserDTO officer = new UserDTO();
	private LivenessAccountLock lock;

	@Before
	public void setUp() {
		values.put("mosip.registration.invalid_login_count", "5");
		values.put("mosip.registration.invalid_login_time", "7");
		when(login.getUserDetail("officer1")).thenReturn(officer);
		lock = new LivenessAccountLock(login, LivenessConfig.of(values));
	}

	@Test
	public void failureIsCountedWithTheClientLoginRule() {
		when(login.validateInvalidLogin(any(UserDTO.class), anyString(), anyInt(), anyInt())).thenReturn(
				LivenessAccountLock.REASON);
		assertEquals(LivenessAccountLock.Result.COUNTED, lock.apply(Workflow.EOD_AUTH, "officer1"));
		verify(login).validateInvalidLogin(eq(officer), eq(LivenessAccountLock.REASON), eq(5), eq(7));
	}

	@Test
	public void reachingTheCountLocksTheAccount() {
		when(login.validateInvalidLogin(any(UserDTO.class), anyString(), anyInt(), anyInt())).thenReturn("ERROR");
		assertEquals(LivenessAccountLock.Result.LOCKED, lock.apply(Workflow.PACKET_AUTH, "officer1"));
	}

	@Test
	public void residentWorkflowsAndUnknownUsersAreNotLocked() {
		assertEquals(LivenessAccountLock.Result.NOT_APPLIED, lock.apply(Workflow.REGISTRATION, "officer1"));
		assertEquals(LivenessAccountLock.Result.NOT_APPLIED, lock.apply(Workflow.EOD_AUTH, "nobody"));
		assertEquals(LivenessAccountLock.Result.NOT_APPLIED, lock.apply(Workflow.EOD_AUTH, " "));
		verify(login, never()).validateInvalidLogin(any(UserDTO.class), anyString(), anyInt(), anyInt());
	}

	@Test
	public void missingOrBadConfigUsesTheClientDefaults() {
		values.clear();
		values.put("mosip.registration.invalid_login_count", "many");
		when(login.validateInvalidLogin(any(UserDTO.class), anyString(), anyInt(), anyInt())).thenReturn("x");
		lock.apply(Workflow.LOGIN_AUTH, "officer1");
		verify(login).validateInvalidLogin(eq(officer), anyString(), eq(LivenessConfig.DEFAULT_INVALID_LOGIN_COUNT),
				eq((int) LivenessConfig.DEFAULT_AUTH_BLOCK_MINUTES));
	}
}
