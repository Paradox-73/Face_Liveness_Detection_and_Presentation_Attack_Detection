package io.mosip.registration_client.liveness;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;

/**
 * {@link LivenessService} for unit tests: shipped default policies, a set of enforced workflows, recorded requests
 * and a result to return. (The app's test class path carries mockito-android, whose mock maker does not run on the
 * JVM, so these tests use fakes.)
 */
final class FakeLivenessService implements LivenessService {

    final Set<Workflow> enforced = EnumSet.noneOf(Workflow.class);
    final List<LivenessCaptureRequest> requests = new ArrayList<>();
    /** Synced configuration values; empty means the shipped defaults. */
    final Map<String, String> config = new HashMap<>();
    LivenessCaptureResult nextResult;

    @Override
    public LivenessPolicy policy(Workflow workflow) {
        return new PolicyResolver(ConfigSource.of(config)).resolve(workflow).policy();
    }

    @Override
    public boolean isEnforced(Workflow workflow) {
        return enforced.contains(workflow);
    }

    @Override
    public Intent newCaptureIntent(Context context, LivenessCaptureRequest request) {
        requests.add(request);
        return request.writeTo(new Intent("liveness.capture"));
    }

    @Override
    public LivenessCaptureResult parseResult(int resultCode, Intent data) {
        return nextResult != null ? nextResult
                : LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED).build();
    }

    @Override
    public void preload() {
    }

    @Override
    public boolean isBuiltInDeviceProvisioned() {
        return true;
    }
}
