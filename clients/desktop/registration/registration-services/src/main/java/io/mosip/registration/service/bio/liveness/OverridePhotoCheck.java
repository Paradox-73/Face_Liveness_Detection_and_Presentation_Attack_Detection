/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.PassivePad;
import io.mosip.liveness.onnx.OnnxLivenessEngine;

/**
 * Checks the photo taken after a supervisor override with the liveness engine's own models: the photo must show
 * exactly one face, and the passive PAD score of that face must not be below {@code pad.reject_threshold}. These
 * are the image checks the engine runs on every capture that follows a live session; an override has no session,
 * so they run here.
 *
 * <p>An engine whose models cannot be reached (a vendor engine that exposes no single-image check) fails closed:
 * the override photo is refused.
 */
final class OverridePhotoCheck {

	/** Finds the face analyser and passive PAD of an engine. */
	@FunctionalInterface
	interface ModelLookup {

		/**
		 * Returns the models of an engine.
		 *
		 * @param engine the liveness engine in use
		 * @return the models; empty when the engine offers no single-image check
		 */
		Optional<Models> modelsOf(LivenessEngine engine);
	}

	/** The default lookup: the ONNX engine's face analyser and passive PAD ensemble. */
	static final ModelLookup ENGINE_MODELS = engine -> {
		if (!(engine instanceof OnnxLivenessEngine)) {
			return Optional.empty();
		}
		try {
			OnnxLivenessEngine onnx = (OnnxLivenessEngine) engine;
			return Optional.of(new Models(onnx.modelSet().faceAnalyzer(), onnx.modelSet().passivePad()));
		} catch (IllegalStateException e) {
			return Optional.empty();
		}
	};

	/** A face analyser and a passive PAD model. */
	static final class Models {
		final FaceAnalyzer analyzer;
		final PassivePad pad;

		Models(FaceAnalyzer analyzer, PassivePad pad) {
			this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
			this.pad = Objects.requireNonNull(pad, "pad");
		}
	}

	/** What the check found. */
	static final class Result {
		/** Number of faces found; {@code -1} when the image could not be checked. */
		final int faces;
		/** Passive PAD score 0-100 of the single face; {@code -1} when not scored. */
		final int padScore;
		/** The verdict for the gate. */
		final BindingVerdict verdict;

		Result(int faces, int padScore, BindingVerdict verdict) {
			this.faces = faces;
			this.padScore = padScore;
			this.verdict = verdict;
		}

		boolean isPassed() {
			return verdict.isPassed();
		}

		/**
		 * Returns the reason written in the audit event.
		 *
		 * @return {@code OVERRIDE_GRANTED} or the first reason code
		 */
		String auditReason() {
			if (verdict.isPassed()) {
				return "OVERRIDE_GRANTED";
			}
			return verdict.reasonCodes().isEmpty() ? FailureReason.BINDING.name()
					: verdict.reasonCodes().iterator().next().name();
		}
	}

	private OverridePhotoCheck() {
	}

	/**
	 * Runs the face count and the passive PAD check on a photo.
	 *
	 * @param models             the engine's models; {@code null} when the engine offers none
	 * @param image              the decoded photo
	 * @param padRejectThreshold {@code pad.reject_threshold} of the workflow's policy
	 * @return the result; never passes when a check could not run
	 */
	static Result check(Models models, ImageBuffer image, int padRejectThreshold) {
		if (models == null || image == null) {
			return new Result(-1, -1, BindingVerdict.failed(FailureReason.BINDING, ReasonCode.ENGINE_ERROR).build());
		}
		try {
			FaceObservation observation = models.analyzer.analyze(image);
			int faces = observation.faceCount();
			if (faces == 0) {
				return new Result(0, -1,
						BindingVerdict.failed(FailureReason.BINDING, ReasonCode.CAPTURE_NO_FACE).build());
			}
			if (faces > 1) {
				return new Result(faces, -1,
						BindingVerdict.failed(FailureReason.BINDING, ReasonCode.CAPTURE_MULTI_FACE).build());
			}
			int score = models.pad.score(image, observation.faces().get(0).box()).score100();
			BindingVerdict.Builder verdict = score < padRejectThreshold
					? BindingVerdict.failed(FailureReason.PAD, ReasonCode.CAPTURE_PAD_SCORE)
					: BindingVerdict.passed();
			return new Result(1, score, verdict.capturePadScore(score).build());
		} catch (EngineException | RuntimeException e) {
			return new Result(-1, -1, BindingVerdict.failed(FailureReason.BINDING, ReasonCode.ENGINE_ERROR).build());
		}
	}
}
