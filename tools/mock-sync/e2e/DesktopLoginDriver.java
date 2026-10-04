/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.WritableImage;
import javafx.scene.text.Text;
import javafx.stage.Window;

/**
 * End-to-end check of the desktop Registration Client against MockSync (test T-SYNC-03).
 * <p>
 * It starts the real client ({@code io.mosip.registration.controller.Initialization}) in this JVM, enters the user ID
 * and password into the real login screen by setting the text fields and firing the real Submit buttons on the
 * JavaFX thread, closes the client's alert dialogs after recording their text, and saves a PNG snapshot of every
 * window each time the visible text changes. No operating-system input events are used, so it also works on a
 * locked Windows session and in CI.
 * <p>
 * Usage (from the client's working directory, client jars on the class path, a JDK that includes JavaFX):
 * <pre>
 * java -cp "registration-client.jar;lib/*" DesktopLoginDriver.java &lt;userId&gt; &lt;password&gt; &lt;outDir&gt; [seconds]
 * </pre>
 * Output: {@code <outDir>/driver.log} (STATE, ACTION, ALERT and SNAPSHOT lines) and {@code <outDir>/NNN-*.png}.
 */
public class DesktopLoginDriver {

	private static PrintStream log;
	private static Path out;
	private static int snapshotCount;

	/**
	 * Runs the client and the login steps.
	 *
	 * @param args user ID, password, output directory, optional run time in seconds (default 300)
	 * @throws Exception on I/O failure
	 */
	public static void main(String[] args) throws Exception {
		String user = args[0];
		String password = args[1];
		out = Path.of(args[2]);
		long seconds = args.length > 3 ? Long.parseLong(args[3]) : 300;
		Files.createDirectories(out);
		log = new PrintStream(Files.newOutputStream(out.resolve("driver.log")), true, "UTF-8");
		startClient();

		boolean userEntered = false;
		boolean passwordEntered = false;
		String lastState = "";
		long end = System.currentTimeMillis() + seconds * 1000;
		while (System.currentTimeMillis() < end) {
			Thread.sleep(1000);
			if (!fxStarted()) {
				continue;
			}
			String state = onFx(DesktopLoginDriver::describeWindows);
			if (state != null && !state.equals(lastState)) {
				log.println("STATE " + System.currentTimeMillis() + " " + state);
				onFx(() -> snapshotAll("state"));
				lastState = state;
			}
			if (Boolean.TRUE.equals(onFx(DesktopLoginDriver::closeAlert))) {
				continue;
			}
			if (!userEntered && Boolean.TRUE.equals(onFx(() -> fill("#userId", user, "#sub1")))) {
				userEntered = true;
				log.println("ACTION user ID entered");
			} else if (userEntered && !passwordEntered
					&& Boolean.TRUE.equals(onFx(() -> fill("#password", password, "#sub2")))) {
				passwordEntered = true;
				log.println("ACTION password entered");
			}
		}
		onFx(() -> snapshotAll("final"));
		log.println("DONE");
		System.exit(0);
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

	private static boolean fxStarted() {
		try {
			Platform.runLater(() -> {
			});
			return true;
		} catch (IllegalStateException notStarted) {
			return false;
		}
	}

	private static String describeWindows() {
		StringBuilder builder = new StringBuilder();
		for (Window window : new ArrayList<>(Window.getWindows())) {
			if (window.isShowing() && window.getScene() != null) {
				List<String> texts = new ArrayList<>();
				collectTexts(window.getScene().getRoot(), texts);
				builder.append("[window: ").append(String.join(" | ", texts.subList(0, Math.min(texts.size(), 30))))
						.append("] ");
			}
		}
		return builder.toString();
	}

	private static void collectTexts(Node node, List<String> texts) {
		if (!node.isVisible()) {
			return;
		}
		String text = node instanceof Labeled ? ((Labeled) node).getText()
				: node instanceof Text ? ((Text) node).getText() : null;
		if (text != null && !text.isBlank()) {
			texts.add(text.trim().replaceAll("\\s+", " "));
		}
		if (node instanceof Parent) {
			for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
				collectTexts(child, texts);
			}
		}
	}

	private static Boolean fill(String fieldId, String value, String buttonId) {
		for (Window window : new ArrayList<>(Window.getWindows())) {
			Scene scene = window.getScene();
			if (!window.isShowing() || scene == null) {
				continue;
			}
			Node field = scene.lookup(fieldId);
			Node button = scene.lookup(buttonId);
			if (field instanceof TextInputControl && button instanceof ButtonBase && shown(field)
					&& !((ButtonBase) button).isDisabled()) {
				((TextInputControl) field).setText(value);
				((ButtonBase) button).fire();
				return true;
			}
		}
		return false;
	}

	private static boolean shown(Node node) {
		for (Node n = node; n != null; n = n.getParent()) {
			if (!n.isVisible()) {
				return false;
			}
		}
		return true;
	}

	/** Closes one client alert ({@code Alert.fxml}: {@code #alertGridPane} with the {@code #exit} link). */
	private static Boolean closeAlert() {
		for (Window window : new ArrayList<>(Window.getWindows())) {
			Scene scene = window.getScene();
			if (!window.isShowing() || scene == null || scene.lookup("#alertGridPane") == null) {
				continue;
			}
			Node exit = scene.lookup("#exit");
			if (exit instanceof ButtonBase) {
				List<String> texts = new ArrayList<>();
				collectTexts(scene.getRoot(), texts);
				log.println("ALERT " + String.join(" | ", texts));
				snapshot(scene, "alert");
				((ButtonBase) exit).fire();
				return true;
			}
		}
		return false;
	}

	private static Void snapshotAll(String label) {
		for (Window window : new ArrayList<>(Window.getWindows())) {
			if (window.isShowing() && window.getScene() != null) {
				snapshot(window.getScene(), label);
			}
		}
		return null;
	}

	private static void snapshot(Scene scene, String label) {
		try {
			WritableImage image = scene.snapshot(null);
			File file = out.resolve(String.format("%03d-%s.png", ++snapshotCount, label)).toFile();
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
