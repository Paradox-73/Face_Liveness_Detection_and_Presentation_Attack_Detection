/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.ServerKeys;
import io.mosip.mocksync.data.UserStore.User;

/**
 * Issues the auth and refresh tokens returned by {@code authenticate/useridpwd}. Both clients only decode the tokens
 * (auth0 {@code JWT.decode}) and read {@code exp}, {@code name} and {@code realm_access.roles}; the tokens are still
 * RS256-signed with the KERNEL SIGN key so any JWT library can parse them.
 */
@Component
public class TokenService {

	private final ServerKeys keys;
	private final MockSyncProperties properties;
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Creates the service.
	 *
	 * @param keys       server keys
	 * @param properties MockSync settings (token lifetime)
	 */
	public TokenService(ServerKeys keys, MockSyncProperties properties) {
		this.keys = keys;
		this.properties = properties;
	}

	/**
	 * Builds the plain token response JSON: {@code token}, {@code refreshToken}, {@code expiryTime} and
	 * {@code refreshExpiryTime} (lifetimes in seconds).
	 *
	 * @param user    authenticated user
	 * @param machine machine name, recorded in the token
	 * @return JSON text to be encrypted for the machine
	 */
	public String tokenResponse(User user, String machine) {
		long lifetime = properties.getTokenValiditySeconds();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("token", jwt(user, machine, "Bearer", lifetime));
		body.put("refreshToken", jwt(user, machine, "Refresh", lifetime));
		body.put("expiryTime", lifetime);
		body.put("refreshExpiryTime", lifetime);
		return toJson(body);
	}

	private String jwt(User user, String machine, String type, long lifetime) {
		long now = Instant.now().getEpochSecond();
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("jti", UUID.randomUUID().toString());
		claims.put("iss", "mock-sync");
		claims.put("sub", user.getUserId());
		claims.put("typ", type);
		claims.put("azp", "mosip-reg-client");
		claims.put("iat", now);
		claims.put("exp", now + lifetime);
		claims.put("preferred_username", user.getUserId());
		claims.put("userId", user.getUserId());
		claims.put("name", user.getName());
		claims.put("machine", machine);
		claims.put("realm_access", Map.of("roles", user.getRoles()));
		claims.put("scope", "profile email");
		return keys.signer().signJwt(toJson(claims));
	}

	private String toJson(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}
}
