/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A device's own PAD result, parsed from a signed capture that carries a PAD block (MOSIP issue {@code #1117}).
 */
public final class DevicePadResult {

    private final DevicePadDecision decision;
    private final PadMethod method;
    private final Integer score;

    private DevicePadResult(DevicePadDecision decision, PadMethod method, Integer score) {
        this.decision = decision;
        this.method = method;
        this.score = score;
    }

    /**
     * Creates a result.
     *
     * @param decision the device decision; not {@code null}
     * @param method   method the device applied, or {@code null} if not reported
     * @param score    device score 0-100, or {@code null} if not reported
     * @return the result
     * @throws IllegalArgumentException if the score is outside 0-100
     */
    public static DevicePadResult of(DevicePadDecision decision, PadMethod method, Integer score) {
        Objects.requireNonNull(decision, "decision");
        if (score != null && (score < 0 || score > 100)) {
            throw new IllegalArgumentException("score must be 0-100");
        }
        return new DevicePadResult(decision, method, score);
    }

    /**
     * Returns the device decision.
     *
     * @return the decision
     */
    public DevicePadDecision decision() {
        return decision;
    }

    /**
     * Tells whether the device reported an attack.
     *
     * @return {@code true} for {@link DevicePadDecision#FAIL}
     */
    public boolean isFail() {
        return decision == DevicePadDecision.FAIL;
    }

    /**
     * Returns the method the device applied.
     *
     * @return the method, if reported
     */
    public Optional<PadMethod> method() {
        return Optional.ofNullable(method);
    }

    /**
     * Returns the device score.
     *
     * @return score 0-100, if reported
     */
    public OptionalInt score() {
        return score == null ? OptionalInt.empty() : OptionalInt.of(score);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DevicePadResult)) {
            return false;
        }
        DevicePadResult other = (DevicePadResult) o;
        return decision == other.decision && method == other.method && Objects.equals(score, other.score);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decision, method, score);
    }

    @Override
    public String toString() {
        return "DevicePadResult[" + decision + ", method=" + method + ", score=" + score + "]";
    }
}
