/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;

/**
 * Reads flow files and runs their commands. Each command name maps to one handler in {@link #handlers}; the list of
 * commands is documented on {@link DesktopFlowDriver}.
 */
final class FlowInterpreter {

	private static final String LIVE_PASS_TEXT = "Good, face captured successfully";
	private static final int MAX_LIVE_ATTEMPTS = 3;
	/** A local port nothing listens on (the discard port), used as the proxy of a cut network. */
	private static final String CLOSED_PORT = "9";

	/** A command: gets the text after the command name; returns false when the flow must stop ({@code exit}). */
	private interface Handler {
		boolean run(String rest) throws Exception;
	}

	/** A command that always lets the flow go on. */
	private interface Step {
		void run(String rest) throws Exception;
	}

	private final FlowContext c;
	private final FxNodes fx;
	private final FlowHttp http;
	private final FlowDb db;
	private final PromptFollower follower;
	private final Map<String, Handler> handlers = new HashMap<>();

	FlowInterpreter(FlowContext c, FlowHttp http, FlowDb db, PromptFollower follower) {
		this.c = c;
		this.fx = c.fx;
		this.http = http;
		this.db = db;
		this.follower = follower;
		register();
	}

	private void step(String name, Step step) {
		handlers.put(name, rest -> {
			step.run(rest);
			return true;
		});
	}

	private void register() {
		step("start", rest -> startClient());
		step("wait-login", rest -> c.require(fx.waitFor(() -> fx.find("#userId") != null, seconds(rest, 300)),
				"login screen not shown"));
		step("login", rest -> login(rest.split("\\s+")));
		step("click", rest -> fx.click(selectorOf(rest), seconds(lastNumber(rest), FlowContext.DEFAULT_WAIT_SECONDS)));
		step("fill", rest -> {
			String[] a = rest.split("\\s+", 2);
			fx.fill(a[0], a.length > 1 ? a[1] : "");
		});
		step("select", rest -> {
			String[] a = rest.split("\\s+", 2);
			fx.select(a[0], a[1]);
		});
		step("check", rest -> {
			String[] a = rest.split("\\s+", 2);
			fx.check(a[0], a.length < 2 || Boolean.parseBoolean(a[1]));
		});
		step("wait", rest -> c.require(
				fx.waitText(regex(rest), seconds(lastNumber(rest), FlowContext.DEFAULT_WAIT_SECONDS), true),
				"text not shown: " + rest));
		step("wait-gone", rest -> c.require(
				fx.waitText(regex(rest), seconds(lastNumber(rest), FlowContext.DEFAULT_WAIT_SECONDS), false),
				"text still shown: " + rest));
		step("wait-node", rest -> {
			String sel = selectorOf(rest);
			c.require(fx.waitFor(() -> fx.shownNode(sel) != null,
					seconds(lastNumber(rest), FlowContext.DEFAULT_WAIT_SECONDS)), "node not shown: " + sel);
		});
		step("expect", rest -> c.result(
				fx.waitText(regex(rest), seconds(lastNumber(rest), FlowContext.DEFAULT_WAIT_SECONDS), true),
				"text " + rest));
		step("expect-live", rest -> expectLive(seconds(rest.trim().isEmpty() ? null : rest.trim(), 90)));
		step("sleep", rest -> Thread.sleep(Long.parseLong(rest.trim())));
		step("network", rest -> network("on".equalsIgnoreCase(rest.trim())));
		step("mask", rest -> c.maskFaces = "on".equalsIgnoreCase(rest.trim()));
		step("autoclose", rest -> c.autoClose = "on".equalsIgnoreCase(rest.trim()));
		step("alert-close", rest -> fx.onFx(fx::closeAlert));
		step("mark", rest -> c.alertMark = c.alerts.size());
		step("expect-alert", rest -> c.result(
				fx.waitAlert(regex(rest), seconds(lastNumber(rest), FlowContext.DEFAULT_WAIT_SECONDS)), "alert " + rest));
		step("wait-alert", rest -> c.require(
				fx.waitAlert(regex(rest), seconds(lastNumber(rest), FlowContext.DEFAULT_WAIT_SECONDS)),
				"alert not shown: " + rest));
		step("menu", rest -> c.require(Boolean.TRUE.equals(fx.onFx(() -> fx.fireMenuItem(rest.trim()))),
				"no menu item " + rest));
		step("dialog", rest -> c.require(Boolean.TRUE.equals(fx.onFx(() -> fx.pressDialog(rest.trim()))),
				"no dialog button " + rest));
		step("snap", rest -> fx.onFx(() -> fx.snapshotAll(rest.trim())));
		step("dump", rest -> fx.dump(rest.trim()));
		step("set", rest -> {
			String[] a = rest.trim().split("\\s+", 2);
			c.variables.put(a[0], a.length > 1 ? a[1] : "");
		});
		handlers.put("include", rest -> runFile(c.scriptDir.resolve(rest.trim())));
		handlers.put("section", rest -> section(c.scriptDir.resolve(rest.trim())));
		step("http", rest -> {
			String[] a = rest.trim().split("\\s+", 3);
			http.http(a[0], a[1], a.length > 2 ? a[2] : "");
		});
		step("mds", rest -> {
			String[] a = rest.split("\\s+", 2);
			http.mds(a[0], a.length > 1 ? a[1] : "{}");
		});
		step("source", rest -> http.source(rest.trim()));
		step("follow", rest -> {
			String[] a = rest.trim().split("\\s+");
			c.followPrefix = "off".equalsIgnoreCase(a[0]) ? null : a[0];
			c.reactionMs = a.length > 1 ? Long.parseLong(a[1]) : FlowContext.DEFAULT_REACTION_MS;
			follower.reset();
		});
		step("sql", rest -> db.sql(rest, -1));
		step("expect-sql", rest -> {
			String[] a = rest.split("\\s+", 2);
			db.sql(a[1], Integer.parseInt(a[0]));
		});
		step("expect-no-rows", rest -> db.sql(rest, FlowDb.NO_ROWS));
		handlers.put("each-row", this::eachRow);
		handlers.put("if-node", this::ifNode);
		step("label", rest -> c.logLine("==== " + rest));
		step("abort-on-fail", rest -> c.abortOnFail = "on".equalsIgnoreCase(rest.trim()));
		handlers.put("exit", rest -> false);
	}

	// ---------------------------------------------------------------------------------------------- running

	/** Runs a flow file; {@code include} lines inside it are resolved against its folder. */
	boolean runFile(Path file) throws Exception {
		Path previous = c.scriptDir;
		c.scriptDir = file.toAbsolutePath().getParent();
		try {
			return runScript(Files.readAllLines(file, StandardCharsets.UTF_8));
		} finally {
			c.scriptDir = previous;
		}
	}

	/** Runs script lines; returns false after {@code exit}. */
	boolean runScript(List<String> lines) throws Exception {
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			line = substitute(line);
			c.logLine("> " + line);
			if (!execute(line)) {
				return false;
			}
		}
		return true;
	}

	/** Replaces {@code ${NAME}} with the value given by {@code set NAME value}. */
	private String substitute(String line) {
		String out = line;
		for (Map.Entry<String, String> v : c.variables.entrySet()) {
			out = out.replace("${" + v.getKey() + "}", v.getValue());
		}
		return out;
	}

	private boolean execute(String line) throws Exception {
		String[] p = line.split("\\s+", 2);
		String cmd = p[0].toLowerCase(Locale.ROOT);
		String rest = p.length > 1 ? p[1] : "";
		Handler handler = handlers.get(cmd);
		if (handler == null) {
			c.logLine("unknown command " + cmd);
			return true;
		}
		return handler.run(rest);
	}

	/**
	 * {@code each-row <#table> <command...>}: select every row of a table in turn, last row first, and run the command
	 * on it. Last to first, because a screen may move a handled row to the end (the EOD approval table does).
	 */
	private boolean eachRow(String rest) throws Exception {
		String[] a = rest.trim().split("\\s+", 2);
		Integer rows = fx.onFx(() -> fx.shownNode(a[0]) instanceof TableView
				? ((TableView<?>) fx.shownNode(a[0])).getItems().size() : 0);
		for (int i = rows == null ? -1 : rows - 1; i >= 0; i--) {
			final int row = i;
			fx.onFx(() -> {
				((TableView<?>) fx.shownNode(a[0])).getSelectionModel().clearAndSelect(row);
				return null;
			});
			c.logLine("  row " + row);
			if (!execute(a[1])) {
				return false;
			}
		}
		return true;
	}

	/** {@code if-node <#fxId> <command...>}: run the command only when that node is shown (optional screen parts). */
	private boolean ifNode(String rest) throws Exception {
		String[] a = rest.trim().split("\\s+", 2);
		if (Boolean.TRUE.equals(fx.onFx(() -> fx.shownNode(a[0]) != null))) {
			return execute(a[1]);
		}
		c.logLine("  skipped, " + a[0] + " not shown");
		return true;
	}

	/**
	 * Runs a flow file as an independent section: when one of its steps cannot be done (an abort), the failure is
	 * counted, the client is brought back to its home screen and the next section runs.
	 */
	private boolean section(Path file) throws Exception {
		try {
			return runFile(file);
		} catch (FlowContext.AbortException e) {
			c.failed++;
			c.logLine("RESULT FAIL section " + file.getFileName() + " stopped: " + e.getMessage());
			recover();
			return true;
		}
	}

	/** Closes open liveness windows, dialogs and alerts, restores the network and goes to the home screen. */
	private void recover() throws Exception {
		c.followPrefix = null;
		network(true);
		for (int i = 0; i < 3; i++) {
			fx.onFxQuiet(() -> {
				Node close = fx.shownNode("#cancelButton");
				if (close instanceof ButtonBase && !close.isDisabled()) {
					((ButtonBase) close).fire();
				}
				fx.pressDialog("CANCEL");
				fx.closeAlert();
				return null;
			});
			Thread.sleep(700);
		}
		if (Boolean.TRUE.equals(fx.onFx(() -> fx.clickable("#homeImgView") != null))) {
			fx.click("#homeImgView", 5);
			Thread.sleep(1500);
			if (Boolean.TRUE.equals(fx.onFx(() -> fx.shownNode("#confirm") != null))) {
				fx.click("#confirm", 5);
			}
		}
		c.logLine("  recovered to the home screen");
	}

	// ---------------------------------------------------------------------------------------------- client

	private void startClient() {
		Thread client = new Thread(() -> {
			try {
				Class.forName("io.mosip.registration.controller.Initialization").getMethod("main", String[].class)
						.invoke(null, (Object) new String[0]);
			} catch (Throwable t) {
				t.printStackTrace(c.log);
			}
		}, "registration-client");
		client.start();
	}

	private void login(String[] a) throws Exception {
		String user = a[0];
		String password = a[1];
		c.require(fx.waitFor(() -> fx.shownNode("#userId") != null, 300), "user ID field not shown");
		fx.onFx(() -> {
			((TextInputControl) fx.shownNode("#userId")).setText(user);
			((ButtonBase) fx.find("#sub1")).fire();
			return null;
		});
		c.require(fx.waitFor(() -> fx.shownNode("#password") != null, 60), "password field not shown");
		fx.onFx(() -> {
			((TextInputControl) fx.shownNode("#password")).setText(password);
			((ButtonBase) fx.find("#sub2")).fire();
			return null;
		});
	}

	/**
	 * Waits for a bona fide liveness check to pass, pressing Retry when an attempt fails (as an operator would), and
	 * records one result with the attempt that passed. Frame packs of real people contain spontaneous blinks, and a
	 * loaded machine can make three frames in a row slower than the engine's limit; both end one attempt, not the
	 * flow. The reason of every failed attempt is logged.
	 */
	private void expectLive(long seconds) throws Exception {
		long end = System.currentTimeMillis() + seconds * 1000;
		int attempt = 1;
		while (System.currentTimeMillis() < end) {
			String state = fx.onFx(fx::allText);
			if (state != null && state.contains(LIVE_PASS_TEXT)) {
				c.result(true, "live face passed on attempt " + attempt);
				return;
			}
			if (Boolean.TRUE.equals(fx.onFx(() -> fx.shownNode("#retryButton") != null))) {
				c.logLine("  attempt " + attempt + " failed: " + fx.onFx(() -> fx.nodeText("#promptLabel")));
				if (attempt >= MAX_LIVE_ATTEMPTS) {
					break;
				}
				fx.click("#retryButton", 5);
				attempt++;
				Thread.sleep(1000);
			}
			Thread.sleep(250);
		}
		c.result(false, "live face did not pass in " + attempt + " attempt(s)");
	}

	/**
	 * Cuts or restores the client's connection to the server. The client calls MockSync over HTTPS with
	 * {@code HttpURLConnection} (Spring's SimpleClientHttpRequestFactory), which reads the proxy system properties
	 * at every connection: "off" sends that traffic to a closed local port, so every server call fails exactly as
	 * when the server is down, while MockSync keeps serving other clients. MockMDS is called over plain HTTP with
	 * Apache HttpClient, which ignores these properties, so the devices keep working.
	 */
	private void network(boolean on) {
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
		c.logLine("  network " + (on ? "restored" : "cut (server calls fail)"));
	}

	// ---------------------------------------------------------------------------------------------- arguments

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
}
