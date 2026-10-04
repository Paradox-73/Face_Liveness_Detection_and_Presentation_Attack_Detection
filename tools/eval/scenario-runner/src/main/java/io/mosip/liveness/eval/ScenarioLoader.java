package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and validates scenario files. Unknown keys are errors, so a typo cannot silently change a test.
 */
final class ScenarioLoader {

    /** Default replay frame rate: the MockMDS default stream rate. */
    static final int DEFAULT_FPS = 15;
    /** Default stream time one attempt may use. */
    static final long DEFAULT_MAX_STREAM_MS = 60_000L;
    /** Default time between a prompt and the start of the action clip. */
    static final long DEFAULT_REACTION_MS = 400L;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Set<String> TOP_KEYS = new HashSet<>(Arrays.asList("id", "title", "kind", "species",
            "subject", "condition", "workflow", "seeds", "seed", "repeat", "fps", "max_stream_ms", "config",
            "engine_params", "stream", "expect", "notes"));
    private static final Set<String> STREAM_KEYS = new HashSet<>(Arrays.asList("main", "at_end", "on_prompt",
            "reaction_ms", "actions", "fixed_pack"));
    private static final Set<String> EXPECT_KEYS = new HashSet<>(Arrays.asList("outcome", "layers"));

    private ScenarioLoader() {
    }

    /**
     * Loads one scenario file.
     *
     * @param file YAML file
     * @return the scenario
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the content is invalid; the message names the file and the field
     */
    static Scenario load(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        try {
            return parse(new StringReader(text));
        } catch (IllegalArgumentException | YAMLException e) {
            throw new IllegalArgumentException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses scenario YAML.
     *
     * @param reader YAML text
     * @return the scenario
     * @throws IllegalArgumentException if the content is invalid
     */
    static Scenario parse(Reader reader) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        Map<String, Object> m = asMap(root, "scenario");
        checkKeys(m, TOP_KEYS, "scenario");
        String id = requireString(m, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be lower-case letters, digits, '.', '-' or '_': " + id);
        }
        Scenario.Kind kind = parseEnum(Scenario.Kind.class, requireString(m, "kind"), "kind");
        String species = optString(m, "species", kind == Scenario.Kind.BONA_FIDE ? "BF"
                : kind == Scenario.Kind.OTHER ? "OTHER" : null);
        if (species == null) {
            throw new IllegalArgumentException("species is required for kind attack");
        }
        String wf = optString(m, "workflow", Workflow.REGISTRATION.id());
        Workflow workflow = Workflow.fromId(wf)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow: " + wf));
        int fps = optInt(m, "fps", DEFAULT_FPS);
        if (fps < 1 || fps > 60) {
            throw new IllegalArgumentException("fps must be 1-60: " + fps);
        }
        long maxStreamMs = optInt(m, "max_stream_ms", (int) DEFAULT_MAX_STREAM_MS);
        if (maxStreamMs < 1_000) {
            throw new IllegalArgumentException("max_stream_ms must be at least 1000");
        }
        return new Scenario(id, optString(m, "title", ""), kind, species, optString(m, "subject", ""),
                optString(m, "condition", ""), workflow, seeds(m), fps, maxStreamMs, config(m), engineParams(m),
                stream(asMap(m.get("stream"), "stream")), expect(m.get("expect")));
    }

    private static List<Long> seeds(Map<String, Object> m) {
        List<Long> seeds = new ArrayList<>();
        if (m.containsKey("seeds")) {
            if (m.containsKey("seed") || m.containsKey("repeat")) {
                throw new IllegalArgumentException("use either seeds or seed/repeat");
            }
            Object v = m.get("seeds");
            if (!(v instanceof List) || ((List<?>) v).isEmpty()) {
                throw new IllegalArgumentException("seeds must be a non-empty list of integers");
            }
            for (Object o : (List<?>) v) {
                seeds.add(toLong(o, "seeds"));
            }
            return seeds;
        }
        long first = m.containsKey("seed") ? toLong(m.get("seed"), "seed") : 1L;
        int repeat = optInt(m, "repeat", 1);
        if (repeat < 1 || repeat > 1_000) {
            throw new IllegalArgumentException("repeat must be 1-1000: " + repeat);
        }
        for (int i = 0; i < repeat; i++) {
            seeds.add(first + i);
        }
        return seeds;
    }

    private static Map<String, String> config(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m.get("config") == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : asMap(m.get("config"), "config").entrySet()) {
            out.put(fullKey(e.getKey()), configValue(e.getValue(), e.getKey()));
        }
        return out;
    }

    /**
     * Expands a short configuration key.
     *
     * @param key a full key ({@code mosip.…}) or one relative to {@code mosip.registration.liveness.}
     * @return the full key
     */
    static String fullKey(String key) {
        return key.startsWith("mosip.") ? key : LivenessConfigKeys.PREFIX + key;
    }

    private static String configValue(Object v, String key) {
        if (v instanceof Boolean) {
            return ((Boolean) v) ? LivenessConfigKeys.YES : LivenessConfigKeys.NO;
        }
        if (v instanceof String || v instanceof Number) {
            return v.toString();
        }
        throw new IllegalArgumentException("config value of " + key + " must be a scalar");
    }

    private static Map<String, String> engineParams(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m.get("engine_params") == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : asMap(m.get("engine_params"), "engine_params").entrySet()) {
            if (!(e.getValue() instanceof Number)) {
                throw new IllegalArgumentException("engine_params." + e.getKey() + " must be a number");
            }
            out.put(e.getKey(), e.getValue().toString());
        }
        return out;
    }

    private static Scenario.StreamPlan stream(Map<String, Object> s) {
        checkKeys(s, STREAM_KEYS, "stream");
        String main = requireString(s, "main");
        Map<ChallengeType, String> actions = new EnumMap<>(ChallengeType.class);
        if (s.get("actions") != null) {
            for (Map.Entry<String, Object> e : asMap(s.get("actions"), "stream.actions").entrySet()) {
                ChallengeType t = parseEnum(ChallengeType.class, e.getKey(), "stream.actions");
                if (!(e.getValue() instanceof String)) {
                    throw new IllegalArgumentException("stream.actions." + e.getKey() + " must be a pack id");
                }
                actions.put(t, (String) e.getValue());
            }
        }
        Scenario.PromptMode mode = parseEnum(Scenario.PromptMode.class,
                optString(s, "on_prompt", actions.isEmpty() ? "ignore" : "match"), "stream.on_prompt");
        String fixed = optString(s, "fixed_pack", null);
        if (mode == Scenario.PromptMode.MATCH && actions.isEmpty()) {
            throw new IllegalArgumentException("stream.on_prompt match needs stream.actions");
        }
        if (mode == Scenario.PromptMode.MISMATCH && actions.size() < 2) {
            throw new IllegalArgumentException("stream.on_prompt mismatch needs at least two stream.actions");
        }
        if (mode == Scenario.PromptMode.FIXED && fixed == null) {
            throw new IllegalArgumentException("stream.on_prompt fixed needs stream.fixed_pack");
        }
        Scenario.AtEnd atEnd = parseEnum(Scenario.AtEnd.class, optString(s, "at_end", "pingpong"), "stream.at_end");
        long reaction = optInt(s, "reaction_ms", (int) DEFAULT_REACTION_MS);
        return new Scenario.StreamPlan(main, atEnd, mode, reaction, actions, fixed);
    }

    private static Scenario.Expectation expect(Object value) {
        if (value == null) {
            return new Scenario.Expectation(null, EnumSet.noneOf(Layer.class));
        }
        Map<String, Object> e = asMap(value, "expect");
        checkKeys(e, EXPECT_KEYS, "expect");
        String o = optString(e, "outcome", null);
        Outcome outcome = o == null ? null : parseEnum(Outcome.class, o, "expect.outcome");
        Set<Layer> layers = EnumSet.noneOf(Layer.class);
        Object l = e.get("layers");
        if (l instanceof List) {
            for (Object item : (List<?>) l) {
                layers.add(parseEnum(Layer.class, String.valueOf(item), "expect.layers"));
            }
        } else if (l != null) {
            throw new IllegalArgumentException("expect.layers must be a list");
        }
        return new Scenario.Expectation(outcome, layers);
    }

    // ------------------------------------------------------------------ small helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String where) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(where + " must be a mapping");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static void checkKeys(Map<String, Object> m, Set<String> allowed, String where) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException("unknown key in " + where + ": " + k);
            }
        }
    }

    private static String requireString(Map<String, Object> m, String key) {
        String v = optString(m, key, null);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return v.trim();
    }

    private static String optString(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Map || v instanceof List) {
            throw new IllegalArgumentException(key + " must be a scalar");
        }
        return v.toString();
    }

    private static int optInt(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        return v == null ? dflt : (int) toLong(v, key);
    }

    private static long toLong(Object v, String key) {
        if (v instanceof Integer || v instanceof Long) {
            return ((Number) v).longValue();
        }
        throw new IllegalArgumentException(key + " must be an integer: " + v);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String key) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": unknown value " + value + ", expected one of "
                    + Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT));
        }
    }
}
