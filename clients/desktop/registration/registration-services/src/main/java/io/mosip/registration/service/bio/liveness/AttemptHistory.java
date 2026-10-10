/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import io.mosip.liveness.core.Workflow;

/**
 * Remembers, per workflow and person (registration ID or user ID), the liveness failures that used up an attempt,
 * so that closing the liveness window and opening it again cannot start a fresh set of attempts.
 *
 * <p>A new {@link LivenessRun} starts with the counted failures of the earlier windows: "fail twice, Cancel, open
 * again" continues at the third attempt. The history also keeps whether any counted failure was a suspected attack
 * (PAD, integrity or binding), which forbids the supervisor override for that person in that workflow. A pass
 * clears the entry. Outcomes that do not use up an attempt (quality, device or engine errors, cancellation) never
 * change the counted failures.
 *
 * <p>The entry also keeps the uncounted low-light results since the last counted failure ({@code QUALITY} with
 * {@code LOW_LIGHT}: a passive PAD failure on a face lit too weakly, "Improve lighting."). The next run passes the
 * number to the session driver, so the engine's allowance ({@code passive.low_light_max_uncounted}) applies per
 * capture, not per window: "dim, dim, Cancel, open again, dim" leaves no further uncounted try. A counted failure
 * or a pass resets it.
 *
 * <p>Entries expire: each counted failure keeps the entry for the workflow's block time
 * ({@link LivenessConfig#exhaustedBlockMinutes(Workflow)}), and a transaction that used up its attempts blocks the
 * person until the same time. A resident's entry is keyed by the registration ID, which is never reused; an
 * operator's entry follows the client's invalid-login lock time. With a {@link Store} (the orchestrator sets the
 * client's local database) every change is written through and the entries are read back after a restart, so a
 * restart does not give a fresh set of attempts.
 *
 * <p>Thread-safe.
 */
final class AttemptHistory {

	/** What is known about one person in one workflow. Immutable. */
	static final class Entry {

		private final int countedFailures;
		private final boolean suspectedAttack;
		private final int lowLightRetries;
		private final Instant blockedUntil;
		private final Instant expiresAt;

		private Entry(int countedFailures, boolean suspectedAttack, int lowLightRetries, Instant blockedUntil,
				Instant expiresAt) {
			this.countedFailures = countedFailures;
			this.suspectedAttack = suspectedAttack;
			this.lowLightRetries = lowLightRetries;
			this.blockedUntil = blockedUntil;
			this.expiresAt = expiresAt;
		}

		/**
		 * Returns the failures that used up an attempt.
		 *
		 * @return counted failures, at least 0
		 */
		int countedFailures() {
			return countedFailures;
		}

		/**
		 * Tells whether any counted failure was a suspected attack.
		 *
		 * @return {@code true} after a PAD, integrity or binding failure
		 */
		boolean isSuspectedAttack() {
			return suspectedAttack;
		}

		/**
		 * Returns the uncounted low-light results since the last counted failure.
		 *
		 * @return 0 or more; 0 after a counted failure
		 */
		int lowLightRetries() {
			return lowLightRetries;
		}

		/**
		 * Returns the end of the block after the attempts were used up.
		 *
		 * @return the end of the block; empty while attempts remain
		 */
		Optional<Instant> blockedUntil() {
			return Optional.ofNullable(blockedUntil);
		}

		String format() {
			return countedFailures + "," + suspectedAttack + "," + lowLightRetries + ","
					+ (blockedUntil == null ? "" : Long.toString(blockedUntil.toEpochMilli())) + ","
					+ expiresAt.toEpochMilli();
		}

		static Entry parse(String text) {
			try {
				String[] p = text.split(",", -1);
				return new Entry(Math.max(0, Integer.parseInt(p[0])), Boolean.parseBoolean(p[1]),
						Math.max(0, Integer.parseInt(p[2])), p[3].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(p[3])),
						Instant.ofEpochMilli(Long.parseLong(p[4])));
			} catch (RuntimeException e) {
				return null;
			}
		}
	}

	/** Saves entries across restarts, keyed like the history; the value is an opaque text. */
	interface Store {

		/**
		 * Reads every saved entry.
		 *
		 * @return key to saved text
		 */
		Map<String, String> load();

		/**
		 * Saves or replaces one entry.
		 *
		 * @param key   history key
		 * @param value saved text
		 */
		void put(String key, String value);

		/**
		 * Removes one entry.
		 *
		 * @param key history key
		 */
		void remove(String key);
	}

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();
	private Store store;

	private boolean loaded = true;

	/**
	 * Writes every later change through to {@code store}; the saved entries are read at the first use (the database
	 * may not be open yet when this is called). A saved entry that cannot be read is dropped.
	 *
	 * @param store where the entries are saved
	 */
	synchronized void persistTo(Store store) {
		this.store = Objects.requireNonNull(store, "store");
		loaded = false;
	}

	private void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		for (Map.Entry<String, String> e : store.load().entrySet()) {
			Entry entry = Entry.parse(e.getValue());
			if (entry != null) {
				entries.merge(e.getKey(), entry, (a, b) -> a);
			}
		}
	}

	/**
	 * Returns the entry of a person in a workflow, removing expired entries.
	 *
	 * @param workflow   the workflow
	 * @param subjectRef the registration ID or user ID
	 * @param now        current time
	 * @return the entry while it lasts
	 */
	synchronized Optional<Entry> get(Workflow workflow, String subjectRef, Instant now) {
		load();
		for (Map.Entry<String, Entry> e : new HashMap<>(entries).entrySet()) {
			if (!e.getValue().expiresAt.isAfter(now)) {
				save(e.getKey(), null);
			}
		}
		return Optional.ofNullable(entries.get(key(workflow, subjectRef)));
	}

	/**
	 * Records a failure that used up an attempt. The count never goes down: the stored count becomes the larger of
	 * the stored count and {@code attemptsUsed}, and a suspected attack stays recorded. The low-light count restarts
	 * at 0, as in the engine's transaction.
	 *
	 * @param workflow        the workflow
	 * @param subjectRef      the registration ID or user ID
	 * @param attemptsUsed    counted failures of the transaction so far, earlier windows included
	 * @param suspectedAttack whether any counted failure of the transaction was a suspected attack
	 * @param expiresAt       when the entry may be forgotten
	 */
	synchronized void counted(Workflow workflow, String subjectRef, int attemptsUsed, boolean suspectedAttack,
			Instant expiresAt) {
		Objects.requireNonNull(expiresAt, "expiresAt");
		update(key(workflow, subjectRef), old -> old == null
				? new Entry(Math.max(0, attemptsUsed), suspectedAttack, 0, null, expiresAt)
				: new Entry(Math.max(old.countedFailures, attemptsUsed), old.suspectedAttack || suspectedAttack, 0,
						old.blockedUntil, later(old.expiresAt, expiresAt)));
	}

	/**
	 * Records an uncounted low-light result: the stored low-light count becomes {@code lowLightRetries}, the count of
	 * the transaction after that result (earlier windows included). Counted failures, the attack mark and a block
	 * stay as they are. A count of 0 creates no entry.
	 *
	 * @param workflow        the workflow
	 * @param subjectRef      the registration ID or user ID
	 * @param lowLightRetries uncounted low-light results since the last counted failure
	 * @param expiresAt       when the entry may be forgotten
	 */
	synchronized void lowLight(Workflow workflow, String subjectRef, int lowLightRetries, Instant expiresAt) {
		Objects.requireNonNull(expiresAt, "expiresAt");
		int count = Math.max(0, lowLightRetries);
		update(key(workflow, subjectRef), old -> old == null
				? (count == 0 ? null : new Entry(0, false, count, null, expiresAt))
				: new Entry(old.countedFailures, old.suspectedAttack, count, old.blockedUntil,
						later(old.expiresAt, expiresAt)));
	}

	/**
	 * Records that a person used up the attempts of a workflow; new runs are refused until {@code until}.
	 *
	 * @param workflow   the workflow
	 * @param subjectRef the registration ID or user ID
	 * @param until      end of the block
	 */
	synchronized void block(Workflow workflow, String subjectRef, Instant until) {
		Objects.requireNonNull(until, "until");
		update(key(workflow, subjectRef), old -> old == null ? new Entry(0, false, 0, until, until)
				: new Entry(old.countedFailures, old.suspectedAttack, old.lowLightRetries, until,
						later(old.expiresAt, until)));
	}

	/**
	 * Forgets a person in a workflow after a pass.
	 *
	 * @param workflow   the workflow
	 * @param subjectRef the registration ID or user ID
	 */
	synchronized void clear(Workflow workflow, String subjectRef) {
		load();
		save(key(workflow, subjectRef), null);
	}

	private void update(String key, UnaryOperator<Entry> change) {
		load();
		save(key, change.apply(entries.get(key)));
	}

	/** Stores one entry in memory and in the store; {@code null} removes it. */
	private void save(String key, Entry entry) {
		if (entry == null) {
			entries.remove(key);
		} else {
			entries.put(key, entry);
		}
		if (store != null) {
			if (entry == null) {
				store.remove(key);
			} else {
				store.put(key, entry.format());
			}
		}
	}

	private static Instant later(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}

	private static String key(Workflow workflow, String subjectRef) {
		return Objects.requireNonNull(workflow, "workflow").id() + '|' + Objects.requireNonNull(subjectRef, "subjectRef");
	}
}
