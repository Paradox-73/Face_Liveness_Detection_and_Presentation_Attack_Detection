/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.mdm.dto;

import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessPassToken;

/**
 * Liveness information attached to a face capture request ({@link MDMRequestDto#getLivenessContext()}).
 *
 * <p>The liveness gate in {@code BioServiceImpl.captureModality} reads it to decide whether the capture needs a
 * liveness pass: it names the workflow, the subject the caller is capturing (registration ID or user ID) and the
 * single-use pass token issued by the liveness session. A context without a token is only accepted for a
 * workflow whose liveness policy is {@code OFF} (for example the exception photo by default).
 */
public final class LivenessContext {

	private final Workflow workflow;
	private final String subjectRef;
	private final LivenessPassToken token;

	private LivenessContext(Workflow workflow, String subjectRef, LivenessPassToken token) {
		this.workflow = Objects.requireNonNull(workflow, "workflow");
		this.subjectRef = subjectRef;
		this.token = token;
	}

	/**
	 * Creates a context carrying the pass token of a liveness session.
	 *
	 * @param token      the pass token; its workflow becomes the context's workflow
	 * @param subjectRef the subject the caller is capturing, checked against the token; {@code null} skips the
	 *                   check
	 * @return the context
	 */
	public static LivenessContext withPass(LivenessPassToken token, String subjectRef) {
		Objects.requireNonNull(token, "token");
		return new LivenessContext(token.workflow(), subjectRef, token);
	}

	/**
	 * Creates a context without a pass, for a workflow whose policy may be {@code OFF}.
	 *
	 * @param workflow   the workflow of the capture
	 * @param subjectRef the subject, or {@code null}
	 * @return the context
	 */
	public static LivenessContext withoutPass(Workflow workflow, String subjectRef) {
		return new LivenessContext(workflow, subjectRef, null);
	}

	/**
	 * Returns the workflow of the capture.
	 *
	 * @return the workflow
	 */
	public Workflow getWorkflow() {
		return workflow;
	}

	/**
	 * Returns the subject the caller is capturing.
	 *
	 * @return registration ID or user ID, if given
	 */
	public Optional<String> getSubjectRef() {
		return Optional.ofNullable(subjectRef);
	}

	/**
	 * Returns the pass token.
	 *
	 * @return the token, if the liveness session issued one
	 */
	public Optional<LivenessPassToken> getToken() {
		return Optional.ofNullable(token);
	}

	@Override
	public String toString() {
		return "LivenessContext[" + workflow.id() + ", subject=" + (subjectRef == null ? "none" : "***") + ", token="
				+ (token == null ? "none" : token.sessionId()) + "]";
	}
}
