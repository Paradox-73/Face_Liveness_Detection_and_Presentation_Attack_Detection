/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 * java -cp "lib/*;&lt;driverClasses&gt;" DesktopFlowDriver &lt;outDir&gt; &lt;script&gt; [&lt;script&gt;...]
 * java -cp "lib/*;&lt;driverClasses&gt;" DesktopFlowDriver &lt;outDir&gt; --interactive &lt;commandDir&gt;
 * java -cp "lib/*;&lt;driverClasses&gt;" DesktopFlowDriver --check-prompts
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

	/**
	 * Runs the scripts, or the interactive command loop.
	 *
	 * @param args output directory, then script files or {@code --interactive <commandDir>}
	 * @throws Exception on I/O failure
	 */
	public static void main(String[] args) throws Exception {
		if (args.length == 1 && "--check-prompts".equals(args[0])) {
			System.exit(PromptFollower.checkPrompts());
		}
		FlowContext c = new FlowContext();
		c.out = Path.of(args[0]);
		Files.createDirectories(c.out);
		c.log = new PrintStream(Files.newOutputStream(c.out.resolve("driver.log")), true, "UTF-8");
		FlowHttp http = new FlowHttp(c);
		PromptFollower follower = new PromptFollower(c, http);
		FlowInterpreter flow = new FlowInterpreter(c, http, new FlowDb(c), follower);
		new FlowWatcher(c, follower).start();
		int exit = 0;
		try {
			if (args.length > 2 && "--interactive".equals(args[1])) {
				interactive(c, flow, Path.of(args[2]));
			} else {
				for (int i = 1; i < args.length; i++) {
					flow.runFile(Path.of(args[i]));
				}
			}
		} catch (FlowContext.AbortException e) {
			c.logLine("ABORTED " + e.getMessage());
			exit = 2;
		} catch (Exception e) {
			c.logLine("ERROR " + e);
			e.printStackTrace(c.log);
			exit = 3;
		}
		c.logLine("SUMMARY passed=" + c.passed + " failed=" + c.failed);
		c.log.println("DONE");
		System.exit(exit != 0 ? exit : c.failed > 0 ? 1 : 0);
	}

	private static void interactive(FlowContext c, FlowInterpreter flow, Path commandDir) throws Exception {
		Files.createDirectories(commandDir);
		Path next = commandDir.resolve("next.cmd");
		c.logLine("INTERACTIVE waiting for " + next);
		while (true) {
			if (Files.exists(next)) {
				List<String> lines = Files.readAllLines(next, StandardCharsets.UTF_8);
				Files.delete(next);
				try {
					if (!flow.runScript(lines)) {
						return;
					}
				} catch (FlowContext.AbortException e) {
					c.logLine("ABORTED " + e.getMessage());
				}
				c.logLine("READY");
			}
			Thread.sleep(300);
		}
	}
}
