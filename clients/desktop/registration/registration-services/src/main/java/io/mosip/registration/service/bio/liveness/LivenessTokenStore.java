package io.mosip.registration.service.bio.liveness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.device.DeviceError;

/**
 * Registry of the liveness pass tokens that are waiting for their signed capture.
 *
 * <p>A liveness run registers its token when the session reaches {@code CAPTURE_READY}; the gate in
 * {@code BioServiceImpl.captureModality} consumes it. A token is <b>single-use</b>: the first
 * {@link #consume(LivenessPassToken, Instant)} marks it used and every later one is refused as reused. A token
 * is refused after its {@link LivenessPassToken#expiresAt()} ({@code issuedAt + binding.max_gap_ms + capture
 * timeout}) and when the presented token differs in any field from the issued one. Used and expired entries are
 * kept for a short while so a replay is reported as reused rather than unknown.
 *
 * <p>Thread-safe.
 */
@Component
public class LivenessTokenStore {

	/** Longest time an entry is kept after its token expired. */
	static final long RETENTION_AFTER_EXPIRY_MS = 60_000L;
	private static final int MAX_ENTRIES = 64;

	/** Why a presented token was refused. */
	public enum Refusal {
		/** No token with this session ID was issued, or it differs from the issued one. */
		UNKNOWN,
		/** The token was already used. */
		REUSED,
		/** The token has expired. */
		EXPIRED
	}

	/**
	 * The session side of a pass: what the gate needs to bind the signed capture to the session that issued the
	 * token. Implementations run each call on the session's own thread.
	 */
	public interface PassHandle {

		/** Tells the session that the signed capture starts ({@code CAPTURE_READY -> CAPTURING}). */
		void captureStarted();

		/**
		 * Tells the session that the signed capture failed.
		 *
		 * @param error the device error
		 */
		void captureFailed(DeviceError error);

		/**
		 * Binds the signed capture to the session.
		 *
		 * @param request   the capture and the presented token
		 * @param timeoutMs longest wait for the session thread
		 * @return the verdict and the session result
		 * @throws LivenessTimeoutException if the session did not answer in time
		 */
		BindOutcome bind(BindingRequest request, long timeoutMs) throws LivenessTimeoutException;

		/**
		 * Returns the supervisor who granted an override, for a pass that did not come from a live session.
		 *
		 * @return the supervisor ID; empty for a normal pass
		 */
		default Optional<String> overrideSupervisor() {
			return Optional.empty();
		}

		/**
		 * Tells whether this handle writes the override audit event ({@code REG-EVT-LV16}) itself, with both user IDs
		 * and the photo's score bucket, so that the gate does not write a second one.
		 *
		 * @return {@code true} when the handle audits the override
		 */
		default boolean auditsOverride() {
			return false;
		}
	}

	/** Verdict of a binding plus the final result of the session. */
	public static final class BindOutcome {

		private final BindingVerdict verdict;
		private final LivenessResult result;

		/**
		 * Creates an outcome.
		 *
		 * @param verdict the binding verdict
		 * @param result  the session result, or {@code null} if the session has not ended
		 */
		public BindOutcome(BindingVerdict verdict, LivenessResult result) {
			this.verdict = Objects.requireNonNull(verdict, "verdict");
			this.result = result;
		}

		/**
		 * Returns the binding verdict.
		 *
		 * @return the verdict
		 */
		public BindingVerdict verdict() {
			return verdict;
		}

		/**
		 * Returns the session result.
		 *
		 * @return the result, if the session ended
		 */
		public Optional<LivenessResult> result() {
			return Optional.ofNullable(result);
		}
	}

	/** Thrown when a presented token is refused. */
	public static final class TokenRefusedException extends Exception {

		private static final long serialVersionUID = 1L;
		private final Refusal refusal;

		TokenRefusedException(Refusal refusal) {
			super("liveness pass refused: " + refusal);
			this.refusal = refusal;
		}

		/**
		 * Returns why the token was refused.
		 *
		 * @return the refusal reason
		 */
		public Refusal refusal() {
			return refusal;
		}
	}

	/** Thrown when the session thread does not answer in time. */
	public static final class LivenessTimeoutException extends Exception {

		private static final long serialVersionUID = 1L;

		/**
		 * Creates the exception.
		 *
		 * @param message what timed out
		 */
		public LivenessTimeoutException(String message) {
			super(message);
		}
	}

	private static final class Entry {
		final LivenessPassToken token;
		final PassHandle handle;
		boolean used;

		Entry(LivenessPassToken token, PassHandle handle) {
			this.token = token;
			this.handle = handle;
		}
	}

	private final Map<String, Entry> entries = new LinkedHashMap<>();

	/**
	 * Registers the token of a session that reached {@code CAPTURE_READY}.
	 *
	 * @param token  the pass token
	 * @param handle the session side of the pass
	 */
	public synchronized void register(LivenessPassToken token, PassHandle handle) {
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(handle, "handle");
		purge(token.issuedAt());
		entries.remove(token.sessionId());
		entries.put(token.sessionId(), new Entry(token, handle));
		while (entries.size() > MAX_ENTRIES) {
			Iterator<String> oldest = entries.keySet().iterator();
			oldest.next();
			oldest.remove();
		}
	}

	/**
	 * Consumes a presented token: checks it and marks it used.
	 *
	 * @param presented the token presented with a capture
	 * @param now       the current time
	 * @return the session side of the pass
	 * @throws TokenRefusedException if the token is unknown, differs from the issued one, was used or expired
	 */
	public synchronized PassHandle consume(LivenessPassToken presented, Instant now) throws TokenRefusedException {
		Objects.requireNonNull(presented, "presented");
		Entry e = entries.get(presented.sessionId());
		if (e == null || !e.token.equals(presented)) {
			throw new TokenRefusedException(Refusal.UNKNOWN);
		}
		if (e.used) {
			throw new TokenRefusedException(Refusal.REUSED);
		}
		e.used = true;
		if (e.token.isExpiredAt(now)) {
			throw new TokenRefusedException(Refusal.EXPIRED);
		}
		return e.handle;
	}

	/**
	 * Returns the only token that is registered, unused and unexpired, for capture calls that cannot name one.
	 *
	 * @param now the current time
	 * @return the token; empty when there is none or more than one
	 */
	public synchronized Optional<LivenessPassToken> pendingToken(Instant now) {
		List<LivenessPassToken> open = new ArrayList<>();
		for (Entry e : entries.values()) {
			if (!e.used && !e.token.isExpiredAt(now)) {
				open.add(e.token);
			}
		}
		return open.size() == 1 ? Optional.of(open.get(0)) : Optional.<LivenessPassToken>empty();
	}

	/**
	 * Withdraws the token of a session that ended or was cancelled before its capture; a later presentation is
	 * refused as reused.
	 *
	 * @param sessionId the session
	 */
	public synchronized void revoke(String sessionId) {
		Entry e = entries.get(sessionId);
		if (e != null) {
			e.used = true;
		}
	}

	/**
	 * Returns the number of entries kept (tests and diagnostics).
	 *
	 * @return entries, used or not
	 */
	public synchronized int size() {
		return entries.size();
	}

	private void purge(Instant now) {
		Instant limit = now.minusMillis(RETENTION_AFTER_EXPIRY_MS);
		entries.values().removeIf(e -> e.token.expiresAt().isBefore(limit));
	}
}
