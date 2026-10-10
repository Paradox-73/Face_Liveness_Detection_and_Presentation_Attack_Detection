/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mutable state of one driver run (log, output folder, counters, switches set by flow commands) and the two
 * ways a step reports its outcome: {@link #require} aborts, {@link #result} records PASS or FAIL.
 */
final class FlowContext {

	static final long DEFAULT_WAIT_SECONDS = 30;
	static final long DEFAULT_REACTION_MS = 500;

	final FxNodes fx = new FxNodes(this);
	final List<String> alerts = new CopyOnWriteArrayList<>();
	final Map<String, String> variables = new java.util.LinkedHashMap<>();

	PrintStream log;
	Path scriptDir = Path.of(".");
	Path out;
	int snapshotCount;
	boolean abortOnFail;
	int passed;
	int failed;
	volatile boolean maskFaces;
	volatile boolean autoClose = true;
	volatile String followPrefix;
	volatile String currentSource = "";
	volatile long reactionMs = DEFAULT_REACTION_MS;
	volatile int alertMark;

	/** A step that cannot be done; ends the flow (or only the current {@code section}). */
	static final class AbortException extends Exception {
		private static final long serialVersionUID = 1L;

		AbortException(String message) {
			super(message);
		}
	}

	/** Writes one line to {@code driver.log}, starting with the time of day. */
	void logLine(String text) {
		log.println(stamp() + text);
	}

	static String stamp() {
		return LocalTime.now().withNano(0) + " ";
	}

	void require(boolean ok, String message) throws AbortException {
		if (!ok) {
			fx.onFxQuiet(() -> fx.snapshotAll("fail"));
			throw new AbortException(message);
		}
	}

	void result(boolean ok, String what) throws AbortException {
		if (ok) {
			passed++;
			logLine("RESULT PASS " + what);
		} else {
			failed++;
			logLine("RESULT FAIL " + what);
			fx.onFxQuiet(() -> fx.snapshotAll("fail"));
			if (abortOnFail) {
				throw new AbortException(what);
			}
		}
	}
}
