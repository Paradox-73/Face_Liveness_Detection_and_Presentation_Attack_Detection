/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/** Background thread: logs every change of the visible texts, closes alerts, follows liveness prompts. */
final class FlowWatcher {

	private final FlowContext c;
	private final PromptFollower follower;

	FlowWatcher(FlowContext c, PromptFollower follower) {
		this.c = c;
		this.follower = follower;
	}

	void start() {
		FxNodes fx = c.fx;
		Thread watcher = new Thread(() -> {
			String last = "";
			while (true) {
				try {
					Thread.sleep(400);
					if (!fx.fxStarted()) {
						continue;
					}
					if (c.autoClose) {
						fx.onFx(fx::closeAlert);
					}
					String state = fx.onFx(fx::describeWindows);
					if (state != null && !state.equals(last)) {
						last = state;
						c.logLine("STATE " + state);
					}
					String prefix = c.followPrefix;
					if (prefix != null && state != null) {
						follower.follow(prefix, state);
					}
				} catch (InterruptedException e) {
					return;
				} catch (Exception e) {
					c.logLine("watcher " + e);
				}
			}
		}, "driver-watcher");
		watcher.setDaemon(true);
		watcher.start();
	}
}
