/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoopbackOnlyFilterTest {

	private static int status(String uri, String remote, String origin) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRemoteAddr(remote);
		if (origin != null) {
			request.addHeader("Origin", origin);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		new LoopbackOnlyFilter().doFilter(request, response, new MockFilterChain());
		return response.getStatus();
	}

	@Test
	void encodedOrDoubledAdminPathFromTheNetworkIsRefused() throws Exception {
		assertEquals(403, status("/admin/users", "192.168.1.5", null));
		assertEquals(403, status("/%61dmin/users", "192.168.1.5", null));
		assertEquals(403, status("//admin/users", "192.168.1.5", null));
		assertEquals(403, status("/admin;x=1/users", "192.168.1.5", null));
	}

	@Test
	void browserRequestsToAdminAreRefusedEvenFromLocalhost() throws Exception {
		assertEquals(200, status("/admin/users", "127.0.0.1", null));
		assertEquals(403, status("/%61dmin/users", "127.0.0.1", "http://evil.example"));
		assertEquals(200, status("/v1/syncdata/actuator/health", "192.168.1.5", null));
	}
}
