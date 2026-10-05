/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.sql.DataSource;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import javafx.stage.Window;

/**
 * Scripted end-to-end driver for the real desktop Registration Client (all face liveness flows of PS04).
 * <p>
 * It starts the client ({@code io.mosip.registration.controller.Initialization}) in this JVM and runs a flow script:
 * one command per line that clicks the client's own buttons and panes, fills its fields, waits for texts, switches the
 * MockMDS face source and faults over the MockMDS admin API, queries the client database and saves PNG snapshots.
 * No operating-system input events are used (buttons are fired and mouse-click events are dispatched on the JavaFX
 * thread), so it works on a locked Windows session and in CI.
 * <p>
 * Usage (working directory = the client run folder, a JDK 11 with JavaFX, client jars on the class path):
 * <pre>
 * java -cp "lib/*" DesktopFlowDriver.java &lt;outDir&gt; &lt;script&gt; [&lt;script&gt;...]
 * java -cp "lib/*" DesktopFlowDriver.java &lt;outDir&gt; --interactive &lt;commandDir&gt;
 * java -cp "lib/*" DesktopFlowDriver.java --check-prompts
 * </pre>
 * {@code --check-prompts} only checks which frame pack each liveness prompt selects and exits (0 when all match).
 * <p>
 * In interactive mode the driver polls {@code <commandDir>/next.cmd}, runs its lines and deletes it, so flows can be
 * explored step by step against one running client.
 * <p>
 * Output in {@code <outDir>}: {@code driver.log} (one line per command, state change, alert, result) and PNG files.
 * Lines starting with {@code RESULT} are the checks ({@code expect}, {@code expect-alert}, {@code expect-sql}):
 * {@code RESULT PASS ...} or {@code RESULT FAIL ...}.
 * <p>
 * Commands ({@code <sel>} is {@code #fxId}, {@code text=<exact text>} or {@code text~<regex>}):
 * <ul>
 * <li>{@code start} start the client; {@code wait-login [sec]} wait for the login screen</li>
 * <li>{@code login <user> <password>} password login (user ID page, then password page)</li>
 * <li>{@code click <sel> [sec]} fire a button, or dispatch a mouse click on a node (waits until it is shown)</li>
 * <li>{@code fill <sel> <text>}, {@code select <sel> <index|text>}, {@code check <sel> [true|false]}; the selector
 * {@code dialog} names the text field of an open JavaFX {@code TextInputDialog} (it has no id)</li>
 * <li>{@code wait <regex> [sec]}, {@code wait-gone <regex> [sec]}, {@code wait-node <sel> [sec]},
 * {@code sleep <ms>}</li>
 * <li>{@code expect <regex> [sec]} like wait, but records RESULT PASS/FAIL and never aborts</li>
 * <li>{@code expect-live [sec]} wait for the liveness window to report a pass, pressing Retry after a failed attempt
 * (at most 3 attempts); one RESULT with the attempt that passed</li>
 * <li>{@code autoclose on|off} close the client's alert windows as they appear (their text is logged);
 * {@code alert-close}; {@code expect-alert <regex> [sec]} a matching alert was seen since the last
 * {@code mark}; {@code mark}</li>
 * <li>{@code dialog <OK|CANCEL|YES|NO>} press a button of a JavaFX {@code Alert} dialog;
 * {@code menu <text>} fire a menu item of the header menu (for example {@code Logout})</li>
 * <li>{@code network off|on} make every server call of the client fail (offline) or work again; MockSync itself
 * keeps running for other clients</li>
 * <li>{@code mask on|off} hide large images (camera preview, captured faces) and HTML views (preview,
 * acknowledgement) in later snapshots, for screenshots that may be published</li>
 * <li>{@code snap <name>} snapshot every window; {@code dump <name>} write the scene graphs to
 * {@code <name>.txt}</li>
 * <li>{@code mds <path> <json>} MockMDS admin call; {@code source <pack>} switch the face source to a frame pack
 * folder under the pack root ({@code -Dframe.packs}, default {@code ../frame-packs}); {@code follow <prefix>|off}
 * switch to {@code <prefix>-blink}, {@code -smile}, {@code -turn-left}, {@code -turn-right} or {@code -look-up}
 * whenever the liveness prompt asks for that action (prompts matched without regard to case), and back to
 * {@code <prefix>-main} for "Look directly at the camera and hold still" and every other state, after a reaction
 * time ({@code follow <prefix> [ms]}, default 500 ms); a prompt whose pack is missing is logged as
 * {@code FOLLOW no pack for <action>} and the source is left unchanged</li>
 * <li>{@code sql <query>} run a query on the client database and log the rows; {@code expect-sql <min rows>
 * <query>}; {@code expect-no-rows <query>}</li>
 * <li>{@code if-node <#fxId> <command>} run the command only when that node is shown; {@code each-row <#table>
 * <command>} select every row of a table in turn (last row first) and run the command</li>
 * <li>{@code set <NAME> <value>} define a variable; {@code ${NAME}} in later lines is replaced by its value</li>
 * <li>{@code include <file>} run another flow file (path relative to the current one); {@code http <METHOD> <url>
 * [json]} call an HTTP API, for example the MockSync admin API</li>
 * <li>{@code section <file>} like include, but a step that cannot be done fails only that file: the failure is
 * recorded, open windows are closed, the network is restored, the client goes home and the next line runs</li>
 * <li>{@code label <text>} write a section label into the log; {@code abort-on-fail on|off}; {@code exit}</li>
 * </ul>
 */
public class DesktopFlowDriver {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private static final String MDS = System.getProperty("mds.url", "http://127.0.0.1:4501");
	/** Frame pack root; the default is the run layout's {@code run/frame-packs} next to the client folder. */
	private static final Path PACKS = Path.of(System.getProperty("frame.packs", "../frame-packs")).toAbsolutePath()
			.normalize();
	private static final long DEFAULT_WAIT_SECONDS = 30;
	private static final String LIVE_PASS_TEXT = "Good, face captured successfully";
	private static final int MAX_LIVE_ATTEMPTS = 3;
	private static final int NO_ROWS = -2;
	private static final double MASK_MIN_SIDE = 60;
	private static volatile boolean maskFaces;
	/** A local port nothing listens on (the discard port), used as the proxy of a cut network. */
	private static final String CLOSED_PORT = "9";

	private static PrintStream log;
	private static Path scriptDir = Path.of(".");
	private static Path out;
	private static int snapshotCount;
	private static volatile boolean autoClose = true;
	private static volatile String followPrefix;
	private static volatile String currentSource = "";
	private static final long DEFAULT_REACTION_MS = 500;
	private static volatile long reactionMs = DEFAULT_REACTION_MS;
	private static String pendingAction;
	private static long pendingSince;
	private static boolean abortOnFail;
	private static int passed;
	private static int failed;
	private static final List<String> ALERTS = new CopyOnWriteArrayList<>();
	private static final Map<String, String> VARIABLES = new java.util.LinkedHashMap<>();
	private static volatile int alertMark;

	/**
	 * Runs the scripts, or the interactive command loop.
	 *
	 * @param args output directory, then script files or {@code --interactive <commandDir>}
	 * @throws Exception on I/O failure
	 */
	public static void main(String[] args) throws Exception {
		if (args.length == 1 && "--check-prompts".equals(args[0])) {
			System.exit(checkPrompts());
		}
		out = Path.of(args[0]);
		Files.createDirectories(out);
		log = new PrintStream(Files.newOutputStream(out.resolve("driver.log")), true, "UTF-8");
		startWatcher();
		int exit = 0;
		try {
			if (args.length > 2 && "--interactive".equals(args[1])) {
				interactive(Path.of(args[2]));
			} else {
				for (int i = 1; i < args.length; i++) {
					runFile(Path.of(args[i]));
				}
			}
		} catch (AbortException e) {
			log.println(stamp() + "ABORTED " + e.getMessage());
			exit = 2;
		} catch (Exception e) {
			log.println(stamp() + "ERROR " + e);
			e.printStackTrace(log);
			exit = 3;
		}
		log.println(stamp() + "SUMMARY passed=" + passed + " failed=" + failed);
		log.println("DONE");
		System.exit(exit != 0 ? exit : failed > 0 ? 1 : 0);
	}

	private static void interactive(Path commandDir) throws Exception {
		Files.createDirectories(commandDir);
		Path next = commandDir.resolve("next.cmd");
		log.println(stamp() + "INTERACTIVE waiting for " + next);
		while (true) {
			if (Files.exists(next)) {
				List<String> lines = Files.readAllLines(next, StandardCharsets.UTF_8);
				Files.delete(next);
				try {
					if (!runScript(lines)) {
						return;
					}
				} catch (AbortException e) {
					log.println(stamp() + "ABORTED " + e.getMessage());
				}
				log.println(stamp() + "READY");
			}
			Thread.sleep(300);
		}
	}

	/** Runs a flow file; {@code include} lines inside it are resolved against its folder. */
	private static boolean runFile(Path file) throws Exception {
		Path previous = scriptDir;
		scriptDir = file.toAbsolutePath().getParent();
		try {
			return runScript(Files.readAllLines(file, StandardCharsets.UTF_8));
		} finally {
			scriptDir = previous;
		}
	}

	/** Runs script lines; returns false after {@code exit}. */
	private static boolean runScript(List<String> lines) throws Exception {
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			line = substitute(line);
			log.println(stamp() + "> " + line);
			if (!execute(line)) {
				return false;
			}
		}
		return true;
	}

	/** Replaces {@code ${NAME}} with the value given by {@code set NAME value}. */
	private static String substitute(String line) {
		String out = line;
		for (Map.Entry<String, String> v : VARIABLES.entrySet()) {
			out = out.replace("${" + v.getKey() + "}", v.getValue());
		}
		return out;
	}

	private static boolean execute(String line) throws Exception {
		String[] p = line.split("\\s+", 2);
		String cmd = p[0].toLowerCase(Locale.ROOT);
		String rest = p.length > 1 ? p[1] : "";
		switch (cmd) {
		case "start":
			startClient();
			break;
		case "wait-login":
			require(waitFor(() -> find("#userId") != null, seconds(rest, 300)), "login screen not shown");
			break;
		case "login":
			login(rest.split("\\s+"));
			break;
		case "click":
			click(selectorOf(rest), seconds(lastNumber(rest), DEFAULT_WAIT_SECONDS));
			break;
		case "fill": {
			String[] a = rest.split("\\s+", 2);
			fill(a[0], a.length > 1 ? a[1] : "");
			break;
		}
		case "select": {
			String[] a = rest.split("\\s+", 2);
			select(a[0], a[1]);
			break;
		}
		case "check": {
			String[] a = rest.split("\\s+", 2);
			check(a[0], a.length < 2 || Boolean.parseBoolean(a[1]));
			break;
		}
		case "wait":
			require(waitText(regex(rest), seconds(lastNumber(rest), DEFAULT_WAIT_SECONDS), true), "text not shown: " + rest);
			break;
		case "wait-gone":
			require(waitText(regex(rest), seconds(lastNumber(rest), DEFAULT_WAIT_SECONDS), false), "text still shown: " + rest);
			break;
		case "wait-node": {
			String sel = selectorOf(rest);
			require(waitFor(() -> shownNode(sel) != null, seconds(lastNumber(rest), DEFAULT_WAIT_SECONDS)),
					"node not shown: " + sel);
			break;
		}
		case "expect":
			result(waitText(regex(rest), seconds(lastNumber(rest), DEFAULT_WAIT_SECONDS), true), "text " + rest);
			break;
		case "expect-live":
			expectLive(seconds(rest.trim().isEmpty() ? null : rest.trim(), 90));
			break;
		case "sleep":
			Thread.sleep(Long.parseLong(rest.trim()));
			break;
		case "network":
			network("on".equalsIgnoreCase(rest.trim()));
			break;
		case "mask":
			maskFaces = "on".equalsIgnoreCase(rest.trim());
			break;
		case "autoclose":
			autoClose = "on".equalsIgnoreCase(rest.trim());
			break;
		case "alert-close":
			onFx(DesktopFlowDriver::closeAlert);
			break;
		case "mark":
			alertMark = ALERTS.size();
			break;
		case "expect-alert":
			result(waitAlert(regex(rest), seconds(lastNumber(rest), DEFAULT_WAIT_SECONDS)), "alert " + rest);
			break;
		case "menu":
			require(Boolean.TRUE.equals(onFx(() -> fireMenuItem(rest.trim()))), "no menu item " + rest);
			break;
		case "dialog":
			require(Boolean.TRUE.equals(onFx(() -> pressDialog(rest.trim()))), "no dialog button " + rest);
			break;
		case "snap":
			onFx(() -> snapshotAll(rest.trim()));
			break;
		case "dump":
			dump(rest.trim());
			break;
		case "set": {
			String[] a = rest.trim().split("\\s+", 2);
			VARIABLES.put(a[0], a.length > 1 ? a[1] : "");
			break;
		}
		case "include":
			return runFile(scriptDir.resolve(rest.trim()));
		case "section":
			return section(scriptDir.resolve(rest.trim()));
		case "http": {
			String[] a = rest.trim().split("\\s+", 3);
			http(a[0], a[1], a.length > 2 ? a[2] : "");
			break;
		}
		case "mds": {
			String[] a = rest.split("\\s+", 2);
			mds(a[0], a.length > 1 ? a[1] : "{}");
			break;
		}
		case "source":
			source(rest.trim());
			break;
		case "follow": {
			String[] a = rest.trim().split("\\s+");
			followPrefix = "off".equalsIgnoreCase(a[0]) ? null : a[0];
			reactionMs = a.length > 1 ? Long.parseLong(a[1]) : DEFAULT_REACTION_MS;
			pendingAction = null;
			break;
		}
		case "sql":
			sql(rest, -1);
			break;
		case "expect-sql": {
			String[] a = rest.split("\\s+", 2);
			sql(a[1], Integer.parseInt(a[0]));
			break;
		}
		case "expect-no-rows":
			sql(rest, NO_ROWS);
			break;
		case "each-row": {
			// each-row <#table> <command...>: select every row of a table in turn, last row first, and run the command on
			// it. Last to first, because a screen may move a handled row to the end (the EOD approval table does).
			String[] a = rest.trim().split("\\s+", 2);
			Integer rows = onFx(() -> shownNode(a[0]) instanceof TableView
					? ((TableView<?>) shownNode(a[0])).getItems().size() : 0);
			for (int i = rows == null ? -1 : rows - 1; i >= 0; i--) {
				final int row = i;
				onFx(() -> {
					((TableView<?>) shownNode(a[0])).getSelectionModel().clearAndSelect(row);
					return null;
				});
				log.println(stamp() + "  row " + row);
				if (!execute(a[1])) {
					return false;
				}
			}
			break;
		}
		case "if-node": {
			// if-node <#fxId> <command...>: run the command only when that node is shown (optional screen parts).
			String[] a = rest.trim().split("\\s+", 2);
			if (Boolean.TRUE.equals(onFx(() -> shownNode(a[0]) != null))) {
				return execute(a[1]);
			}
			log.println(stamp() + "  skipped, " + a[0] + " not shown");
			break;
		}
		case "label":
			log.println(stamp() + "==== " + rest);
			break;
		case "abort-on-fail":
			abortOnFail = "on".equalsIgnoreCase(rest.trim());
			break;
		case "exit":
			return false;
		default:
			log.println(stamp() + "unknown command " + cmd);
		}
		return true;
	}

	// ---------------------------------------------------------------------------------------------- client

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

	/**
	 * Waits for a bona fide liveness check to pass, pressing Retry when an attempt fails (as an operator would), and
	 * records one result with the attempt that passed. Frame packs of real people contain spontaneous blinks, and a
	 * loaded machine can make three frames in a row slower than the engine's limit; both end one attempt, not the
	 * flow. The reason of every failed attempt is logged.
	 */
	private static void expectLive(long seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000;
		int attempt = 1;
		while (System.currentTimeMillis() < end) {
			String state = onFx(DesktopFlowDriver::allText);
			if (state != null && state.contains(LIVE_PASS_TEXT)) {
				result(true, "live face passed on attempt " + attempt);
				return;
			}
			if (Boolean.TRUE.equals(onFx(() -> shownNode("#retryButton") != null))) {
				log.println(stamp() + "  attempt " + attempt + " failed: "
						+ onFx(() -> textOf(shownNode("#promptLabel"))));
				if (attempt >= MAX_LIVE_ATTEMPTS) {
					break;
				}
				click("#retryButton", 5);
				attempt++;
				Thread.sleep(1000);
			}
			Thread.sleep(250);
		}
		result(false, "live face did not pass in " + attempt + " attempt(s)");
	}

	/**
	 * Cuts or restores the client's connection to the server. The client calls MockSync over HTTPS with
	 * {@code HttpURLConnection} (Spring's SimpleClientHttpRequestFactory), which reads the proxy system properties
	 * at every connection: "off" sends that traffic to a closed local port, so every server call fails exactly as
	 * when the server is down, while MockSync keeps serving other clients. MockMDS is called over plain HTTP with
	 * Apache HttpClient, which ignores these properties, so the devices keep working.
	 */
	/**
	 * Runs a flow file as an independent section: when one of its steps cannot be done (an abort), the failure is
	 * counted, the client is brought back to its home screen and the next section runs.
	 */
	private static boolean section(Path file) throws Exception {
		try {
			return runFile(file);
		} catch (AbortException e) {
			failed++;
			log.println(stamp() + "RESULT FAIL section " + file.getFileName() + " stopped: " + e.getMessage());
			recover();
			return true;
		}
	}

	/** Closes open liveness windows, dialogs and alerts, restores the network and goes to the home screen. */
	private static void recover() throws Exception {
		followPrefix = null;
		network(true);
		for (int i = 0; i < 3; i++) {
			onFxQuiet(() -> {
				Node close = shownNode("#cancelButton");
				if (close instanceof ButtonBase && !close.isDisabled()) {
					((ButtonBase) close).fire();
				}
				pressDialog("CANCEL");
				closeAlert();
				return null;
			});
			Thread.sleep(700);
		}
		if (Boolean.TRUE.equals(onFx(() -> clickable("#homeImgView") != null))) {
			click("#homeImgView", 5);
			Thread.sleep(1500);
			if (Boolean.TRUE.equals(onFx(() -> shownNode("#confirm") != null))) {
				click("#confirm", 5);
			}
		}
		log.println(stamp() + "  recovered to the home screen");
	}

	private static void network(boolean on) {
		if (on) {
			System.clearProperty("https.proxyHost");
			System.clearProperty("https.proxyPort");
			System.clearProperty("http.nonProxyHosts");
		} else {
			System.setProperty("https.proxyHost", "127.0.0.1");
			System.setProperty("https.proxyPort", CLOSED_PORT);
			// Java adds localhost and 127.* to any non-empty non-proxy list; only an empty list proxies localhost,
			// the MockSync host name.
			System.setProperty("http.nonProxyHosts", "");
		}
		log.println(stamp() + "  network " + (on ? "restored" : "cut (server calls fail)"));
	}

	private static void login(String[] a) throws Exception {
		String user = a[0];
		String password = a[1];
		require(waitFor(() -> shownNode("#userId") != null, 300), "user ID field not shown");
		onFx(() -> {
			((TextInputControl) shownNode("#userId")).setText(user);
			((ButtonBase) find("#sub1")).fire();
			return null;
		});
		require(waitFor(() -> shownNode("#password") != null, 60), "password field not shown");
		onFx(() -> {
			((TextInputControl) shownNode("#password")).setText(password);
			((ButtonBase) find("#sub2")).fire();
			return null;
		});
	}

	// ---------------------------------------------------------------------------------------------- actions

	/**
	 * Clicks a node. The node is found on the JavaFX thread, and the click itself is posted without waiting for it:
	 * a handler that opens a modal dialog ({@code showAndWait}) keeps running while the next commands work on that
	 * dialog.
	 */
	private static void click(String selector, long seconds) throws Exception {
		require(waitFor(() -> clickable(selector) != null, seconds), "not clickable: " + selector);
		AtomicReference<Node> target = new AtomicReference<>();
		onFx(() -> {
			target.set(clickable(selector));
			return null;
		});
		Node node = target.get();
		require(node != null, "click failed: " + selector);
		log.println(stamp() + "  " + (node instanceof ButtonBase ? "fired " : "clicked ") + onFx(() -> describeNode(node)));
		Platform.runLater(() -> {
			if (node instanceof ButtonBase) {
				((ButtonBase) node).fire();
			} else {
				dispatchClick(node);
			}
		});
		Thread.sleep(150);
	}

	private static void dispatchClick(Node node) {
		Bounds b = node.getLayoutBounds();
		double x = b.getMinX() + b.getWidth() / 2;
		double y = b.getMinY() + b.getHeight() / 2;
		javafx.geometry.Point2D s = node.localToScreen(x, y);
		double sx = s == null ? 0 : s.getX();
		double sy = s == null ? 0 : s.getY();
		for (javafx.event.EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED,
				MouseEvent.MOUSE_CLICKED)) {
			Event.fireEvent(node, new MouseEvent(type, x, y, sx, sy, MouseButton.PRIMARY, 1, false, false, false, false,
					type == MouseEvent.MOUSE_PRESSED, false, false, true, false, true, null));
		}
	}

	private static void fill(String selector, String text) throws Exception {
		require(waitFor(() -> field(selector) != null, DEFAULT_WAIT_SECONDS), "no text field " + selector);
		onFx(() -> {
			TextInputControl field = field(selector);
			field.requestFocus();
			field.setText(text);
			return null;
		});
	}

	/** The text field for a selector; {@code dialog} is the first text field of the newest open dialog window. */
	private static TextInputControl field(String selector) {
		if ("dialog".equals(selector)) {
			for (Window w : windows()) {
				if (w.getScene().getRoot() instanceof DialogPane) {
					List<Node> all = new ArrayList<>();
					collect(w.getScene().getRoot(), all);
					for (Node n : all) {
						if (n instanceof TextInputControl && shown(n)) {
							return (TextInputControl) n;
						}
					}
				}
			}
			return null;
		}
		Node n = shownNode(selector);
		return n instanceof TextInputControl ? (TextInputControl) n : null;
	}

	@SuppressWarnings("unchecked")
	private static void select(String selector, String value) throws Exception {
		require(waitFor(() -> {
			Node n = shownNode(selector);
			return n instanceof ComboBox && !((ComboBox<?>) n).getItems().isEmpty()
					|| n instanceof ListView && !((ListView<?>) n).getItems().isEmpty();
		}, DEFAULT_WAIT_SECONDS), "no items in " + selector);
		String chosen = onFx(() -> {
			Node n = shownNode(selector);
			List<?> items = n instanceof ComboBox ? ((ComboBox<?>) n).getItems() : ((ListView<?>) n).getItems();
			int index = -1;
			if (value.matches("\\d+")) {
				index = Integer.parseInt(value);
			} else {
				for (int i = 0; i < items.size(); i++) {
					if (String.valueOf(items.get(i)).contains(value)) {
						index = i;
						break;
					}
				}
			}
			if (index < 0 || index >= items.size()) {
				return null;
			}
			if (n instanceof ComboBox) {
				((ComboBox<Object>) n).getSelectionModel().select(index);
			} else {
				((ListView<Object>) n).getSelectionModel().select(index);
			}
			return String.valueOf(items.get(index));
		});
		require(chosen != null, "no item " + value + " in " + selector);
		log.println(stamp() + "  selected " + chosen);
	}

	private static void check(String selector, boolean value) throws Exception {
		require(waitFor(() -> shownNode(selector) != null, DEFAULT_WAIT_SECONDS), "no check box " + selector);
		onFx(() -> {
			Node n = shownNode(selector);
			if (n instanceof CheckBox) {
				CheckBox box = (CheckBox) n;
				if (box.isSelected() != value) {
					box.fire();
				}
			} else if (n instanceof Toggle) {
				((Toggle) n).setSelected(value);
			}
			return null;
		});
	}

	/** Fires the first menu item (in any menu bar of a showing window) whose text contains the given text. */
	private static Boolean fireMenuItem(String text) {
		for (Window w : windows()) {
			List<Node> all = new ArrayList<>();
			collect(w.getScene().getRoot(), all);
			for (Node n : all) {
				if (!(n instanceof MenuBar)) {
					continue;
				}
				for (Menu menu : ((MenuBar) n).getMenus()) {
					for (MenuItem item : menu.getItems()) {
						if (item.getText() != null && item.getText().contains(text) && !item.isDisable()) {
							log.println(stamp() + "  menu item " + item.getText());
							item.fire();
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static Boolean pressDialog(String button) {
		ButtonType type;
		switch (button.toUpperCase(Locale.ROOT)) {
		case "CANCEL":
			type = ButtonType.CANCEL;
			break;
		case "YES":
			type = ButtonType.YES;
			break;
		case "NO":
			type = ButtonType.NO;
			break;
		default:
			type = ButtonType.OK;
		}
		for (Window window : windows()) {
			if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane) {
				DialogPane pane = (DialogPane) window.getScene().getRoot();
				Node b = pane.lookupButton(type);
				if (b instanceof ButtonBase) {
					log.println(stamp() + "  dialog: " + pane.getContentText());
					((ButtonBase) b).fire();
					return true;
				}
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------- MockMDS

	private static void source(String pack) throws Exception {
		String path = PACKS.resolve(pack).toString().replace('\\', '/');
		mds("/admin/source",
				"{\"type\":\"Face\",\"source\":\"SEQUENCE\",\"path\":\"" + path + "\",\"fps\":15,\"loop\":true}");
		currentSource = pack;
	}

	/** Calls an HTTP API (for example the MockSync admin API) and logs the status and the start of the answer. */
	private static void http(String method, String url, String body) throws Exception {
		HttpRequest.BodyPublisher publisher = body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body);
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json").method(method.toUpperCase(Locale.ROOT), publisher).build(),
				HttpResponse.BodyHandlers.ofString());
		String answer = r.body().replaceAll("\\s+", " ");
		log.println(stamp() + "  HTTP " + method + " " + url + " -> " + r.statusCode() + " "
				+ answer.substring(0, Math.min(160, answer.length())));
	}

	private static void mds(String path, String body) throws Exception {
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(MDS + path)).timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		String answer = r.body().replaceAll("\\s+", " ");
		log.println(stamp() + "  MDS " + path + " -> " + answer.substring(0, Math.min(160, answer.length())));
	}

	// ---------------------------------------------------------------------------------------------- database

	private static void sql(String query, int minRows) throws Exception {
		int rows = 0;
		try {
			Object context = Class.forName("io.mosip.registration.controller.ClientApplication")
					.getMethod("getApplicationContext").invoke(null);
			DataSource ds = (DataSource) context.getClass().getMethod("getBean", Class.class).invoke(context,
					DataSource.class);
			try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
				ResultSetMetaData md = rs.getMetaData();
				while (rs.next()) {
					List<String> cells = new ArrayList<>();
					for (int i = 1; i <= md.getColumnCount(); i++) {
						cells.add(md.getColumnLabel(i) + "=" + rs.getString(i));
					}
					log.println(stamp() + "  ROW " + String.join(" | ", cells));
					rows++;
				}
			}
		} catch (Exception e) {
			log.println(stamp() + "  SQL error " + e);
		}
		log.println(stamp() + "  rows=" + rows);
		if (minRows == NO_ROWS) {
			result(rows == 0, "sql no rows: " + query);
		} else if (minRows >= 0) {
			result(rows >= minRows, "sql rows>=" + minRows + ": " + query);
		}
	}

	// ---------------------------------------------------------------------------------------------- waiting

	private interface Check {
		boolean test() throws Exception;
	}

	private static boolean waitFor(Check check, long seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000;
		while (System.currentTimeMillis() < end) {
			if (fxStarted() && Boolean.TRUE.equals(onFx(check::test))) {
				return true;
			}
			Thread.sleep(250);
		}
		return false;
	}

	private static boolean waitText(Pattern pattern, long seconds, boolean present) throws Exception {
		return waitFor(() -> pattern.matcher(allText()).find() == present, seconds);
	}

	private static boolean waitAlert(Pattern pattern, long seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000;
		while (System.currentTimeMillis() < end) {
			for (int i = alertMark; i < ALERTS.size(); i++) {
				if (pattern.matcher(ALERTS.get(i)).find()) {
					return true;
				}
			}
			if (fxStarted()) {
				String open = onFx(DesktopFlowDriver::openAlertText);
				if (open != null && pattern.matcher(open).find()) {
					return true;
				}
			}
			Thread.sleep(250);
		}
		return false;
	}

	/** Background thread: logs every change of the visible texts, closes alerts, follows liveness prompts. */
	private static void startWatcher() {
		Thread watcher = new Thread(() -> {
			String last = "";
			while (true) {
				try {
					Thread.sleep(400);
					if (!fxStarted()) {
						continue;
					}
					if (autoClose) {
						onFx(DesktopFlowDriver::closeAlert);
					}
					String state = onFx(DesktopFlowDriver::describeWindows);
					if (state != null && !state.equals(last)) {
						last = state;
						log.println(stamp() + "STATE " + state);
					}
					String prefix = followPrefix;
					if (prefix != null && state != null) {
						follow(prefix, state);
					}
				} catch (InterruptedException e) {
					return;
				} catch (Exception e) {
					log.println(stamp() + "watcher " + e);
				}
			}
		}, "driver-watcher");
		watcher.setDaemon(true);
		watcher.start();
	}

	/**
	 * Liveness prompts the frame source follows, in the order they are tested, and the pack suffix of each: the
	 * English texts of {@code labels_en.properties} ({@code liveness.challenge.*} and
	 * {@code liveness.feedback.look_straight}), matched without regard to case. After the last challenge the client
	 * asks the person to look straight at the camera and hold still, which goes back to the {@code -main} pack.
	 */
	private static final String[][] PROMPTS = {
			{ "look straight|look directly at the camera and hold still|hold still", "main" },
			{ "please blink", "blink" },
			{ "please smile", "smile" },
			{ "turn your head to the left", "turn-left" },
			{ "turn your head to the right", "turn-right" },
			{ "please look up", "look-up" } };
	private static final Pattern[] PROMPT_PATTERNS = new Pattern[PROMPTS.length];
	static {
		for (int i = 0; i < PROMPTS.length; i++) {
			PROMPT_PATTERNS[i] = Pattern.compile(PROMPTS[i][0], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		}
	}
	/** The action whose missing pack was logged last, so a prompt that stays on screen is logged once. */
	private static String missingLogged;

	/**
	 * Returns the frame-pack suffix for the liveness prompt in the visible texts.
	 *
	 * @param state the visible texts of all windows
	 * @return {@code blink}, {@code smile}, {@code turn-left}, {@code turn-right}, {@code look-up} or {@code main}
	 */
	static String packSuffix(String state) {
		for (int i = 0; i < PROMPTS.length; i++) {
			if (PROMPT_PATTERNS[i].matcher(state).find()) {
				return PROMPTS[i][1];
			}
		}
		return "main";
	}

	/**
	 * Checks the prompt table against the client's English texts without starting the client
	 * ({@code DesktopFlowDriver --check-prompts}).
	 *
	 * @return 0 when every case matches, 1 otherwise
	 */
	static int checkPrompts() {
		String[][] cases = {
				{ "liveness | Please blink | Attempt 1 of 3", "blink" },
				{ "Please smile", "smile" },
				{ "Please turn your head to the left", "turn-left" },
				{ "Please turn your head to the right", "turn-right" },
				{ "TURN YOUR HEAD TO THE RIGHT", "turn-right" },
				{ "Please look up", "look-up" },
				{ "please LOOK UP", "look-up" },
				{ "Look directly at the camera and hold still.", "main" },
				{ "Look straight at the camera and hold still", "main" },
				{ "Hold still", "main" },
				{ "Look directly at the camera.", "main" },
				{ "Checking face liveness", "main" },
				{ "Good, face captured successfully", "main" } };
		int failures = 0;
		for (String[] c : cases) {
			String got = packSuffix(c[0]);
			boolean ok = got.equals(c[1]);
			if (!ok) {
				failures++;
			}
			System.out.println((ok ? "PASS " : "FAIL ") + "'" + c[0] + "' -> " + got + (ok ? "" : " (want " + c[1] + ")"));
		}
		System.out.println("prompt checks: " + (cases.length - failures) + "/" + cases.length + " passed");
		return failures == 0 ? 0 : 1;
	}

	private static void follow(String prefix, String state) throws Exception {
		String suffix = packSuffix(state);
		String wanted = prefix + "-" + suffix;
		if (wanted.equals(currentSource)) {
			pendingAction = null;
			return;
		}
		if (!Files.isDirectory(PACKS.resolve(wanted))) {
			// The face has no recording of this action: the challenge cannot be answered and the attempt fails.
			if (!wanted.equals(missingLogged)) {
				missingLogged = wanted;
				log.println(stamp() + "FOLLOW no pack for " + suffix.replace('-', '_') + " (" + wanted
						+ " missing under " + PACKS + ")");
			}
			pendingAction = null;
			return;
		}
		missingLogged = null;
		// A person reacts to a prompt after a few hundred milliseconds; switching at once would answer faster than
		// the engine's minimum onset and fail the challenge as ONSET_TOO_FAST.
		if (!wanted.endsWith("-main")) {
			long now = System.currentTimeMillis();
			if (!wanted.equals(pendingAction)) {
				pendingAction = wanted;
				pendingSince = now;
				return;
			}
			if (now - pendingSince < reactionMs) {
				return;
			}
		}
		pendingAction = null;
		source(wanted);
	}

	// ---------------------------------------------------------------------------------------------- scene helpers

	private static List<Window> windows() {
		List<Window> list = new ArrayList<>();
		for (Window w : Window.getWindows()) {
			if (w.isShowing() && w.getScene() != null) {
				list.add(w);
			}
		}
		Collections.reverse(list);
		return list;
	}

	/** Finds the first node for a selector in the showing windows, newest window first. */
	private static Node find(String selector) {
		for (Window w : windows()) {
			Node n = findIn(w.getScene().getRoot(), selector);
			if (n != null) {
				return n;
			}
		}
		return null;
	}

	private static Node shownNode(String selector) {
		for (Window w : windows()) {
			List<Node> all = new ArrayList<>();
			collect(w.getScene().getRoot(), all);
			for (Node n : all) {
				if (matches(n, selector) && shown(n)) {
					return n;
				}
			}
		}
		return null;
	}

	private static Node findIn(Node root, String selector) {
		if (selector.startsWith("#")) {
			List<Node> all = new ArrayList<>();
			collect(root, all);
			Node fallback = null;
			for (Node n : all) {
				if (matches(n, selector)) {
					if (shown(n)) {
						return n;
					}
					fallback = fallback == null ? n : fallback;
				}
			}
			return fallback;
		}
		List<Node> all = new ArrayList<>();
		collect(root, all);
		for (Node n : all) {
			if (matches(n, selector) && shown(n)) {
				return n;
			}
		}
		return null;
	}

	private static boolean matches(Node n, String selector) {
		if (selector.startsWith("#")) {
			return selector.substring(1).equals(n.getId());
		}
		String text = textOf(n);
		if (text == null) {
			return false;
		}
		text = text.trim();
		if (selector.startsWith("text=")) {
			return text.equals(selector.substring(5));
		}
		if (selector.startsWith("text~")) {
			return Pattern.compile(selector.substring(5)).matcher(text).find();
		}
		return false;
	}

	/** The node to click: a shown, enabled button, or the node itself when it or an ancestor handles mouse clicks. */
	private static Node clickable(String selector) {
		Node n = shownNode(selector);
		if (n == null) {
			return null;
		}
		for (Node c = n; c != null; c = c.getParent()) {
			if (c instanceof ButtonBase) {
				return c.isDisabled() ? null : c;
			}
			if (c.getOnMouseClicked() != null || c.getOnMousePressed() != null) {
				return c.isDisabled() ? null : n;
			}
		}
		// A container whose clickable part is a child (for example an HBox around an icon): click that child.
		List<Node> inside = new ArrayList<>();
		collect(n, inside);
		for (Node c : inside) {
			if (shown(c) && (c instanceof ButtonBase || c.getOnMouseClicked() != null)) {
				return c.isDisabled() ? null : c;
			}
		}
		// A caption next to its clickable tile (for example the settings tiles): click the tile in the same group.
		Node group = n.getParent() == null ? null : n.getParent();
		for (int level = 0; group != null && level < 2; level++, group = group.getParent()) {
			List<Node> near = new ArrayList<>();
			collect(group, near);
			for (Node c : near) {
				if (c != n && shown(c) && c.getOnMouseClicked() != null) {
					return c.isDisabled() ? null : c;
				}
			}
		}
		return n.isDisabled() ? null : n;
	}

	private static void collect(Node node, List<Node> all) {
		all.add(node);
		if (node instanceof Parent) {
			for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
				collect(child, all);
			}
		}
	}

	private static boolean shown(Node node) {
		for (Node n = node; n != null; n = n.getParent()) {
			if (!n.isVisible()) {
				return false;
			}
		}
		return true;
	}

	private static String textOf(Node n) {
		if (n instanceof Labeled) {
			return ((Labeled) n).getText();
		}
		if (n instanceof Text) {
			return ((Text) n).getText();
		}
		return null;
	}

	private static String allText() {
		StringBuilder b = new StringBuilder();
		for (Window w : windows()) {
			List<String> texts = new ArrayList<>();
			collectTexts(w.getScene().getRoot(), texts);
			b.append(String.join(" | ", texts)).append(" || ");
		}
		return b.toString();
	}

	private static String describeWindows() {
		StringBuilder builder = new StringBuilder();
		for (Window window : windows()) {
			List<String> texts = new ArrayList<>();
			collectTexts(window.getScene().getRoot(), texts);
			builder.append("[").append(String.join(" | ", texts.subList(0, Math.min(texts.size(), 40)))).append("] ");
		}
		return builder.toString();
	}

	private static void collectTexts(Node node, List<String> texts) {
		if (!node.isVisible()) {
			return;
		}
		// A Labeled draws its text with an internal LabeledText node; count that text once.
		boolean skinText = "LabeledText".equals(node.getClass().getSimpleName());
		String text = node instanceof TextInputControl || skinText ? null : textOf(node);
		if (text != null && !text.isBlank()) {
			texts.add(text.trim().replaceAll("\\s+", " "));
		}
		if (node instanceof Parent) {
			for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
				collectTexts(child, texts);
			}
		}
	}

	private static String describeNode(Node n) {
		String text = textOf(n);
		return n.getClass().getSimpleName() + (n.getId() == null ? "" : "#" + n.getId())
				+ (text == null ? "" : " '" + text.trim() + "'");
	}

	private static void dump(String name) throws Exception {
		String text = onFx(() -> {
			StringBuilder b = new StringBuilder();
			for (Window w : windows()) {
				b.append("WINDOW ").append(w.getClass().getSimpleName()).append('\n');
				dumpNode(w.getScene().getRoot(), 0, b);
			}
			return b.toString();
		});
		Files.writeString(out.resolve(name + ".txt"), text == null ? "" : text);
		log.println(stamp() + "  DUMP " + name + ".txt");
	}

	private static void dumpNode(Node n, int depth, StringBuilder b) {
		if (!n.isVisible()) {
			return;
		}
		b.append("  ".repeat(depth)).append(n.getClass().getSimpleName());
		if (n.getId() != null) {
			b.append(" #").append(n.getId());
		}
		String text = n instanceof TextInputControl ? ((TextInputControl) n).getText() : textOf(n);
		if (text != null && !text.isBlank()) {
			b.append(" '").append(text.trim().replaceAll("\\s+", " ")).append('\'');
		}
		if (n.isDisabled()) {
			b.append(" [disabled]");
		}
		if (n.getOnMouseClicked() != null) {
			b.append(" [onClick]");
		}
		if (n instanceof ComboBox) {
			b.append(" items=").append(((ComboBox<?>) n).getItems().size());
		}
		b.append('\n');
		if (n instanceof Parent) {
			for (Node child : ((Parent) n).getChildrenUnmodifiable()) {
				dumpNode(child, depth + 1, b);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------- alerts

	/** Closes one client alert ({@code Alert.fxml}: {@code #alertGridPane} with the {@code #exit} button). */
	private static Boolean closeAlert() {
		for (Window window : windows()) {
			Scene scene = window.getScene();
			if (scene.lookup("#alertGridPane") == null) {
				continue;
			}
			Node exit = scene.lookup("#exit");
			if (exit instanceof ButtonBase) {
				List<String> texts = new ArrayList<>();
				collectTexts(scene.getRoot(), texts);
				String text = String.join(" | ", texts);
				ALERTS.add(text);
				log.println(stamp() + "ALERT " + text);
				snapshot(scene, "alert");
				((ButtonBase) exit).fire();
				return true;
			}
		}
		return false;
	}

	private static String openAlertText() {
		for (Window window : windows()) {
			Scene scene = window.getScene();
			if (scene.lookup("#alertGridPane") != null || scene.getRoot() instanceof DialogPane) {
				List<String> texts = new ArrayList<>();
				collectTexts(scene.getRoot(), texts);
				return String.join(" | ", texts);
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------- snapshots

	private static Void snapshotAll(String label) {
		for (Window window : windows()) {
			snapshot(window.getScene(), label);
		}
		return null;
	}

	private static void snapshot(Scene scene, String label) {
		List<Node> hidden = maskFaces ? hideFaceImages(scene) : List.of();
		try {
			WritableImage image = scene.snapshot(null);
			File file = out.resolve(String.format("%03d-%s.png", ++snapshotCount, label)).toFile();
			ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
			log.println(stamp() + "SNAPSHOT " + file.getName());
		} catch (IOException | RuntimeException e) {
			log.println(stamp() + "snapshot failed " + e);
		} finally {
			hidden.forEach(n -> n.setOpacity(1));
		}
	}

	/**
	 * Makes every large image view (camera preview, captured face and document thumbnails; icons are smaller than
	 * {@link #MASK_MIN_SIDE}) and every HTML page view of the scene transparent and returns them, so that snapshots
	 * meant for publication show no face. The scene is drawn again at once; nothing else changes.
	 */
	private static List<Node> hideFaceImages(Scene scene) {
		List<Node> all = new ArrayList<>();
		collect(scene.getRoot(), all);
		List<Node> hidden = new ArrayList<>();
		for (Node n : all) {
			// Preview, acknowledgement and EOD detail pages are HTML (WebView) with the resident's photo inside.
			if ("WebView".equals(n.getClass().getSimpleName()) && shown(n) && n.getOpacity() > 0) {
				n.setOpacity(0);
				hidden.add(n);
				continue;
			}
			if (n instanceof ImageView && shown(n) && n.getOpacity() > 0) {
				Bounds b = n.getBoundsInLocal();
				if (b.getWidth() >= MASK_MIN_SIDE && b.getHeight() >= MASK_MIN_SIDE) {
					n.setOpacity(0);
					hidden.add(n);
				}
			}
		}
		return hidden;
	}

	// ---------------------------------------------------------------------------------------------- plumbing

	private static final class AbortException extends Exception {
		private static final long serialVersionUID = 1L;

		AbortException(String message) {
			super(message);
		}
	}

	private static void require(boolean ok, String message) throws AbortException {
		if (!ok) {
			onFxQuiet(() -> snapshotAll("fail"));
			throw new AbortException(message);
		}
	}

	private static void result(boolean ok, String what) throws AbortException {
		if (ok) {
			passed++;
			log.println(stamp() + "RESULT PASS " + what);
		} else {
			failed++;
			log.println(stamp() + "RESULT FAIL " + what);
			onFxQuiet(() -> snapshotAll("fail"));
			if (abortOnFail) {
				throw new AbortException(what);
			}
		}
	}

	private static Pattern regex(String rest) {
		String r = rest.trim();
		String n = lastNumber(r);
		if (n != null) {
			r = r.substring(0, r.length() - n.length()).trim();
		}
		return Pattern.compile(r);
	}

	/** The last token when it is a plain number (a timeout in seconds), else null. */
	private static String lastNumber(String rest) {
		String[] t = rest.trim().split("\\s+");
		return t.length > 1 && t[t.length - 1].matches("\\d+") ? t[t.length - 1] : null;
	}

	/** The selector part of "selector [seconds]"; text selectors may contain spaces. */
	private static String selectorOf(String rest) {
		String r = rest.trim();
		String n = lastNumber(r);
		return n == null ? r : r.substring(0, r.length() - n.length()).trim();
	}

	private static long seconds(String value, long fallback) {
		return value == null || !value.trim().matches("\\d+") ? fallback : Long.parseLong(value.trim());
	}

	private static String stamp() {
		return LocalTime.now().withNano(0) + " ";
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

	private interface FxCall<T> {
		T call() throws Exception;
	}

	private static void onFxQuiet(FxCall<?> call) {
		try {
			if (fxStarted()) {
				onFx(call);
			}
		} catch (Exception ignored) {
			// best effort
		}
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
