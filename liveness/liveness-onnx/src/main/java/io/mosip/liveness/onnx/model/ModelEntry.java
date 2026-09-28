package io.mosip.liveness.onnx.model;

import io.mosip.liveness.core.api.ModelInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One model of a signed bundle, as listed in {@code manifest.json}: identifier, file, version, SHA-256, size,
 * licence, source and model parameters (for example the MiniFASNet crop scale).
 */
public final class ModelEntry {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private final String id;
    private final String file;
    private final String version;
    private final String sha256;
    private final long bytes;
    private final String licence;
    private final String source;
    private final Map<String, String> params;

    private ModelEntry(String id, String file, String version, String sha256, long bytes, String licence, String source,
                       Map<String, String> params) {
        this.id = id;
        this.file = file;
        this.version = version;
        this.sha256 = sha256;
        this.bytes = bytes;
        this.licence = licence;
        this.source = source;
        this.params = params;
    }

    /**
     * Creates an entry and validates every field.
     *
     * @param id      identifier: lower-case letters, digits, {@code . _ -}; up to 64 characters
     * @param file    path inside the bundle; {@code /} separators, no {@code ..} and no leading {@code /}
     * @param version model version; not empty
     * @param sha256  SHA-256 of the file as 64 hex digits (stored lower-case)
     * @param bytes   file size; positive
     * @param licence SPDX licence identifier; not empty
     * @param source  where the file comes from (URL and commit); not empty
     * @param params  model parameters; copied
     * @return the entry
     * @throws IllegalArgumentException if a field is invalid
     */
    public static ModelEntry of(String id, String file, String version, String sha256, long bytes, String licence,
                                String source, Map<String, String> params) {
        require(id != null && ID.matcher(id).matches(), "invalid model id: " + id);
        require(file != null && FILE.matcher(file).matches() && !file.contains(".."), "invalid model file for " + id);
        require(version != null && !version.trim().isEmpty(), "missing version for " + id);
        String hex = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
        require(HEX64.matcher(hex).matches(), "sha256 must be 64 hex digits for " + id);
        require(bytes > 0 && bytes <= MAX_BYTES, "invalid size for " + id);
        require(licence != null && !licence.trim().isEmpty(), "missing licence for " + id);
        require(source != null && !source.trim().isEmpty(), "missing source for " + id);
        Objects.requireNonNull(params, "params");
        return new ModelEntry(id, file, version, hex, bytes, licence, source,
                Collections.unmodifiableMap(new LinkedHashMap<>(params)));
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Returns a copy with another SHA-256 and size (used by the signer tool after hashing the files).
     *
     * @param newSha256 64 hex digits
     * @param newBytes  file size
     * @return the updated entry
     */
    public ModelEntry withDigest(String newSha256, long newBytes) {
        return of(id, file, version, newSha256, newBytes, licence, source, params);
    }

    /**
     * Returns the model identifier.
     *
     * @return the identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the file path inside the bundle.
     *
     * @return the relative path
     */
    public String file() {
        return file;
    }

    /**
     * Returns the model version.
     *
     * @return the version
     */
    public String version() {
        return version;
    }

    /**
     * Returns the expected SHA-256 of the file.
     *
     * @return 64 lower-case hex digits
     */
    public String sha256() {
        return sha256;
    }

    /**
     * Returns the expected file size.
     *
     * @return bytes
     */
    public long bytes() {
        return bytes;
    }

    /**
     * Returns the SPDX licence identifier.
     *
     * @return for example {@code "Apache-2.0"}
     */
    public String licence() {
        return licence;
    }

    /**
     * Returns the file's origin.
     *
     * @return URL and commit or tag
     */
    public String source() {
        return source;
    }

    /**
     * Returns every model parameter.
     *
     * @return an unmodifiable map
     */
    public Map<String, String> params() {
        return params;
    }

    /**
     * Returns one model parameter.
     *
     * @param name parameter name
     * @return the value, if present
     */
    public Optional<String> param(String name) {
        return Optional.ofNullable(params.get(name));
    }

    /**
     * Returns a numeric model parameter.
     *
     * @param name     parameter name
     * @param fallback value used when the parameter is absent
     * @return the value
     * @throws IllegalArgumentException if the parameter is present but not a finite number
     */
    public double doubleParam(String name, double fallback) {
        String v = params.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("parameter " + name + " of " + id + " is not finite");
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter " + name + " of " + id + " is not a number", e);
        }
    }

    /**
     * Returns the audit description of this model.
     *
     * @return identifier, version and SHA-256
     */
    public ModelInfo toModelInfo() {
        return ModelInfo.of(id, version, sha256);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ModelEntry)) {
            return false;
        }
        ModelEntry m = (ModelEntry) o;
        return bytes == m.bytes && id.equals(m.id) && file.equals(m.file) && version.equals(m.version)
                && sha256.equals(m.sha256) && licence.equals(m.licence) && source.equals(m.source)
                && params.equals(m.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, file, version, sha256, bytes, licence, source, params);
    }

    @Override
    public String toString() {
        return id + "@" + version + " (" + file + ", " + bytes + " bytes, " + licence + ")";
    }
}
