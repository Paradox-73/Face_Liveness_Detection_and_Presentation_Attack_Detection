/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.nio.file.Files;
import java.util.regex.Pattern;

/** Switches the MockMDS face source to follow the liveness prompt on screen ({@code follow} command). */
final class PromptFollower {

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

	private final FlowContext c;
	private final FlowHttp http;
	private String pendingAction;
	private long pendingSince;
	/** The action whose missing pack was logged last, so a prompt that stays on screen is logged once. */
	private String missingLogged;

	PromptFollower(FlowContext c, FlowHttp http) {
		this.c = c;
		this.http = http;
	}

	/** Forgets a reaction in progress; called when {@code follow} changes its settings. */
	void reset() {
		pendingAction = null;
	}

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
		for (String[] cs : cases) {
			String got = packSuffix(cs[0]);
			boolean ok = got.equals(cs[1]);
			if (!ok) {
				failures++;
			}
			System.out.println((ok ? "PASS " : "FAIL ") + "'" + cs[0] + "' -> " + got + (ok ? "" : " (want " + cs[1] + ")"));
		}
		System.out.println("prompt checks: " + (cases.length - failures) + "/" + cases.length + " passed");
		return failures == 0 ? 0 : 1;
	}

	void follow(String prefix, String state) throws Exception {
		String suffix = packSuffix(state);
		String wanted = prefix + "-" + suffix;
		if (wanted.equals(c.currentSource)) {
			pendingAction = null;
			return;
		}
		if (!Files.isDirectory(FlowHttp.PACKS.resolve(wanted))) {
			// The face has no recording of this action: the challenge cannot be answered and the attempt fails.
			if (!wanted.equals(missingLogged)) {
				missingLogged = wanted;
				c.logLine("FOLLOW no pack for " + suffix.replace('-', '_') + " (" + wanted
						+ " missing under " + FlowHttp.PACKS + ")");
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
			if (now - pendingSince < c.reactionMs) {
				return;
			}
		}
		pendingAction = null;
		http.source(wanted);
	}
}
