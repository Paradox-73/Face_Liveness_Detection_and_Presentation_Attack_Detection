/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.web;

import java.io.IOException;
import java.net.InetAddress;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects {@code /admin/**} requests that do not come from the local machine. The admin API has no login; it is meant
 * for the scripts and the operator on the demo laptop only.
 */
@Component
@Order(1)
public class LoopbackOnlyFilter extends OncePerRequestFilter {

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/admin");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "admin API is available from localhost only");
			return;
		}
		chain.doFilter(request, response);
	}
}
