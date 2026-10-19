package com.company.campaign.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract schema version in the canonical {@code major.minor} form.
 *
 * <p>On the wire this is a plain JSON string ({@code "1.3"}), never an object, so a version cannot be
 * half-parsed or partially populated. Exactly one text representation maps to a given version: leading
 * zeros, whitespace, signs, a missing minor component and any extra suffix are rejected with
 * {@code SCHEMA_VERSION_MALFORMED} instead of being silently coerced.
 *
 * <p>Ordering is lexicographic on {@code (major, minor)} and is provided for diagnostics and future
 * upgrade logic only. Compatibility is <em>not</em> inferred from ordering: see
 * {@link SchemaCompatibility}.
 */
public record SchemaVersion(int major, int minor) implements Comparable<SchemaVersion> {

    /** {@code major.minor} with no leading zeros on either component. */
    private static final Pattern FORMAT = Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");

    public SchemaVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Schema version components must not be negative");
        }
    }

    /** Parses the canonical {@code major.minor} text form. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SchemaVersion parse(String value) {
        if (value == null) {
            throw new SchemaVersionException(null);
        }
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new SchemaVersionException(value);
        }
        try {
            return new SchemaVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException exception) {
            // Digits alone do not make a component storable: a value longer than int range is still a
            // malformed wire value, and must not surface as a raw NumberFormatException from a parser
            // whose every other rejection carries the stable schema-version code.
            throw new SchemaVersionException(value);
        }
    }

    /** The canonical wire form. */
    @JsonValue
    public String value() {
        return major + "." + minor;
    }

    @Override
    public int compareTo(SchemaVersion other) {
        Objects.requireNonNull(other, "other");
        int majorComparison = Integer.compare(major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return value();
    }
}