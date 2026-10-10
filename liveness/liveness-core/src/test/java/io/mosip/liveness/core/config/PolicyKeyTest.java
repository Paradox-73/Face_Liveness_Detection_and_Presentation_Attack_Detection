/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.policy.PolicyResolver;

class PolicyKeyTest {

    /** The resolver clamps and the builder rejects with the same range (was 1-60 against at least 1). */
    @Test
    void resolverAndBuilderUseTheSameRange() {
        LivenessPolicy resolved = new PolicyResolver(ConfigSource.of(Collections.singletonMap(
                PolicyKey.PASSIVE_MIN_FRAMES.key(), "61"))).resolve(Workflow.REGISTRATION).policy();
        assertEquals(60, resolved.passiveMinFrames());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessPolicy.builder(Workflow.REGISTRATION).passiveMinFrames(61).build());
        assertEquals(60, LivenessPolicy.builder(Workflow.REGISTRATION).passiveMinFrames(60).build().passiveMinFrames());
    }

    @Test
    void everyNumberDefaultIsInsideItsRange() {
        for (PolicyKey k : PolicyKey.values()) {
            if (k.type() == PolicyKey.Type.NUMBER) {
                assertEquals(k.defaultLong(), k.clamp(k.defaultLong()), k.key());
            }
        }
    }

    @Test
    void yesNoAcceptsTheResolverSpellings() {
        assertTrue(PolicyKey.ANDROID_BLOCK_EMULATOR.isOn(ConfigSource.of(Collections.singletonMap(
                PolicyKey.ANDROID_BLOCK_EMULATOR.key(), "yes"))));
        assertFalse(PolicyKey.ENABLE.isOn(ConfigSource.of(Collections.singletonMap(PolicyKey.ENABLE.key(), "false"))));
        assertTrue(PolicyKey.ENABLE.isOn(ConfigSource.of(Collections.singletonMap(PolicyKey.ENABLE.key(), "maybe"))),
                "an unreadable value keeps the default");
    }
}
