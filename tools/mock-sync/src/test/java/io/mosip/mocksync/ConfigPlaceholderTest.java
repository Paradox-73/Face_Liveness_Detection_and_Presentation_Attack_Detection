/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.mosip.mocksync.data.ConfigService;

/**
 * Placeholder resolution used to turn mosip-config files into the values clients receive.
 */
class ConfigPlaceholderTest {

	private final Map<String, String> lookup = Map.of("a", "1", "b", "${a}-2", "url", "https://${host}", "host", "h");

	@Test
	void resolvesNestedReferencesAndDefaults() {
		assertEquals("1-2-x", ConfigService.resolve("${b}-x", lookup));
		assertEquals("https://h/p", ConfigService.resolve("${url}/p", lookup));
		assertEquals("dflt", ConfigService.resolve("${missing:dflt}", lookup));
		assertEquals("", ConfigService.resolve("${missing:}", lookup));
		assertEquals("1", ConfigService.resolve("${missing:${a}}", lookup));
		assertEquals("plain %{X-B3}i", ConfigService.resolve("plain %{X-B3}i", lookup));
	}

	@Test
	void unresolvablePlaceholdersDropTheValue() {
		assertNull(ConfigService.resolve("${keycloak.external.url}/auth", lookup));
		assertNull(ConfigService.resolve("${unterminated", lookup));
	}
}
