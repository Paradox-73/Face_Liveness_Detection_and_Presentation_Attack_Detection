package io.mosip.registration_client.api_services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.entity.Audit;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.livenessmanager.auth.FaceAuthenticator;
import io.mosip.registration.livenessmanager.auth.OperatorDirectory;
import io.mosip.registration.livenessmanager.match.FaceMatch;
import io.mosip.registration_client.liveness.CaptureOutcome;
import io.mosip.registration_client.liveness.FaceAuthFlow;
import io.mosip.registration_client.liveness.FaceCaptureLivenessGate;
import io.mosip.registration_client.liveness.LivenessCaptureLauncher;
import io.mosip.registration_client.liveness.LivenessMessages;
import io.mosip.registration_client.liveness.TestLivenessServices;
import io.mosip.registration_client.model.LivenessPigeon;

/**
 * Flutter bridge of face liveness: workflow IDs, the last capture outcome of a field, and the result and audit
 * events of a face authentication request.
 */
@RunWith(RobolectricTestRunner.class)
public class LivenessApiTest {

    private final TestLivenessServices.Fake liveness = TestLivenessServices.fake();
    private final List<AuditEvent> audits = new ArrayList<>();
    private final List<String> enrolled = new ArrayList<>();
    private FaceCaptureLivenessGate gate;
    private LivenessApi api;

    @Before
    public void setUp() {
        OperatorDirectory directory = new OperatorDirectory() {
            @Override
            public List<String> roles(String userId) {
                return Collections.singletonList(userId.startsWith("supervisor")
                        ? FaceAuthenticator.SUPERVISOR_ROLE : "REGISTRATION_OFFICER");
            }

            @Override
            public List<byte[]> enrolledFaces(String userId) {
                return enrolled.contains(userId) ? Collections.singletonList(new byte[] {1})
                        : Collections.emptyList();
            }

            @Override
            public boolean isLocked(String userId) {
                return false;
            }

            @Override
            public void recordFailure(String userId) {
            }

            @Override
            public void lock(String userId) {
            }
        };
        LivenessCaptureLauncher launcher = new LivenessCaptureLauncher(liveness.service());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        launcher.attach(activity);
        FaceAuthenticator authenticator = new FaceAuthenticator(liveness.service(), directory,
                (probe, gallery) -> FaceMatch.compared(75, 40));
        gate = new FaceCaptureLivenessGate(liveness.service());
        api = new LivenessApi(liveness.service(), gate, new FaceAuthFlow(authenticator, launcher), launcher,
                new KeyEchoMessages(), new RecordingAudit(), null);
        api.setCallbackActivity(activity);
    }

    @Test
    public void workflowIdsFollowTheConfigurationNames() {
        liveness.enforce(Workflow.PACKET_AUTH);
        assertTrue(api.isEnforced("packet_auth"));
        assertFalse(api.isEnforced("registration"));
        assertFalse("unknown IDs are never enforced", api.isEnforced("no_such_workflow"));
    }

    @Test
    public void faceAuthenticationIsOfferedOnlyForAuthenticationWorkflowsWithAnEnrolledFace() {
        liveness.enforce(Workflow.REGISTRATION);
        liveness.enforce(Workflow.LOGIN_AUTH);
        assertFalse("registration is a capture, not an authentication", api.isFaceAuthAvailable("officer1",
                "registration"));
        assertFalse("no face enrolled at onboarding", api.isFaceAuthAvailable("officer1", "login_auth"));
        enrolled.add("officer1");
        assertTrue(api.isFaceAuthAvailable("officer1", "login_auth"));
    }

    @Test
    public void lastCaptureOutcomeCarriesStatusMessageAndAttempts() {
        LivenessPigeon.LivenessOutcome none = api.getLastCaptureOutcome("individualBiometrics");
        assertEquals(CaptureOutcome.Status.NOT_REQUIRED.name(), none.getStatus());
        assertNull(none.getMessage());

        liveness.enforce(Workflow.REGISTRATION);
        int max = liveness.service().policy(Workflow.REGISTRATION).maxRetries();
        gate.record("rid-1", "individualBiometrics", TestLivenessServices.padFailure(max));

        LivenessPigeon.LivenessOutcome blocked = api.getLastCaptureOutcome("individualBiometrics");
        assertEquals(CaptureOutcome.Status.BLOCKED.name(), blocked.getStatus());
        assertEquals(MessageKeys.RETRY_MAX_RESIDENT + "[" + max + "]", blocked.getMessage());
        assertEquals(max, blocked.getMaxAttempts().intValue());
    }

    @Test
    public void nonAuthenticationWorkflowIsRefusedWithoutAudit() {
        List<LivenessPigeon.LivenessOutcome> results = new ArrayList<>();
        api.authenticateFace("officer1", "registration", result(results));
        assertEquals(LivenessApi.FAILED, results.get(0).getStatus());
        assertEquals("NOT_ENFORCED", results.get(0).getErrorCode());
        assertTrue(audits.isEmpty());
    }

    @Test
    public void refusedAuthenticationIsAuditedWithTheDesktopEvent() {
        List<LivenessPigeon.LivenessOutcome> results = new ArrayList<>();
        liveness.enforce(Workflow.EOD_AUTH);

        api.authenticateFace("officer1", "eod_auth", result(results));

        assertEquals(Collections.singletonList(AuditEvent.REG_SUPERVISOR_AUTH_FACE), audits);
        assertEquals(LivenessApi.FAILED, results.get(0).getStatus());
        assertEquals("NOT_SUPERVISOR", results.get(0).getErrorCode());
        assertEquals(MessageKeys.ERROR_NOT_SUPERVISOR + "[]", results.get(0).getMessage());
        assertTrue("no capture screen for a refused request", liveness.requests().isEmpty());
    }

    @Test
    public void signInAndPacketAuthenticationUseTheirOwnAuditEvents() {
        List<LivenessPigeon.LivenessOutcome> results = new ArrayList<>();
        api.authenticateFace("officer1", "login_auth", result(results));
        api.authenticateFace("officer1", "packet_auth", result(results));
        assertEquals(java.util.Arrays.asList(AuditEvent.LOGIN_WITH_FACE, AuditEvent.REG_OPERATOR_AUTH_FACE), audits);
        assertEquals("NOT_ENFORCED", results.get(0).getErrorCode());
    }

    private static LivenessPigeon.Result<LivenessPigeon.LivenessOutcome> result(
            List<LivenessPigeon.LivenessOutcome> sink) {
        return new LivenessPigeon.Result<LivenessPigeon.LivenessOutcome>() {
            @Override
            public void success(@NonNull LivenessPigeon.LivenessOutcome outcome) {
                sink.add(outcome);
            }

            @Override
            public void error(@NonNull Throwable error) {
                throw new AssertionError(error);
            }
        };
    }

    /** Returns "key[arg,...]" so the tests see which key and arguments were resolved. */
    private static final class KeyEchoMessages extends LivenessMessages {
        KeyEchoMessages() {
            super(null);
        }

        @Override
        public String text(Context context, String language, String key, List<String> args) {
            return key == null ? null : key + args.toString().replace(" ", "");
        }
    }

    /** Keeps the audit events; the rest of the audit API is not used by the bridge. */
    private final class RecordingAudit implements AuditManagerService {
        @Override
        public void audit(AuditEvent event, Components module) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, Components module, String errorMsg) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, String moduleId, String moduleName, String refId, String refIdType) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, String moduleId, String moduleName) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, String moduleId, String moduleName, String errorMsg) {
            audits.add(event);
        }

        @Override
        public void auditWithArguments(AuditEvent event, String moduleId, String moduleName, String... arguments) {
            audits.add(event);
        }

        @Override
        public boolean deleteAuditLogs() {
            return false;
        }

        @Override
        public List<Audit> getAuditLogs(long fromDateTime) {
            return Collections.emptyList();
        }
    }
}
