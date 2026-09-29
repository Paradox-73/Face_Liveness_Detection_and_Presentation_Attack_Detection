package io.mosip.registration.service.bio.liveness;

import java.util.Objects;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.registration.constants.RegistrationConstants;

/**
 * What a screen asks {@link LivenessOrchestrator#start} to verify: the workflow and the person.
 *
 * <p>The subject reference binds the pass token: the registration ID for resident workflows, the user ID for
 * operator and supervisor authentication. It is never written to logs or audits by the liveness services.
 */
public final class LivenessRequest {

	private final Workflow workflow;
	private final String subjectRef;
	private final String modality;
	private final FaceCaptureDevice device;

	private LivenessRequest(Builder b) {
		this.workflow = b.workflow;
		this.subjectRef = b.subjectRef;
		this.modality = b.modality;
		this.device = b.device;
	}

	/**
	 * Starts a builder.
	 *
	 * @param workflow   the workflow
	 * @param subjectRef registration ID or user ID; not empty
	 * @return a builder for the discovered SBI face device
	 */
	public static Builder builder(Workflow workflow, String subjectRef) {
		return new Builder(workflow, subjectRef);
	}

	/**
	 * Returns the workflow.
	 *
	 * @return the workflow
	 */
	public Workflow workflow() {
		return workflow;
	}

	/**
	 * Returns the subject reference.
	 *
	 * @return registration ID or user ID
	 */
	public String subjectRef() {
		return subjectRef;
	}

	/**
	 * Returns the capture modality used to find the SBI device.
	 *
	 * @return the modality, {@link RegistrationConstants#FACE_FULLFACE} by default
	 */
	public String modality() {
		return modality;
	}

	/**
	 * Returns an explicitly chosen device.
	 *
	 * @return the device, or {@code null} to use the discovered SBI face device
	 */
	public FaceCaptureDevice device() {
		return device;
	}

	@Override
	public String toString() {
		return "LivenessRequest[" + workflow.id() + ", subject=***, modality=" + modality + "]";
	}

	/** Builder for {@link LivenessRequest}. */
	public static final class Builder {

		private final Workflow workflow;
		private final String subjectRef;
		private String modality = RegistrationConstants.FACE_FULLFACE;
		private FaceCaptureDevice device;

		private Builder(Workflow workflow, String subjectRef) {
			this.workflow = Objects.requireNonNull(workflow, "workflow");
			this.subjectRef = Objects.requireNonNull(subjectRef, "subjectRef");
			if (subjectRef.trim().isEmpty()) {
				throw new IllegalArgumentException("subjectRef must not be empty");
			}
		}

		/**
		 * Sets the capture modality used to find the SBI device.
		 *
		 * @param modality modality name
		 * @return this builder
		 */
		public Builder modality(String modality) {
			this.modality = Objects.requireNonNull(modality, "modality");
			return this;
		}

		/**
		 * Uses a specific device instead of the discovered SBI face device (replay sources and tests).
		 *
		 * @param device the device
		 * @return this builder
		 */
		public Builder device(FaceCaptureDevice device) {
			this.device = device;
			return this;
		}

		/**
		 * Creates the request.
		 *
		 * @return the request
		 */
		public LivenessRequest build() {
			return new LivenessRequest(this);
		}
	}
}
