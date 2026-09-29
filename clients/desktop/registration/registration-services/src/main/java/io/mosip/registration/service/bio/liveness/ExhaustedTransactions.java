package io.mosip.registration.service.bio.liveness;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.mosip.liveness.core.Workflow;

/**
 * Remembers liveness transactions whose attempts are used up ({@code max_retries} reached), per workflow and person,
 * so that closing the liveness window and opening it again cannot start a fresh set of attempts for the same person.
 *
 * <p>Entries expire: a resident's entry is keyed by the registration ID, which is never reused, and an operator's
 * entry follows the client's invalid-login lock time. The list lives in memory only; a client restart clears it.
 *
 * <p>Thread-safe.
 */
final class ExhaustedTransactions {

	private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();

	/**
	 * Records that a transaction used up its attempts.
	 *
	 * @param workflow   the workflow
	 * @param subjectRef the registration ID or user ID
	 * @param until      end of the block
	 */
	void record(Workflow workflow, String subjectRef, Instant until) {
		blockedUntil.put(key(workflow, subjectRef), Objects.requireNonNull(until, "until"));
	}

	/**
	 * Returns the end of the block for a person in a workflow, removing expired entries.
	 *
	 * @param workflow   the workflow
	 * @param subjectRef the registration ID or user ID
	 * @param now        current time
	 * @return the end of the block while it lasts
	 */
	Optional<Instant> blockedUntil(Workflow workflow, String subjectRef, Instant now) {
		blockedUntil.values().removeIf(until -> !until.isAfter(now));
		return Optional.ofNullable(blockedUntil.get(key(workflow, subjectRef)));
	}

	private static String key(Workflow workflow, String subjectRef) {
		return Objects.requireNonNull(workflow, "workflow").id() + '|' + Objects.requireNonNull(subjectRef, "subjectRef");
	}
}
