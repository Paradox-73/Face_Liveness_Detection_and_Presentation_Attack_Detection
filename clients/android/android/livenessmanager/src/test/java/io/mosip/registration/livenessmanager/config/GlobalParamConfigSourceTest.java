/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;

public class GlobalParamConfigSourceTest {

    private final Map<String, String> synced = new HashMap<>();
    private final Map<String, String> merged = new HashMap<>();
    private final GlobalParamConfigSource source = new GlobalParamConfigSource(new GlobalParamConfigSource.Lookup() {
        @Override
        public String merged(String key) {
            return merged.get(key);
        }

        @Override
        public String synced(String key) {
            return synced.get(key);
        }
    });

    @Test
    public void overridableKeyReadsTheLocalSetting() {
        synced.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "80");
        merged.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "85");
        assertEquals(Optional.of("85"), source.get(LivenessConfigKeys.PASSIVE_THRESHOLD));
    }

    @Test
    public void localSettingCannotSwitchLivenessOff() {
        synced.put(LivenessConfigKeys.ENABLE, "Y");
        merged.put(LivenessConfigKeys.ENABLE, "N");
        String policyKey = LivenessConfigKeys.policyKey(Workflow.REGISTRATION);
        synced.put(policyKey, "PASSIVE_THEN_ACTIVE");
        merged.put(policyKey, "OFF");
        assertEquals(Optional.of("Y"), source.get(LivenessConfigKeys.ENABLE));
        LivenessPolicy policy = new PolicyResolver(source).resolve(Workflow.REGISTRATION).policy();
        assertEquals(PolicyMode.PASSIVE_THEN_ACTIVE, policy.mode());
    }

    @Test
    public void syncedMasterSwitchOffTurnsPolicyOff() {
        synced.put(LivenessConfigKeys.ENABLE, "N");
        assertFalse(new PolicyResolver(source).resolve(Workflow.PACKET_AUTH).policy().isEnabled());
    }

    @Test
    public void blankAndFailingValuesAreAbsent() {
        synced.put(LivenessConfigKeys.ENGINE, "   ");
        assertEquals(Optional.empty(), source.get(LivenessConfigKeys.ENGINE));
        assertEquals(Optional.empty(), source.get(null));
        GlobalParamConfigSource failing = new GlobalParamConfigSource(new GlobalParamConfigSource.Lookup() {
            @Override
            public String merged(String key) {
                throw new IllegalStateException("database closed");
            }

            @Override
            public String synced(String key) {
                throw new IllegalStateException("database closed");
            }
        });
        assertEquals(Optional.empty(), failing.get(LivenessConfigKeys.ENABLE));
        assertTrue(new PolicyResolver(failing).resolve(Workflow.REGISTRATION).policy().isEnabled());
    }

    @Test
    public void valuesAreTrimmed() {
        merged.put(LivenessConfigKeys.MAX_RETRIES, " 4 ");
        assertEquals(Optional.of("4"), source.get(LivenessConfigKeys.MAX_RETRIES));
    }

    @Test
    public void reportsChangedKeysAfterTheBaseline() {
        assertEquals(Collections.emptyList(), source.changedKeysSinceLastCheck());
        merged.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "90");
        synced.put(LivenessConfigKeys.ENABLE, "N");
        assertEquals(java.util.Arrays.asList(LivenessConfigKeys.ENABLE, LivenessConfigKeys.PASSIVE_THRESHOLD),
                source.changedKeysSinceLastCheck());
        assertEquals(Collections.emptyList(), source.changedKeysSinceLastCheck());
        synced.remove(LivenessConfigKeys.ENABLE);
        assertEquals(Collections.singletonList(LivenessConfigKeys.ENABLE), source.changedKeysSinceLastCheck());
    }

    @Test
    public void readsArcRepository() {
        GlobalParamRepository repository = mock(GlobalParamRepository.class);
        when(repository.getGlobalParamValue(LivenessConfigKeys.ENABLE)).thenReturn("N");
        when(repository.getCachedStringGlobalParam(LivenessConfigKeys.ENABLE)).thenReturn("Y");
        when(repository.getCachedStringGlobalParam(LivenessConfigKeys.PASSIVE_THRESHOLD)).thenReturn("70");
        GlobalParamConfigSource arc = new GlobalParamConfigSource(repository);
        assertEquals(Optional.of("N"), arc.get(LivenessConfigKeys.ENABLE));
        assertEquals(Optional.of("70"), arc.get(LivenessConfigKeys.PASSIVE_THRESHOLD));
    }
}
