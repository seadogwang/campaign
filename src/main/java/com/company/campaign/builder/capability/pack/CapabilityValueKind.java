package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;

/**
 * The closed set of value kinds a capability pack payload field may declare.
 *
 * <p>This is the common, capability-agnostic vocabulary every T19 pack shares: a field is a string, an
 * integer, a decimal, a boolean, a date, a datetime, a duration, an ordered enumeration or a reference to
 * another resolved component. It is deliberately narrow and closed — a pack cannot invent a kind, and an
 * unknown token is refused rather than coerced into a neighbour.
 *
 * <p>The distinctions the rest of the pack contract depends on are facts of the kind, not of a caller: only
 * the ordered kinds have a total order, and only {@link #REFERENCE} points at another component.
 */
public enum CapabilityValueKind {

    /** Bounded text. */
    STRING,

    /** Whole numbers. */
    INTEGER,

    /** Exact decimal numbers. */
    DECIMAL,

    /** Truth values. */
    BOOLEAN,

    /** Calendar dates without a time component. */
    DATE,

    /** Instants with a time component. */
    DATETIME,

    /** Positive or zero ISO-8601 durations. */
    DURATION,

    /** Closed coded values with a meaningful order. */
    ORDERED_ENUM,

    /** A reference to another resolved component of the same composition. */
    REFERENCE;

    /**
     * Parses one canonical kind token, refusing anything this build does not declare.
     *
     * <p>The rejected token is never echoed: it is external input and must not become observable output.
     */
    public static CapabilityValueKind parse(String token) {
        if (token != null) {
            for (CapabilityValueKind kind : values()) {
                if (kind.name().equals(token)) {
                    return kind;
                }
            }
        }
        throw invalid();
    }

    /** True when the values of this kind have a meaningful total order. */
    public boolean isOrdered() {
        return this == ORDERED_ENUM || this == INTEGER || this == DECIMAL
                || this == DATE || this == DATETIME || this == DURATION;
    }

    /** True when a value of this kind references another resolved component. */
    public boolean isReference() {
        return this == REFERENCE;
    }

    private static CompilerException invalid() {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                "capabilityPackValueKind",
                "A capability pack field must declare one known value kind.");
    }
}