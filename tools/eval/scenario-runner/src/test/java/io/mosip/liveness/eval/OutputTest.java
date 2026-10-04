/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON writer, latency statistics, CLI options and the committed scenario files. */
class OutputTest {

    /** The repository root, from the module folder that surefire runs in. */
    private static final Path REPO = Paths.get("").toAbsolutePath().getParent().getParent().getParent();

    @Test
    void jsonWritesScalarsCollectionsAndEscapes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a\"b\\c\nd\u0001");
        m.put("i", 3);
        m.put("d", 2.5);
        m.put("whole", 4.0);
        m.put("nan", Double.NaN);
        m.put("b", true);
        m.put("n", null);
        m.put("e", Outcome.ACCEPT);
        m.put("l", Arrays.asList(1, "x"));
        assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\\u0001\",\"i\":3,\"d\":2.5,\"whole\":4,\"nan\":null,\"b\":true,"
                + "\"n\":null,\"e\":\"ACCEPT\",\"l\":[1,\"x\"]}", Json.write(m));
        assertThrows(IllegalArgumentException.class, () -> Json.write(new byte[1]));
    }

    @Test
    void latencyPercentilesAreNearestRank() {
        LatencyStats s = new LatencyStats();
        for (int i = 1; i <= 20; i++) {
            s.add(i);
        }
        assertEquals(20, s.count());
        assertEquals(10.5, s.mean(), 1e-9);
        assertEquals(10.0, s.percentile(50), 1e-9);
        assertEquals(19.0, s.percentile(95), 1e-9);
        assertEquals(20.0, s.max(), 1e-9);
        LatencyStats all = new LatencyStats();
        all.addAll(s);
        all.addAll(s);
        assertEquals(40, all.count());
        assertTrue(Double.isNaN(new LatencyStats().mean()));
        assertThrows(IllegalArgumentException.class, () -> s.percentile(0));
        assertEquals(1.23, LatencyStats.round(1.2349), 1e-9);
    }

    @Test
    void cliOptions() {
        ScenarioRunnerCli.Options o = ScenarioRunnerCli.Options.parse(new String[]{"run", "--out", "o", "--packs", "p",
            "--config", "max_retries=1", "--engine-param", "planarity.min_residual=0.03", "--filter", "^atk-",
            "--threads", "1", "--no-fail"});
        assertEquals("run", o.command);
        assertEquals(Arrays.asList("eval/scenarios"), o.scenarioPaths);
        assertEquals("1", o.config.get("mosip.registration.liveness.max_retries"));
        assertEquals("0.03", o.engineParams.get("planarity.min_residual"));
        assertTrue(o.filter.matcher("atk-s2").find());
        assertTrue(o.noFail);
        assertEquals(Paths.get("p"), o.packsRoot());
        assertThrows(IllegalArgumentException.class, () -> ScenarioRunnerCli.Options.parse(new String[]{"run"}));
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioRunnerCli.Options.parse(new String[]{"run", "--out", "o", "--bogus"}));
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioRunnerCli.Options.parse(new String[]{"run", "--out", "o", "--config", "novalue"}));
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioRunnerCli.Options.parse(new String[]{"run", "--out", "o", "--threads", "0"}));
    }

    @Test
    void everyCommittedScenarioLoadsAndNamesOnlyManifestedPacks() throws IOException {
        Path scenarios = REPO.resolve("eval/scenarios");
        Path manifests = REPO.resolve("eval/packs");
        assertTrue(Files.isDirectory(scenarios), "run the tests from tools/eval/scenario-runner: " + scenarios);
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(scenarios, "*.yaml")) {
            for (Path f : ds) {
                Scenario sc = ScenarioLoader.load(f);
                assertEquals(f.getFileName().toString(), sc.id() + ".yaml", "file name must match the id");
                assertFalse(ids.contains(sc.id()));
                ids.add(sc.id());
                for (String pack : sc.stream().packIds()) {
                    assertTrue(Files.isRegularFile(manifests.resolve(pack + ".yaml")),
                            sc.id() + " uses pack " + pack + " without a manifest in eval/packs");
                }
                if (sc.kind() == Scenario.Kind.ATTACK) {
                    assertTrue(sc.species().startsWith("SYN-"), sc.id() + ": synthetic species must say so");
                }
            }
        }
        assertTrue(ids.size() >= 30, "expected the full scenario suite, found " + ids.size());
    }
}
