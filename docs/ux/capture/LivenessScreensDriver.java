import java.io.File;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import io.mosip.liveness.core.Workflow;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.controller.ClientApplication;
import io.mosip.registration.controller.liveness.LivenessDialog;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.service.bio.BioService;
import io.mosip.registration.service.bio.liveness.LivenessOrchestrator;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;

/**
 * Drives the face liveness window of the real desktop Registration Client through its states and saves a PNG of
 * each state, for the UX documentation (docs/ux/desktop-liveness.md).
 *
 * <p>It starts the client in this JVM, logs in with the given user, then opens {@code LivenessDialog} with the real
 * {@code LivenessOrchestrator} (engine, SBI stream, audits) for several scenarios. On capture ready it calls the
 * real {@code BioService} capture, so the liveness gate binds the signed capture exactly as the screens do. The
 * face device is a MockMDS whose frame source and faults are switched over its admin API between scenarios. No
 * operating-system input events are used; buttons are fired on the JavaFX thread.
 *
 * <p>Usage (working directory = client run layout, JDK 11 with JavaFX):
 * <pre>
 * java -cp "lib/*" LivenessScreensDriver.java &lt;user&gt; &lt;password&gt; &lt;outDir&gt; &lt;framePackRoot&gt; [scenario...]
 * </pre>
 * Scenarios: static-pad, no-face, device-unavailable, eod-arabic, login-french-offline, live (uses frame packs of
 * real people: for local verification only, never commit its images).
 */
public class LivenessScreensDriver {

	private static final String MDS = "http://127.0.0.1:4501";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private static PrintStream log;
	private static Path out;
	private static Path packs;

	public static void main(String[] args) throws Exception {
		String user = args[0];
		String password = args[1];
		out = Path.of(args[2]);
		packs = Path.of(args[3]);
		List<String> scenarios = args.length > 4 ? Arrays.asList(args).subList(4, args.length)
				: Arrays.asList("static-pad", "no-face", "device-unavailable", "eod-arabic", "login-french-offline");
		Files.createDirectories(out);
		log = new PrintStream(Files.newOutputStream(out.resolve("driver.log")), true, "UTF-8");
		startClient();
		login(user, password);
		for (String scenario : scenarios) {
			try {
				run(scenario);
			} catch (Exception e) {
				log.println("SCENARIO " + scenario + " ERROR " + e);
				e.printStackTrace(log);
			}
		}
		mds("/admin/fault", "{\"type\":\"Face\",\"fault\":\"NONE\"}");
		log.println("DONE");
		System.exit(0);
	}

	private static void run(String scenario) throws Exception {
		log.println("SCENARIO " + scenario);
		mds("/admin/fault", "{\"type\":\"Face\",\"fault\":\"NONE\"}");
		switch (scenario) {
		case "static-pad":
			source("m-stream-static");
			drive(scenario, Workflow.REGISTRATION, "eng", false, true, 2, 60);
			break;
		case "no-face":
			source("q-blank");
			drive(scenario, Workflow.REGISTRATION, "eng", false, true, 0, 25);
			break;
		case "device-unavailable":
			source("m-stream-static");
			mds("/admin/fault", "{\"type\":\"Face\",\"fault\":\"UNAVAILABLE\"}");
			drive(scenario, Workflow.REGISTRATION, "eng", false, true, 0, 20);
			break;
		case "device-disconnected":
			source("m-stream-static");
			mds("/admin/fault", "{\"type\":\"Face\",\"fault\":\"DISCONNECT\",\"afterFrames\":12}");
			drive(scenario, Workflow.REGISTRATION, "eng", false, true, 0, 25);
			break;
		case "eod-arabic":
			source("m-stream-static");
			drive(scenario, Workflow.EOD_AUTH, "ara", true, true, 0, 30);
			break;
		case "login-french-offline":
			source("m-stream-static");
			drive(scenario, Workflow.LOGIN_AUTH, "fra", false, false, 0, 30);
			break;
		case "audit-probe":
			try {
				ClientApplication.getApplicationContext()
						.getBean(io.mosip.registration.audit.AuditManagerService.class)
						.audit(io.mosip.registration.constants.AuditEvent.LIVENESS_SESSION_STARTED,
								io.mosip.registration.constants.Components.REG_LIVENESS, "probe", "APPLICATION_ID",
								"{\"probe\":true}");
				log.println("AUDIT_PROBE ok");
			} catch (RuntimeException e) {
				log.println("AUDIT_PROBE failed " + e);
				e.printStackTrace(log);
			}
			break;
		case "live-eod":
			source("s3-main");
			drive(scenario, Workflow.EOD_AUTH, "eng", false, true, 2, 90);
			break;
		case "live":
			source("s3-main");
			drive(scenario, Workflow.REGISTRATION, "eng", false, true, 2, 90);
			break;
		default:
			log.println("unknown scenario " + scenario);
		}
	}

	/**
	 * Opens the liveness window and records every state until it closes or the time is up.
	 *
	 * @param retries how many times to press Retry when it is offered
	 */
	private static void drive(String scenario, Workflow workflow, String lang, boolean rtl, boolean online,
			int retries, int seconds) throws Exception {
		LivenessOrchestrator orchestrator = ClientApplication.getApplicationContext()
				.getBean(LivenessOrchestrator.class);
		BioService bioService = ClientApplication.getApplicationContext().getBean(BioService.class);
		String subject = workflow.isAuth() ? "officer1" : "10001100010000120260930010000";
		AtomicReference<LivenessDialog> dialog = new AtomicReference<>();
		onFx(() -> {
			LivenessDialog.Options options = new LivenessDialog.Options(workflow, subject)
					.owner(ClientApplication.getPrimaryStage()).language(lang, rtl).onlineCheck(() -> online);
			dialog.set(LivenessDialog.open(orchestrator, options, new LivenessDialog.Callbacks() {
				@Override
				public void captureReady(LivenessContext context) {
					log.println("CAPTURE_READY " + context.getWorkflow().id());
					Thread t = new Thread(() -> capture(bioService, workflow, context), "driver-capture");
					t.start();
				}

				@Override
				public void passed() {
					log.println("PASSED");
				}

				@Override
				public void closedWithoutPass(Optional<LivenessOutcome> lastOutcome) {
					log.println("CLOSED_WITHOUT_PASS " + lastOutcome.map(Object::toString).orElse("-"));
				}
			}));
			return null;
		});
		String last = "";
		int shot = 0;
		int retriesLeft = retries;
		long end = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < end) {
			Thread.sleep(120);
			String state = onFx(() -> dialog.get().isOpen() ? describe(dialog.get()) : null);
			if (state == null) {
				log.println("CLOSED");
				break;
			}
			if (!state.equals(last)) {
				last = state;
				String name = String.format("%s-%02d", scenario, ++shot);
				log.println("STATE " + name + " " + state);
				onFx(() -> snapshot(dialog.get(), name));
				if (scenario.startsWith("live")) {
					followChallenge(state);
				}
			}
			if (state.contains("retry=true") && retriesLeft > 0) {
				Thread.sleep(1500);
				retriesLeft--;
				if (scenario.equals("device-unavailable")) {
					mds("/admin/fault", "{\"type\":\"Face\",\"fault\":\"NONE\"}");
				}
				log.println("ACTION retry");
				onFx(() -> fire(dialog.get(), "#retryButton"));
			} else if (state.contains("retry=false") && state.contains("terminal") && !state.contains("[SUCCESS")) {
				Thread.sleep(2500);
				onFx(() -> snapshot(dialog.get(), scenario + "-final"));
				break;
			}
		}
		onFx(() -> {
			if (dialog.get().isOpen()) {
				dialog.get().cancel();
			}
			return null;
		});
		Thread.sleep(2000);
	}

	private static void followChallenge(String state) {
		try {
			if (state.contains("Please blink")) {
				source("s3-blink");
			} else if (state.contains("Please smile")) {
				source("s3-smile");
			} else if (state.contains("Hold still") || state.contains("Checking")) {
				source("s3-main");
			}
		} catch (Exception e) {
			log.println("source switch failed " + e);
		}
	}

	private static void capture(BioService bioService, Workflow workflow, LivenessContext context) {
		try {
			MDMRequestDto request = new MDMRequestDto(RegistrationConstants.FACE_FULLFACE, null, "Registration",
					io.mosip.registration.context.ApplicationContext
							.getStringValueFromApplicationMap(RegistrationConstants.SERVER_ACTIVE_PROFILE),
					10000, 1, 40);
			request.setLivenessContext(context);
			List<BiometricsDto> result = workflow.isAuth() ? bioService.captureModalityForAuth(request)
					: bioService.captureModality(request);
			log.println("CAPTURE_OK " + result.size() + " evidence="
					+ (result.isEmpty() ? "-" : result.get(0).getLivenessEvidence()));
		} catch (Exception e) {
			log.println("CAPTURE_FAILED " + e);
		}
	}

	private static String describe(LivenessDialog dialog) {
		Scene scene = dialog.panel().getScene();
		List<String> texts = new ArrayList<>();
		for (String id : Arrays.asList("#titleLabel", "#offlineBadge", "#promptLabel", "#stepLabel", "#attemptLabel",
				"#retryButton", "#overrideButton", "#cancelButton")) {
			Node node = scene.lookup(id);
			if (node != null && node.isVisible() && node instanceof javafx.scene.control.Labeled) {
				texts.add(((javafx.scene.control.Labeled) node).getText());
			}
		}
		Node guidance = scene.lookup("#guidanceBox");
		if (guidance instanceof javafx.scene.Parent) {
			for (Node n : ((javafx.scene.Parent) guidance).getChildrenUnmodifiable()) {
				texts.add(((javafx.scene.control.Labeled) n).getText());
			}
		}
		io.mosip.registration.controller.liveness.LivenessViewModel model = dialog.panel().currentModel();
		boolean retry = model != null && model.isRetryVisible();
		boolean terminal = model != null && model.isTerminal();
		String phase = model == null ? "-" : model.phase().name();
		return String.join(" | ", texts) + " [" + phase + " retry=" + retry + (terminal ? " terminal" : "") + "]";
	}

	private static Void fire(LivenessDialog dialog, String id) {
		Node node = dialog.panel().getScene().lookup(id);
		if (node instanceof ButtonBase && node.isVisible()) {
			((ButtonBase) node).fire();
		}
		return null;
	}

	private static void source(String pack) throws Exception {
		String path = packs.resolve(pack).toString().replace('\\', '/');
		mds("/admin/source", "{\"type\":\"Face\",\"source\":\"SEQUENCE\",\"path\":\"" + path
				+ "\",\"fps\":15,\"loop\":true}");
	}

	private static void mds(String path, String body) throws Exception {
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(MDS + path))
				.timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		String answer = r.body();
		log.println("MDS " + path + " " + body + " -> " + answer.substring(0, Math.min(80, answer.length())));
	}

	private static void startClient() {
		Thread client = new Thread(() -> {
			try {
				Class.forName("io.mosip.registration.controller.Initialization").getMethod("main", String[].class)
						.invoke(null, (Object) new String[0]);
			} catch (Throwable t) {
				t.printStackTrace(log);
			}
		}, "registration-client");
		client.start();
	}

	private static void login(String user, String password) throws Exception {
		boolean userEntered = false;
		long end = System.currentTimeMillis() + 240_000;
		while (System.currentTimeMillis() < end) {
			Thread.sleep(1000);
			try {
				Platform.runLater(() -> {
				});
			} catch (IllegalStateException notStarted) {
				continue;
			}
			if (!userEntered && Boolean.TRUE.equals(onFx(() -> fill("#userId", user, "#sub1")))) {
				userEntered = true;
				log.println("ACTION user ID entered");
			} else if (userEntered && Boolean.TRUE.equals(onFx(() -> fill("#password", password, "#sub2")))) {
				log.println("ACTION password entered");
				for (int i = 0; i < 20; i++) {
					Thread.sleep(1000);
					onFx(LivenessScreensDriver::closeAlert);
				}
				onFx(() -> {
					for (Window w : new ArrayList<>(Window.getWindows())) {
						if (w.isShowing() && w.getScene() != null) {
							snapshotScene(w.getScene(), "home");
						}
					}
					return null;
				});
				return;
			}
		}
		throw new IllegalStateException("login screen not reached");
	}

	/** Closes one client alert ({@code #alertGridPane} with the {@code #exit} link), recording its text. */
	private static Void closeAlert() {
		for (Window window : new ArrayList<>(Window.getWindows())) {
			Scene scene = window.getScene();
			if (window.isShowing() && scene != null && scene.lookup("#alertGridPane") != null
					&& scene.lookup("#exit") instanceof ButtonBase) {
				snapshotScene(scene, "alert-" + System.currentTimeMillis());
				((ButtonBase) scene.lookup("#exit")).fire();
				log.println("ALERT closed");
			}
		}
		return null;
	}

	private static Boolean fill(String fieldId, String value, String buttonId) {
		for (Window window : new ArrayList<>(Window.getWindows())) {
			Scene scene = window.getScene();
			if (!window.isShowing() || scene == null) {
				continue;
			}
			Node field = scene.lookup(fieldId);
			Node button = scene.lookup(buttonId);
			if (field instanceof TextInputControl && button instanceof ButtonBase && field.isVisible()
					&& field.getParent() != null && field.getParent().isVisible()) {
				((TextInputControl) field).setText(value);
				((ButtonBase) button).fire();
				return true;
			}
		}
		return false;
	}

	private static Void snapshot(LivenessDialog dialog, String name) {
		snapshotScene(dialog.panel().getScene(), name);
		return null;
	}

	private static void snapshotScene(Scene scene, String name) {
		try {
			WritableImage image = scene.snapshot(null);
			File file = out.resolve(name + ".png").toFile();
			ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
			log.println("SNAPSHOT " + file.getName());
		} catch (Exception e) {
			e.printStackTrace(log);
		}
	}

	private interface FxCall<T> {
		T call() throws Exception;
	}

	private static <T> T onFx(FxCall<T> call) throws Exception {
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Exception> error = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				result.set(call.call());
			} catch (Exception e) {
				error.set(e);
			} finally {
				done.countDown();
			}
		});
		if (!done.await(20, TimeUnit.SECONDS)) {
			return null;
		}
		if (error.get() != null) {
			throw error.get();
		}
		return result.get();
	}
}
