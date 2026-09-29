package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.Test;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;
import javafx.scene.image.Image;

/** Forwarding of liveness callbacks to the UI thread. */
public class FxLivenessListenerTest {

	/** Queues work like Platform.runLater and runs it when asked. */
	private static final class QueueExecutor implements Executor {
		final List<Runnable> queued = new ArrayList<>();

		@Override
		public void execute(Runnable command) {
			queued.add(command);
		}

		void runAll() {
			List<Runnable> copy = new ArrayList<>(queued);
			queued.clear();
			copy.forEach(Runnable::run);
		}
	}

	private static final class Recorder implements FxLivenessListener.Target {
		final List<String> calls = new ArrayList<>();

		@Override
		public void uiState(LivenessUiState state) {
			calls.add("state:" + state.messageKey());
		}

		@Override
		public void previewFrame(Image image) {
			calls.add("frame");
		}

		@Override
		public void captureReady(LivenessContext context) {
			calls.add("capture:" + context.getWorkflow().id());
		}

		@Override
		public void sessionEnded(LivenessOutcome outcome) {
			calls.add("ended");
		}
	}

	@Test
	public void callbacksRunOnTheUiExecutorInOrder() {
		QueueExecutor ui = new QueueExecutor();
		Recorder target = new Recorder();
		FxLivenessListener listener = new FxLivenessListener(target, ui);

		listener.onUiState(LivenessUiState.builder(UiPhase.CHECKING, MessageKeys.STATUS_CHECKING).build());
		listener.onCaptureReady(LivenessContext.withoutPass(Workflow.REGISTRATION, "rid"));
		listener.onSessionEnded(null);

		assertTrue("nothing runs on the liveness thread", target.calls.isEmpty());
		ui.runAll();
		assertEquals(3, target.calls.size());
		assertEquals("state:" + MessageKeys.STATUS_CHECKING, target.calls.get(0));
		assertEquals("capture:registration", target.calls.get(1));
		assertEquals("ended", target.calls.get(2));
	}

	@Test
	public void detachDropsQueuedAndLaterCallbacks() {
		QueueExecutor ui = new QueueExecutor();
		Recorder target = new Recorder();
		FxLivenessListener listener = new FxLivenessListener(target, ui);

		listener.onUiState(LivenessUiState.builder(UiPhase.CHECKING, MessageKeys.STATUS_CHECKING).build());
		listener.detach();
		listener.onCaptureReady(LivenessContext.withoutPass(Workflow.LOGIN_AUTH, "officer1"));
		ui.runAll();

		assertTrue(target.calls.isEmpty());
		assertTrue(ui.queued.isEmpty());
	}

	@Test
	public void previewIgnoresRawAndMissingFrames() {
		QueueExecutor ui = new QueueExecutor();
		Recorder target = new Recorder();
		FxLivenessListener listener = new FxLivenessListener(target, ui);

		listener.onPreviewFrame(null);

		assertTrue(ui.queued.isEmpty());
	}
}
