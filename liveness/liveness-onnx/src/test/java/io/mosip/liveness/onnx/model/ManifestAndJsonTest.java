/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

import io.mosip.liveness.onnx.TestSupport;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestAndJsonTest {

    private static final String SHA = "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0";

    @Test
    void jsonReadsEveryValueType() {
        Object v = Json.parse(" {\"a\": [1, -2.5e1, true, false, null, \"x\\\"\\u0041\\n\"], \"b\": {}} ");
        Map<?, ?> m = (Map<?, ?>) v;
        List<?> a = (List<?>) m.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(-25.0, a.get(1));
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals(Boolean.FALSE, a.get(3));
        assertNull(a.get(4));
        assertEquals("x\"A\n", a.get(5));
        assertTrue(((Map<?, ?>) m.get("b")).isEmpty());
    }

    @Test
    void jsonRejectsMalformedInput() {
        for (String bad : Arrays.asList("", "{", "{\"a\":1,}", "[1 2]", "{\"a\":1}x", "{\"a\":1,\"a\":2}", "\"\\q\"",
                "tru", "-", "1e999", "\"a\u0001\"", "{1:2}")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append('[');
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void manifestRoundTripsThroughJson() {
        ModelEntry e = ModelEntry.of("minifasnet-v2", "MiniFASNetV2.onnx", "2.7_80x80", SHA.toUpperCase(), 1743581,
                "Apache-2.0", "https://example.org/x@abc", Collections.singletonMap("cropScale", "2.7"));
        ModelManifest m = ModelManifest.of("bundle", "1.2.3", Collections.singletonList(e));
        String json = m.toJson();
        ModelManifest back = ModelManifest.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(json, back.toJson());
        assertEquals("1.2.3", back.version().toString());
        ModelEntry b = back.entry("minifasnet-v2").get();
        assertEquals(e, b);
        assertEquals(SHA, b.sha256());
        assertEquals(2.7, b.doubleParam("cropScale", 0), 1e-12);
        assertEquals(9.0, b.doubleParam("missing", 9.0), 1e-12);
        assertEquals("minifasnet-v2", b.toModelInfo().id());
        assertEquals(SHA.substring(0, 8), b.toModelInfo().sha256Prefix());
        assertFalse(back.entry("other").isPresent());
    }

    @Test
    void manifestNumbersInParamsAreAccepted() {
        String json = "{\"schema\":1,\"bundle\":\"b\",\"version\":\"1.0.0\",\"models\":[{\"id\":\"m\",\"file\":\"m.onnx\","
                + "\"version\":\"1\",\"sha256\":\"" + SHA + "\",\"bytes\":10,\"licence\":\"MIT\",\"source\":\"s\","
                + "\"params\":{\"cropScale\":4.0}}],\"extra\":\"ignored\"}";
        ModelManifest m = ModelManifest.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(4.0, m.entry("m").get().doubleParam("cropScale", 0), 1e-12);
    }

    @Test
    void manifestFieldsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> entry("../evil.onnx", SHA));
        assertThrows(IllegalArgumentException.class, () -> entry("/abs.onnx", SHA));
        assertThrows(IllegalArgumentException.class, () -> entry("a\\b.onnx", SHA));
        assertThrows(IllegalArgumentException.class, () -> entry("ok.onnx", "abc"));
        assertThrows(IllegalArgumentException.class, () -> ModelEntry.of("Bad Id", "f.onnx", "1", SHA, 1, "MIT", "s",
                Collections.<String, String>emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> ModelManifest.of("b", "1.0", Collections.<ModelEntry>emptyList()));
        assertThrows(IllegalArgumentException.class, () -> ModelManifest.of("b", "1.0.0",
                Arrays.asList(entry("a.onnx", SHA), entry("a.onnx", SHA))));
        assertThrows(IllegalArgumentException.class,
                () -> ModelManifest.parse("{\"schema\":2,\"bundle\":\"b\",\"version\":\"1.0.0\",\"models\":[]}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> entry("ok.onnx", SHA).withDigest(SHA, 0));
    }

    @Test
    void semanticVersionsCompareLikeSemver() {
        assertTrue(SemanticVersion.parse("1.0.0").isAtLeast(SemanticVersion.parse("1.0.0")));
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.9.9")) > 0);
        assertTrue(SemanticVersion.parse("1.0.0-rc1").compareTo(SemanticVersion.parse("1.0.0")) < 0);
        assertTrue(SemanticVersion.parse("1.0.0-alpha").compareTo(SemanticVersion.parse("1.0.0-alpha.1")) < 0);
        assertTrue(SemanticVersion.parse("1.0.0-2").compareTo(SemanticVersion.parse("1.0.0-10")) < 0);
        assertTrue(SemanticVersion.parse("1.0.0-9").compareTo(SemanticVersion.parse("1.0.0-a")) < 0);
        assertFalse(SemanticVersion.parse("0.9.9").isAtLeast(SemanticVersion.parse("1.0.0")));
        assertEquals(SemanticVersion.parse("2.0.0"), SemanticVersion.parse("2.0.0"));
        assertEquals(SemanticVersion.parse("2.0.0").hashCode(), SemanticVersion.parse("2.0.0").hashCode());
        for (String bad : Arrays.asList("1.0", "1.0.0.0", "01.0.0", "a.b.c", "1.0.0-", "1.0.0+build", "")) {
            assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(bad), bad);
        }
    }

    @Test
    void engineParamsRoundTripAndKeepTheirOrder() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("planarity.min_residual", "0.03");
        params.put("acquire.consecutive_frames", "6");
        ModelManifest m = ModelManifest.of("bundle", "1.2.3", Collections.singletonList(entry("m.onnx", SHA)), params);
        String json = m.toJson();
        assertTrue(json.endsWith("  ],\n  \"engineParams\": {\"planarity.min_residual\": \"0.03\", "
                + "\"acquire.consecutive_frames\": \"6\"}\n}\n"), json);
        ModelManifest back = ModelManifest.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(params, back.engineParams());
        assertEquals(Arrays.asList("planarity.min_residual", "acquire.consecutive_frames"),
                Arrays.asList(back.engineParams().keySet().toArray()));
        assertEquals(json, back.toJson());
        assertEquals(params, back.withModels(back.models()).engineParams(), "the signer's update keeps them");
    }

    @Test
    void engineParamsAcceptNumbersAndRejectOtherTypes() {
        String head = "{\"schema\":1,\"bundle\":\"b\",\"version\":\"1.0.0\",\"models\":[],\"engineParams\":";
        ModelManifest m = ModelManifest.parse((head + "{\"a.b\":0.25,\"c.d\":3,\"e.f\":\"1e-3\"}}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("0.25", m.engineParams().get("a.b"));
        assertEquals("3", m.engineParams().get("c.d"));
        assertEquals("1e-3", m.engineParams().get("e.f"));
        for (String bad : Arrays.asList("{\"a\":true}", "{\"a\":null}", "{\"a\":[1]}", "[]", "{\"a\":\" \"}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ModelManifest.parse((head + bad + "}").getBytes(StandardCharsets.UTF_8)), bad);
        }
    }

    @Test
    void aManifestWithoutEngineParamsKeepsItsExactBytes() throws Exception {
        ModelManifest m = ModelManifest.of("bundle", "1.0.0", Collections.singletonList(entry("m.onnx", SHA)));
        assertTrue(m.engineParams().isEmpty());
        assertFalse(m.toJson().contains("engineParams"));
        byte[] repository = Files.readAllBytes(new File(
                TestSupport.modelsDir(), ModelRegistry.MANIFEST_FILE).toPath());
        String text = new String(repository, StandardCharsets.UTF_8);
        assertEquals(text, ModelManifest.parse(repository).toJson(), "the signed repository manifest is unchanged");
    }

    private static ModelEntry entry(String file, String sha) {
        return ModelEntry.of("m", file, "1", sha, 1, "MIT", "s", Collections.<String, String>emptyMap());
    }
}
