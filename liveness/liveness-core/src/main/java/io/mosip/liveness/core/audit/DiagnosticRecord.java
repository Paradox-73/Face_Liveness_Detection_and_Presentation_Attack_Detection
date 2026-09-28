package io.mosip.liveness.core.audit;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One diagnostic entry, written at each session state change when {@code diagnostic.enable=Y}.
 *
 * <p>The type holds only technical signals by construction: there is no field for images, embeddings, user
 * identifiers or registration identifiers (tests T-CORE-AUD-02). Raw scores are allowed here (they are not
 * allowed in audits).
 */
public final class DiagnosticRecord {

    /** Longest allowed {@link #detail()} text, in characters. */
    public static final int MAX_DETAIL_LENGTH = 256;

    private final String sessionId;
    private final SessionState state;
    private final long elapsedMs;
    private final Integer padScore;
    private final Set<ReasonCode> flags;
    private final Double planarityResidual;
    private final Integer bindingSimilarity;
    private final Double fps;
    private final Map<String, String> modelVersions;
    private final String detail;

    private DiagnosticRecord(Builder b) {
        this.sessionId = b.sessionId;
        this.state = b.state;
        this.elapsedMs = b.elapsedMs;
        this.padScore = b.padScore;
        this.flags = Collections.unmodifiableSet(b.flags.isEmpty()
                ? EnumSet.noneOf(ReasonCode.class) : EnumSet.copyOf(b.flags));
        this.planarityResidual = b.planarityResidual;
        this.bindingSimilarity = b.bindingSimilarity;
        this.fps = b.fps;
        this.modelVersions = Collections.unmodifiableMap(new LinkedHashMap<>(b.modelVersions));
        this.detail = b.detail;
    }

    /**
     * Starts a builder.
     *
     * @param sessionId session identifier
     * @param state     state the session just entered
     * @param elapsedMs time since the session started, in milliseconds
     * @return a builder
     */
    public static Builder builder(String sessionId, SessionState state, long elapsedMs) {
        return new Builder(sessionId, state, elapsedMs);
    }

    /**
     * Returns the session identifier.
     *
     * @return the session identifier
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns the state the session entered.
     *
     * @return the state
     */
    public SessionState state() {
        return state;
    }

    /**
     * Returns the time since the session started.
     *
     * @return milliseconds
     */
    public long elapsedMs() {
        return elapsedMs;
    }

    /**
     * Returns the current passive PAD score.
     *
     * @return score 0-100, if computed
     */
    public OptionalInt padScore() {
        return padScore == null ? OptionalInt.empty() : OptionalInt.of(padScore);
    }

    /**
     * Returns the integrity and quality flags raised so far.
     *
     * @return an unmodifiable set, possibly empty
     */
    public Set<ReasonCode> flags() {
        return flags;
    }

    /**
     * Returns the planarity residual (landmark homography residual RMS divided by inter-eye distance).
     *
     * @return the residual, if a head turn was measured
     */
    public OptionalDouble planarityResidual() {
        return planarityResidual == null ? OptionalDouble.empty() : OptionalDouble.of(planarityResidual);
    }

    /**
     * Returns the capture-to-session similarity.
     *
     * @return similarity 0-100, if binding ran
     */
    public OptionalInt bindingSimilarity() {
        return bindingSimilarity == null ? OptionalInt.empty() : OptionalInt.of(bindingSimilarity);
    }

    /**
     * Returns the measured stream frame rate.
     *
     * @return frames per second, if measured
     */
    public OptionalDouble fps() {
        return fps == null ? OptionalDouble.empty() : OptionalDouble.of(fps);
    }

    /**
     * Returns the model versions in use.
     *
     * @return an unmodifiable map from model identifier to version
     */
    public Map<String, String> modelVersions() {
        return modelVersions;
    }

    /**
     * Returns the technical detail, for example the configuration key that fell back to its default.
     *
     * @return the detail text, or an empty string
     */
    public String detail() {
        return detail;
    }

    /**
     * Formats the record as one log line of {@code key=value} pairs; absent values are left out.
     *
     * @return for example {@code liveness session=s1 state=PASSIVE elapsedMs=812 pad=91 fps=14.8}
     */
    public String toLogLine() {
        StringBuilder sb = new StringBuilder("liveness session=").append(sessionId).append(" state=").append(state)
                .append(" elapsedMs=").append(elapsedMs);
        if (padScore != null) {
            sb.append(" pad=").append(padScore);
        }
        if (!flags.isEmpty()) {
            sb.append(" flags=").append(join(flags));
        }
        if (planarityResidual != null) {
            sb.append(" planarity=").append(planarityResidual);
        }
        if (bindingSimilarity != null) {
            sb.append(" similarity=").append(bindingSimilarity);
        }
        if (fps != null) {
            sb.append(" fps=").append(fps);
        }
        if (!modelVersions.isEmpty()) {
            sb.append(" models=");
            String sep = "";
            for (Map.Entry<String, String> e : modelVersions.entrySet()) {
                sb.append(sep).append(e.getKey()).append('@').append(e.getValue());
                sep = ",";
            }
        }
        if (!detail.isEmpty()) {
            sb.append(" detail=").append(detail);
        }
        return sb.toString();
    }

    private static String join(Set<ReasonCode> codes) {
        StringBuilder sb = new StringBuilder();
        for (ReasonCode c : codes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.name());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toLogLine();
    }

    /** Builder for {@link DiagnosticRecord}. */
    public static final class Builder {

        private final String sessionId;
        private final SessionState state;
        private final long elapsedMs;
        private Integer padScore;
        private final Set<ReasonCode> flags = EnumSet.noneOf(ReasonCode.class);
        private Double planarityResidual;
        private Integer bindingSimilarity;
        private Double fps;
        private final Map<String, String> modelVersions = new LinkedHashMap<>();
        private String detail = "";

        private Builder(String sessionId, SessionState state, long elapsedMs) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.state = Objects.requireNonNull(state, "state");
            if (elapsedMs < 0) {
                throw new IllegalArgumentException("elapsedMs must not be negative");
            }
            this.elapsedMs = elapsedMs;
        }

        /**
         * Sets the passive PAD score.
         *
         * @param score 0-100
         * @return this builder
         */
        public Builder padScore(int score) {
            this.padScore = checkScore(score);
            return this;
        }

        /**
         * Adds a flag.
         *
         * @param flag integrity or quality reason code
         * @return this builder
         */
        public Builder flag(ReasonCode flag) {
            flags.add(Objects.requireNonNull(flag, "flag"));
            return this;
        }

        /**
         * Sets the planarity residual.
         *
         * @param residual residual RMS divided by inter-eye distance; 0 or more
         * @return this builder
         */
        public Builder planarityResidual(double residual) {
            if (!(residual >= 0) || Double.isInfinite(residual)) {
                throw new IllegalArgumentException("residual must be finite and not negative");
            }
            this.planarityResidual = residual;
            return this;
        }

        /**
         * Sets the capture-to-session similarity.
         *
         * @param similarity 0-100
         * @return this builder
         */
        public Builder bindingSimilarity(int similarity) {
            this.bindingSimilarity = checkScore(similarity);
            return this;
        }

        /**
         * Sets the measured frame rate.
         *
         * @param fps frames per second; 0 or more
         * @return this builder
         */
        public Builder fps(double fps) {
            if (!(fps >= 0) || Double.isInfinite(fps)) {
                throw new IllegalArgumentException("fps must be finite and not negative");
            }
            this.fps = fps;
            return this;
        }

        /**
         * Adds a model version.
         *
         * @param modelId model identifier
         * @param version model version
         * @return this builder
         */
        public Builder model(String modelId, String version) {
            modelVersions.put(Objects.requireNonNull(modelId, "modelId"), Objects.requireNonNull(version, "version"));
            return this;
        }

        /**
         * Sets a short technical detail. It must not contain images, embeddings, user identifiers or
         * registration identifiers; configuration keys, reason codes and error class names are fine.
         *
         * @param detail text of at most {@value DiagnosticRecord#MAX_DETAIL_LENGTH} characters; line breaks are
         *               replaced by spaces
         * @return this builder
         * @throws IllegalArgumentException if the text is too long
         */
        public Builder detail(String detail) {
            Objects.requireNonNull(detail, "detail");
            if (detail.length() > MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException("detail exceeds " + MAX_DETAIL_LENGTH + " characters");
            }
            this.detail = detail.replace('\r', ' ').replace('\n', ' ');
            return this;
        }

        /**
         * Creates the record.
         *
         * @return the record
         */
        public DiagnosticRecord build() {
            return new DiagnosticRecord(this);
        }

        private static Integer checkScore(int score) {
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("score must be 0-100");
            }
            return score;
        }
    }
}
