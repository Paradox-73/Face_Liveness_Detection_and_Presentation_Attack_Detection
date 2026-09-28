package io.mosip.mocksync.web;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs one line per request: method, path, status and time. Bodies and query strings are not logged, because they
 * carry passwords, tokens and encrypted biometric data.
 */
@Component
@Order(0)
public class RequestLogFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger("mocksync.requests");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long start = System.currentTimeMillis();
		try {
			chain.doFilter(request, response);
		} finally {
			LOGGER.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
					System.currentTimeMillis() - start);
		}
	}
}
