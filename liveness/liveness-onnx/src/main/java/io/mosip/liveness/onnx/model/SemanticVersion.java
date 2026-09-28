package io.mosip.liveness.onnx.model;

import java.util.Objects;

/**
 * A {@code MAJOR.MINOR.PATCH} version with an optional pre-release suffix ({@code 1.0.0-rc1}), compared as in
 * Semantic Versioning 2.0: numbers first, and a pre-release sorts before its release. Build metadata
 * ({@code +...}) is not accepted. Used to block model bundle rollback ({@code model.min_version}).
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;

    private SemanticVersion(int major, int minor, int patch, String preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    /**
     * Parses a version.
     *
     * @param text for example {@code "1.2.0"} or {@code "1.2.0-rc1"}
     * @return the version
     * @throws IllegalArgumentException if the text is not a valid version
     */
    public static SemanticVersion parse(String text) {
        Objects.requireNonNull(text, "text");
        String core = text;
        String pre = "";
        int dash = text.indexOf('-');
        if (dash >= 0) {
            core = text.substring(0, dash);
            pre = text.substring(dash + 1);
            if (pre.isEmpty() || !pre.matches("[0-9A-Za-z.-]+")) {
                throw new IllegalArgumentException("invalid pre-release in version: " + text);
            }
        }
        String[] parts = core.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("version must be MAJOR.MINOR.PATCH: " + text);
        }
        return new SemanticVersion(number(parts[0], text), number(parts[1], text), number(parts[2], text), pre);
    }

    private static int number(String part, String text) {
        boolean leadingZero = part.length() > 1 && part.charAt(0) == '0';
        if (part.isEmpty() || part.length() > 9 || !part.matches("[0-9]+") || leadingZero) {
            throw new IllegalArgumentException("invalid number in version: " + text);
        }
        return Integer.parseInt(part);
    }

    /**
     * Tells whether this version is at least another one.
     *
     * @param minimum the lowest accepted version
     * @return {@code true} if this version is equal to or newer than {@code minimum}
     */
    public boolean isAtLeast(SemanticVersion minimum) {
        return compareTo(minimum) >= 0;
    }

    @Override
    public int compareTo(SemanticVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, o.patch);
        }
        if (c != 0) {
            return c;
        }
        if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
            return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty());
        }
        return comparePreRelease(preRelease, o.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean xn = x[i].matches("[0-9]+");
            boolean yn = y[i].matches("[0-9]+");
            int c;
            if (xn && yn) {
                c = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            } else if (xn != yn) {
                c = xn ? -1 : 1;
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemanticVersion && compareTo((SemanticVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
