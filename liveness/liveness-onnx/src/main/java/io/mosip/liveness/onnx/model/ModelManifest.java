/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The model bundle manifest ({@code manifest.json}): bundle name, bundle version, one {@link ModelEntry} per
 * model file and, optionally, the detection parameters of the default engine ({@code engineParams}).
 *
 * <pre>{@code
 * {
 *   "schema": 1,
 *   "bundle": "mosip-liveness-models",
 *   "version": "1.0.0",
 *   "models": [
 *     { "id": "yunet", "file": "face_detection_yunet_2026may.onnx", "version": "2026may",
 *       "sha256": "...", "bytes": 229738, "licence": "MIT", "source": "https://...@commit", "params": {} }
 *   ],
 *   "engineParams": { "planarity.min_residual": "0.02" }
 * }
 * }</pre>
 *
 * <p>The signature in {@code manifest.jws} covers the exact bytes of this file, so parsing does not need a
 * canonical form. Unknown keys are ignored. {@code engineParams} is optional; its keys and ranges are checked by
 * the engine ({@code EngineParameters.fromMap}), not here, so the manifest format stays independent of the
 * engine version. Because the parameters are signed with the models, a bundle update can recalibrate the
 * engine but a local edit cannot.
 */
public final class ModelManifest {

    /** The manifest format version this code reads and writes. */
    public static final int SCHEMA = 1;

    private static final int MAX_BYTES = 256 * 1024;

    private final String bundle;
    private final SemanticVersion version;
    private final List<ModelEntry> models;
    private final Map<String, String> engineParams;

    private ModelManifest(String bundle, SemanticVersion version, List<ModelEntry> models,
                          Map<String, String> engineParams) {
        this.bundle = bundle;
        this.version = version;
        this.models = models;
        this.engineParams = engineParams;
    }

    /**
     * Creates a manifest.
     *
     * @param bundle  bundle name; not empty
     * @param version bundle version ({@code MAJOR.MINOR.PATCH})
     * @param models  model entries with unique identifiers and files; copied
     * @return the manifest
     * @throws IllegalArgumentException if a value is invalid or an identifier or file repeats
     */
    public static ModelManifest of(String bundle, String version, List<ModelEntry> models) {
        return of(bundle, version, models, Collections.<String, String>emptyMap());
    }

    /**
     * Creates a manifest with engine parameters.
     *
     * @param bundle       bundle name; not empty
     * @param version      bundle version ({@code MAJOR.MINOR.PATCH})
     * @param models       model entries with unique identifiers and files; copied
     * @param engineParams engine parameter keys and values as text, possibly empty; copied in iteration order
     * @return the manifest
     * @throws IllegalArgumentException if a value is invalid, an identifier or file repeats, or a parameter key
     *                                  or value is empty
     */
    public static ModelManifest of(String bundle, String version, List<ModelEntry> models,
                                   Map<String, String> engineParams) {
        if (bundle == null || bundle.trim().isEmpty()) {
            throw new IllegalArgumentException("bundle name missing");
        }
        Objects.requireNonNull(models, "models");
        Set<String> ids = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (ModelEntry m : models) {
            if (!ids.add(m.id()) || !files.add(m.file())) {
                throw new IllegalArgumentException("duplicate model id or file: " + m.id());
            }
        }
        Objects.requireNonNull(engineParams, "engineParams");
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : engineParams.entrySet()) {
            if (e.getKey() == null || e.getKey().trim().isEmpty() || e.getValue() == null
                    || e.getValue().trim().isEmpty()) {
                throw new IllegalArgumentException("engine parameter keys and values must not be empty");
            }
            params.put(e.getKey(), e.getValue());
        }
        return new ModelManifest(bundle, SemanticVersion.parse(version),
                Collections.unmodifiableList(new ArrayList<>(models)), Collections.unmodifiableMap(params));
    }

    /**
     * Parses {@code manifest.json}.
     *
     * @param json UTF-8 bytes of the manifest
     * @return the manifest
     * @throws IllegalArgumentException if the document is malformed or a field is invalid
     */
    public static ModelManifest parse(byte[] json) {
        Objects.requireNonNull(json, "json");
        if (json.length > MAX_BYTES) {
            throw new IllegalArgumentException("manifest larger than " + MAX_BYTES + " bytes");
        }
        Map<String, Object> root = asObject(Json.parse(new String(json, StandardCharsets.UTF_8)), "manifest");
        Object schema = root.get("schema");
        if (!(schema instanceof Long) || (Long) schema != SCHEMA) {
            throw new IllegalArgumentException("unsupported manifest schema: " + schema);
        }
        List<ModelEntry> entries = new ArrayList<>();
        Object list = root.get("models");
        if (!(list instanceof List)) {
            throw new IllegalArgumentException("manifest has no models list");
        }
        for (Object item : (List<?>) list) {
            entries.add(entry(asObject(item, "model")));
        }
        return of(string(root, "bundle"), string(root, "version"), entries, engineParams(root.get("engineParams")));
    }

    private static Map<String, String> engineParams(Object value) {
        Map<String, String> params = new LinkedHashMap<>();
        if (value == null) {
            return params;
        }
        for (Map.Entry<String, Object> e : asObject(value, "engineParams").entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof String || v instanceof Long || v instanceof Double)) {
                throw new IllegalArgumentException("engineParams values must be strings or numbers");
            }
            params.put(e.getKey(), v.toString());
        }
        return params;
    }

    private static ModelEntry entry(Map<String, Object> m) {
        Object size = m.get("bytes");
        if (!(size instanceof Long)) {
            throw new IllegalArgumentException("model bytes must be an integer");
        }
        Map<String, String> params = new LinkedHashMap<>();
        Object p = m.get("params");
        if (p != null) {
            for (Map.Entry<String, Object> e : asObject(p, "params").entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof String || v instanceof Long || v instanceof Double || v instanceof Boolean)) {
                    throw new IllegalArgumentException("params values must be strings, numbers or booleans");
                }
                params.put(e.getKey(), v.toString());
            }
        }
        return ModelEntry.of(string(m, "id"), string(m, "file"), string(m, "version"), string(m, "sha256"),
                (Long) size, string(m, "licence"), string(m, "source"), params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(what + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("missing string field: " + key);
        }
        return (String) v;
    }

    /**
     * Returns the bundle name.
     *
     * @return the name
     */
    public String bundle() {
        return bundle;
    }

    /**
     * Returns the bundle version.
     *
     * @return the version
     */
    public SemanticVersion version() {
        return version;
    }

    /**
     * Returns the model entries in manifest order.
     *
     * @return an unmodifiable list
     */
    public List<ModelEntry> models() {
        return models;
    }

    /**
     * Returns the engine parameters of the bundle ({@code engineParams}).
     *
     * @return an unmodifiable map in manifest order; empty when the manifest has none, so the engine keeps its
     *         shipped defaults
     */
    public Map<String, String> engineParams() {
        return engineParams;
    }

    /**
     * Finds a model by identifier.
     *
     * @param id model identifier
     * @return the entry, if listed
     */
    public Optional<ModelEntry> entry(String id) {
        for (ModelEntry m : models) {
            if (m.id().equals(id)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns a copy with other model entries.
     *
     * @param newModels the entries
     * @return the new manifest
     */
    public ModelManifest withModels(List<ModelEntry> newModels) {
        return of(bundle, version.toString(), newModels, engineParams);
    }

    /**
     * Writes the manifest as pretty-printed JSON with a fixed key order and {@code \n} line ends, so the same
     * manifest always gives the same bytes. {@code engineParams} is written only when present, so a manifest
     * without it keeps its exact bytes (and its signature) through a parse and write.
     *
     * @return the JSON text, ending with a line break
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"schema\": ").append(SCHEMA).append(",\n");
        field(sb, "  ", "bundle", bundle);
        field(sb, "  ", "version", version.toString());
        sb.append("  \"models\": [\n");
        for (int i = 0; i < models.size(); i++) {
            ModelEntry m = models.get(i);
            sb.append("    {\n");
            field(sb, "      ", "id", m.id());
            field(sb, "      ", "file", m.file());
            field(sb, "      ", "version", m.version());
            field(sb, "      ", "sha256", m.sha256());
            sb.append("      \"bytes\": ").append(m.bytes()).append(",\n");
            field(sb, "      ", "licence", m.licence());
            field(sb, "      ", "source", m.source());
            sb.append("      \"params\": ");
            inlineObject(sb, m.params());
            sb.append("\n    }").append(i + 1 < models.size() ? "," : "").append('\n');
        }
        sb.append("  ]");
        if (!engineParams.isEmpty()) {
            sb.append(",\n  \"engineParams\": ");
            inlineObject(sb, engineParams);
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private static void inlineObject(StringBuilder sb, Map<String, String> values) {
        sb.append('{');
        int k = 0;
        for (Map.Entry<String, String> e : values.entrySet()) {
            sb.append(k++ == 0 ? "" : ", ");
            Json.quote(e.getKey(), sb);
            sb.append(": ");
            Json.quote(e.getValue(), sb);
        }
        sb.append('}');
    }

    private static void field(StringBuilder sb, String indent, String key, String value) {
        sb.append(indent);
        Json.quote(key, sb);
        sb.append(": ");
        Json.quote(value, sb);
        sb.append(",\n");
    }

    @Override
    public String toString() {
        return "ModelManifest[" + bundle + "@" + version + ", models=" + models.size() + ", engineParams="
                + engineParams.size() + "]";
    }
}
