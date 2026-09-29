package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;

import org.junit.Test;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.device.DeviceError;

/** Tests {@link LivenessTokenStore}: single use, expiry, tampering, withdrawal and the pending-token lookup. */
public class LivenessTokenStoreTest {

	private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

	private final LivenessTokenStore store = new LivenessTokenStore();

	private static LivenessPassToken token(String session, String subject) {
		return LivenessPassToken.builder().sessionId(session).workflow(Workflow.REGISTRATION).subjectRef(subject)
				.deviceSerial("SN1").nonce("nonce-0123456789-" + session).issuedAt(T0).expiresAt(T0.plusMillis(5_000)).build();
	}

	private static final LivenessTokenStore.PassHandle HANDLE = new LivenessTokenStore.PassHandle() {
		@Override
		public void captureStarted() {
		}

		@Override
		public void captureFailed(DeviceError error) {
		}

		@Override
		public LivenessTokenStore.BindOutcome bind(BindingRequest request, long timeoutMs) {
			return new LivenessTokenStore.BindOutcome(BindingVerdict.passed().build(), null);
		}
	};

	private void assertRefusal(LivenessPassToken t, Instant now, LivenessTokenStore.Refusal expected) {
		try {
			store.consume(t, now);
			fail("expected " + expected);
		} catch (LivenessTokenStore.TokenRefusedException e) {
			assertEquals(expected, e.refusal());
		}
	}

	@Test
	public void tokenIsConsumedOnce() throws Exception {
		LivenessPassToken t = token("s1", "rid");
		store.register(t, HANDLE);
		assertSame(HANDLE, store.consume(t, T0.plusMillis(100)));
		assertRefusal(t, T0.plusMillis(200), LivenessTokenStore.Refusal.REUSED);
	}

	@Test
	public void expiredTokenIsRefusedAndStaysUsed() {
		LivenessPassToken t = token("s1", "rid");
		store.register(t, HANDLE);
		assertRefusal(t, T0.plusMillis(5_001), LivenessTokenStore.Refusal.EXPIRED);
		assertRefusal(t, T0.plusMillis(100), LivenessTokenStore.Refusal.REUSED);
	}

	@Test
	public void unknownOrAlteredTokenIsRefused() {
		store.register(token("s1", "rid"), HANDLE);
		assertRefusal(token("s2", "rid"), T0, LivenessTokenStore.Refusal.UNKNOWN);
		assertRefusal(token("s1", "someone-else"), T0, LivenessTokenStore.Refusal.UNKNOWN);
	}

	@Test
	public void revokedTokenCannotBeUsed() {
		LivenessPassToken t = token("s1", "rid");
		store.register(t, HANDLE);
		store.revoke("s1");
		assertRefusal(t, T0, LivenessTokenStore.Refusal.REUSED);
	}

	@Test
	public void pendingTokenOnlyWhenExactlyOneIsOpen() throws Exception {
		assertFalse(store.pendingToken(T0).isPresent());
		LivenessPassToken a = token("a", "rid");
		store.register(a, HANDLE);
		assertEquals(a, store.pendingToken(T0).get());
		store.register(token("b", "rid"), HANDLE);
		assertFalse("ambiguous", store.pendingToken(T0).isPresent());
		store.consume(a, T0);
		assertTrue(store.pendingToken(T0).isPresent());
		assertFalse("expired", store.pendingToken(T0.plusMillis(6_000)).isPresent());
	}

	@Test
	public void oldEntriesArePurged() {
		store.register(token("old", "rid"), HANDLE);
		LivenessPassToken late = LivenessPassToken.builder().sessionId("new").workflow(Workflow.REGISTRATION)
				.subjectRef("rid").deviceSerial("SN1").nonce("nonce-0123456789abcdef").issuedAt(T0.plusSeconds(3_600))
				.expiresAt(T0.plusSeconds(3_605)).build();
		store.register(late, HANDLE);
		assertEquals(1, store.size());
	}
}
