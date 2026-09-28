package io.mosip.liveness.core.audit;

import io.mosip.liveness.core.Workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One liveness audit record, handed to an {@link AuditSink} and stored by the client's audit service.
 *
 * <p>The payload is a flat map of short strings, serialised by {@link #descriptionJson()} so both clients
 * produce the same JSON. Limits guard against images or templates slipping into audits: each value is at most
 * {@value #MAX_VALUE_LENGTH} characters and the JSON at most {@value #MAX_DESCRIPTION_LENGTH} characters (the
 * desktop {@code LOG_DESC VARCHAR(2048)} column).
 */
public final class LivenessAuditEvent {

    /** Longest allowed attribute value, in characters. */
    public static final int MAX_VALUE_LENGTH = 256;
    /** Longest allowed JSON description, in characters. */
    public static final int MAX_DESCRIPTION_LENGTH = 2048;

    private final AuditEventId eventId;
    private final Workflow workflow;
    private final String sessionId;
    private final Instant occurredAt;
    private final Map<String, String> attributes;
    private final String descriptionJson;

    private LivenessAuditEvent(Builder b, String descriptionJson) {
        this.eventId = b.eventId;
        this.workflow = b.workflow;
        this.sessionId = b.sessionId;
        this.occurredAt = b.occurredAt;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
        this.descriptionJson = descriptionJson;
    }

    /**
     * Starts a builder.
     *
     * @param eventId    event type
     * @param workflow   workflow of the session
     * @param sessionId  session identifier (use an empty string for events outside a session, such as LV15)
     * @param occurredAt wall-clock time of the event
     * @return a builder
     */
    public static Builder builder(AuditEventId eventId, Workflow workflow, String sessionId, Instant occurredAt) {
        return new Builder(eventId, workflow, sessionId, occurredAt);
    }

    /**
     * Returns the event type.
     *
     * @return the event identifier
     */
    public AuditEventId eventId() {
        return eventId;
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns the session identifier.
     *
     * @return the session identifier, possibly empty
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns when the event happened.
     *
     * @return the instant
     */
    public Instant occurredAt() {
        return occurredAt;
    }

    /**
     * Returns the event attributes (without workflow and session identifier).
     *
     * @return an unmodifiable map in insertion order
     */
    public Map<String, String> attributes() {
        return attributes;
    }

    /**
     * Returns the payload as one-line JSON: workflow and session identifier first, then the attributes in
     * insertion order, for example {@code {"workflow":"registration","sessionId":"s1","attempt":"1"}}.
     *
     * @return the JSON text, at most {@value #MAX_DESCRIPTION_LENGTH} characters
     */
    public String descriptionJson() {
        return descriptionJson;
    }

    @Override
    public String toString() {
        return eventId.id() + " " + descriptionJson;
    }

    private static String toJson(Workflow workflow, String sessionId, Map<String, String> attributes) {
        StringBuilder sb = new StringBuilder(128).append('{');
        appendPair(sb, AuditAttributes.WORKFLOW, workflow.id());
        sb.append(',');
        appendPair(sb, AuditAttributes.SESSION_ID, sessionId);
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            sb.append(',');
            appendPair(sb, e.getKey(), e.getValue());
        }
        return sb.append('}').toString();
    }

    private static void appendPair(StringBuilder sb, String key, String value) {
        appendString(sb, key);
        sb.append(':');
        appendString(sb, value);
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Builder for {@link LivenessAuditEvent}. */
    public static final class Builder {

        private final AuditEventId eventId;
        private final Workflow workflow;
        private final String sessionId;
        private final Instant occurredAt;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(AuditEventId eventId, Workflow workflow, String sessionId, Instant occurredAt) {
            this.eventId = Objects.requireNonNull(eventId, "eventId");
            this.workflow = Objects.requireNonNull(workflow, "workflow");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }

        /**
         * Adds a text attribute.
         *
         * @param key   attribute name, preferably from {@link AuditAttributes}; not workflow or sessionId
         * @param value value of at most {@value LivenessAuditEvent#MAX_VALUE_LENGTH} characters
         * @return this builder
         * @throws IllegalArgumentException if the key is empty or reserved, or the value is too long
         */
        public Builder put(String key, String value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (key.isEmpty() || AuditAttributes.WORKFLOW.equals(key) || AuditAttributes.SESSION_ID.equals(key)) {
                throw new IllegalArgumentException("empty or reserved attribute name: " + key);
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("attribute " + key + " exceeds " + MAX_VALUE_LENGTH + " characters");
            }
            attributes.put(key, value);
            return this;
        }

        /**
         * Adds a numeric attribute.
         *
         * @param key   attribute name
         * @param value number
         * @return this builder
         */
        public Builder put(String key, long value) {
            return put(key, Long.toString(value));
        }

        /**
         * Creates the event.
         *
         * @return the event
         * @throws IllegalArgumentException if the JSON description would exceed
         *                                  {@value LivenessAuditEvent#MAX_DESCRIPTION_LENGTH} characters
         */
        public LivenessAuditEvent build() {
            String json = toJson(workflow, sessionId, attributes);
            if (json.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("audit description exceeds " + MAX_DESCRIPTION_LENGTH + " characters");
            }
            return new LivenessAuditEvent(this, json);
        }
    }
}
