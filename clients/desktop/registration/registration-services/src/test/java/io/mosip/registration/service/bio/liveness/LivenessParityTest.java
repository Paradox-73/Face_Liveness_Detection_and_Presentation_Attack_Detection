/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.interop.ParityCase;
import io.mosip.liveness.core.interop.ParityCheck;
import io.mosip.liveness.core.interop.ParityFiles;
import io.mosip.liveness.core.interop.ParityReplay;
import io.mosip.registration.audit.AuditManagerService;

/**
 * Cross-client parity (bonus B4): replays the cases of {@code eval/interop/liveness-parity.json} through the desktop
 * client's own engine loader ({@link LivenessEngineRegistry}, ONNX Runtime from this module's dependencies), its
 * configuration classes ({@link LivenessConfig}) and its image decoder ({@link DesktopImageDecoder}), and checks the
 * decisions, reason codes and scores against the recorded ones. The Android client runs the same cases in
 * {@code livenessmanager} {@code LivenessParityTest}.
 *
 * <p>Committed cases (MockMDS images) always run; frame-pack cases run where {@code run/frame-packs} is present
 * (or {@code LIVENESS_FRAME_PACKS} names it). With {@code -Dliveness.parity.update=true} the test records the
 * desktop results as the expected ones instead of checking them.
 */
public class LivenessParityTest {

	private static final String UPDATE_PROPERTY = "liveness.parity.update";

	private static final ObjectMapper JSON = new ObjectMapper();
	private static LivenessEngineRegistry registry;
	private static LivenessEngine engine;
	private static ParityFiles files;

	@BeforeClass
	public static void loadEngine() throws Exception {
		files = ParityFiles.locate();
		registry = new LivenessEngineRegistry(LivenessConfig.of(new LinkedHashMap<>()), new DesktopImageDecoder(),
				new DesktopAuditSink(Mockito.mock(AuditManagerService.class), Runnable::run),
				new DesktopDiagnosticSink());
		engine = registry.engine();
	}

	@AfterClass
	public static void closeEngine() {
		if (registry != null) {
			registry.close();
		}
	}

	@Test
	public void desktopDecisionsMatchTheParityFile() throws Exception {
		Map<String, Object> golden = JSON.readValue(files.golden(), new TypeReference<LinkedHashMap<String, Object>>() {
		});
		int tolerance = ((Number) golden.get("scoreTolerance")).intValue();
		boolean update = Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY))
				|| Boolean.parseBoolean(System.getenv("LIVENESS_PARITY_UPDATE"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> cases = (List<Map<String, Object>>) golden.get("cases");
		DesktopImageDecoder decoder = new DesktopImageDecoder();
		List<String> failures = new ArrayList<>();
		int committed = 0;
		int ran = 0;
		for (Map<String, Object> json : cases) {
			ParityCase c = ParityCase.fromJson(json);
			if (!files.available(c)) {
				System.out.println("parity " + c.id() + ": skipped, frame packs not found in " + files.packs());
				continue;
			}
			Map<String, Object> config = new LinkedHashMap<>(c.config());
			Map<String, Object> actual = ParityReplay.run(c, files, engine,
					LivenessConfig.of(config).policy(c.workflow()), decoder);
			ran++;
			committed += c.isCommitted() ? 1 : 0;
			if (update) {
				json.put("expected", actual);
				System.out.println("parity " + c.id() + ": recorded " + actual.get("decision") + " "
						+ actual.get("reasonCodes"));
				continue;
			}
			if (c.expected() == null) {
				failures.add(c.id() + ": no expected result recorded");
				continue;
			}
			ParityCheck check = ParityCheck.compare(c.expected(), actual, tolerance);
			System.out.println("parity " + c.id() + ": " + actual.get("decision") + " " + actual.get("reasonCodes")
					+ ", max score difference " + check.maxScoreDifference() + (check.matches() ? "" : ", MISMATCH"));
			for (String m : check.mismatches()) {
				failures.add(c.id() + ": " + m);
			}
		}
		if (update) {
			write(files.golden(), golden, cases);
		}
		assertTrue("the committed cases always run", committed >= 1);
		System.out.println("parity desktop: " + ran + " of " + cases.size() + " cases run");
		if (!failures.isEmpty()) {
			fail("desktop results differ from " + ParityFiles.GOLDEN_PATH + ":\n" + String.join("\n", failures));
		}
	}

	/** Writes the file with one line per case, so that a re-recorded case shows as one changed line. */
	private static void write(File file, Map<String, Object> golden, List<Map<String, Object>> cases)
			throws IOException {
		StringBuilder sb = new StringBuilder("{\n");
		for (Map.Entry<String, Object> e : golden.entrySet()) {
			if (!"cases".equals(e.getKey())) {
				sb.append("  ").append(JSON.writeValueAsString(e.getKey())).append(": ")
						.append(JSON.writeValueAsString(e.getValue())).append(",\n");
			}
		}
		sb.append("  \"cases\": [\n");
		for (int i = 0; i < cases.size(); i++) {
			sb.append("    ").append(JSON.writeValueAsString(cases.get(i))).append(i + 1 < cases.size() ? ",\n" : "\n");
		}
		sb.append("  ]\n}\n");
		Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
	}
}
