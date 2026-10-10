/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import io.mosip.liveness.core.Workflow;

public class AttemptHistoryTest {

	private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

	/** The local database, as the history sees it. */
	private static final class MapStore implements AttemptHistory.Store {
		final Map<String, String> rows = new HashMap<>();

		@Override
		public Map<String, String> load() {
			return new HashMap<>(rows);
		}

		@Override
		public void put(String key, String value) {
			rows.put(key, value);
		}

		@Override
		public void remove(String key) {
			rows.remove(key);
		}
	}

	@Test
	public void countsAndBlocksSurviveARestart() {
		MapStore db = new MapStore();
		AttemptHistory before = new AttemptHistory();
		before.persistTo(db);
		before.counted(Workflow.LOGIN_AUTH, "officer1", 3, true, NOW.plusSeconds(600));
		before.block(Workflow.LOGIN_AUTH, "officer1", NOW.plusSeconds(600));
		before.lowLight(Workflow.REGISTRATION, "rid-1", 2, NOW.plusSeconds(600));

		AttemptHistory restarted = new AttemptHistory();
		restarted.persistTo(db);
		AttemptHistory.Entry login = restarted.get(Workflow.LOGIN_AUTH, "officer1", NOW).get();
		assertEquals(3, login.countedFailures());
		assertTrue(login.isSuspectedAttack());
		assertEquals(NOW.plusSeconds(600), login.blockedUntil().get());
		assertEquals(2, restarted.get(Workflow.REGISTRATION, "rid-1", NOW).get().lowLightRetries());

		restarted.clear(Workflow.LOGIN_AUTH, "officer1");
		assertEquals(1, db.rows.size());
		assertFalse("expired entries are removed from the database too",
				restarted.get(Workflow.REGISTRATION, "rid-1", NOW.plusSeconds(601)).isPresent());
		assertTrue(db.rows.isEmpty());
	}

	@Test
	public void unreadableSavedEntryIsDropped() {
		MapStore db = new MapStore();
		db.rows.put("login_auth|officer1", "garbage");
		AttemptHistory history = new AttemptHistory();
		history.persistTo(db);
		assertFalse(history.get(Workflow.LOGIN_AUTH, "officer1", NOW).isPresent());
	}
}
