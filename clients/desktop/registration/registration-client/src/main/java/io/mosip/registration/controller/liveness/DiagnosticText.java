package io.mosip.registration.controller.liveness;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;

/**
 * Builds the diagnostic lines of the liveness window, shown only when
 * {@code mosip.registration.liveness.diagnostic.enable=Y} (G5): workflow, policy, engine and model versions, the
 * session state with its PAD score, integrity flags, planarity residual, binding similarity and frame rate, and the
 * attempt count and result code. These are the technical values of the diagnostic log line; they never include an
 * image, a template, a user ID or a registration ID. The labels are fixed English technical names, for support
 * staff, not translated user texts.
 */
final class DiagnosticText {

	private DiagnosticText() {
	}

	/** What the diagnostic lines are made of; every part except the workflow may be missing. */
	static final class Input {
		String workflow = "";
		LivenessPolicy policy;
		String engineStatus = "";
		Optional<EngineInfo> engine = Optional.empty();
		UiPhase phase;
		LivenessOutcome outcome;
		Optional<DiagnosticRecord> record = Optional.empty();
	}

	/**
	 * Formats the lines.
	 *
	 * @param in the values
	 * @return three or four lines separated by {@code \n}
	 */
	static String format(Input in) {
		StringBuilder text = new StringBuilder("DIAGNOSTIC  workflow=").append(in.workflow);
		if (in.policy != null) {
			text.append("  policy=").append(in.policy.mode()).append("  maxAttempts=").append(in.policy.maxRetries());
		}
		text.append("  engine=").append(in.engineStatus);
		in.engine.ifPresent(e -> text.append(' ').append(e.tag()));
		text.append('\n').append(sessionLine(in));
		if (in.outcome != null) {
			text.append('\n').append("attempts=").append(in.outcome.attemptsUsed()).append('/')
					.append(in.outcome.maxAttempts()).append("  result=")
					.append(in.outcome.failureReason().map(Enum::name).orElse("PASS"));
			in.outcome.errorCode().ifPresent(code -> text.append("  code=").append(code));
		}
		String models = models(in);
		if (!models.isEmpty()) {
			text.append('\n').append("models=").append(models);
		}
		return text.toString();
	}

	private static String sessionLine(Input in) {
		StringBuilder line = new StringBuilder("phase=").append(in.phase == null ? "-" : in.phase.name());
		if (!in.record.isPresent()) {
			return line.append("  state=-").toString();
		}
		DiagnosticRecord r = in.record.get();
		line.append("  state=").append(r.state()).append("  t=").append(r.elapsedMs()).append("ms");
		if (r.padScore().isPresent()) {
			line.append("  pad=").append(r.padScore().getAsInt());
		}
		if (r.fps().isPresent()) {
			line.append("  fps=").append(r.fps().getAsDouble());
		}
		if (r.planarityResidual().isPresent()) {
			line.append("  planarity=").append(String.format(Locale.ROOT, "%.4f", r.planarityResidual().getAsDouble()));
		}
		if (r.bindingSimilarity().isPresent()) {
			line.append("  binding=").append(r.bindingSimilarity().getAsInt());
		}
		if (!r.flags().isEmpty()) {
			line.append("  flags=");
			String sep = "";
			for (ReasonCode flag : r.flags()) {
				line.append(sep).append(flag.name());
				sep = ",";
			}
		}
		if (!r.detail().isEmpty()) {
			line.append("  detail=").append(r.detail());
		}
		return line.toString();
	}

	private static String models(Input in) {
		StringBuilder models = new StringBuilder();
		String sep = "";
		if (in.engine.isPresent() && !in.engine.get().models().isEmpty()) {
			for (ModelInfo m : in.engine.get().models()) {
				models.append(sep).append(m.id()).append('@').append(m.version()).append('#').append(m.sha256Prefix());
				sep = ", ";
			}
			return models.toString();
		}
		if (in.record.isPresent()) {
			for (Map.Entry<String, String> m : in.record.get().modelVersions().entrySet()) {
				models.append(sep).append(m.getKey()).append('@').append(m.getValue());
				sep = ", ";
			}
		}
		return models.toString();
	}
}
