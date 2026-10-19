package com.company.campaign.journey.compiler;

/**
 * The version discriminators stamped into every T20 Journey compiler artifact.
 *
 * <p>Compilation is a deterministic, versioned transformation: a change to a derivation, validation or
 * hashing rule is a new version, so a Journey artifact can never silently compare two outputs produced under
 * different rules. Every value is part of the Journey content hash and is reported verbatim.
 *
 * <p>The runtime schema version is the frozen T19 Flow runtime schema. T20 claims only that a Journey was
 * compiled against it; it claims no Execution, task, message send or reward grant, which remain T21-T23.
 */
public final class JourneyVersions {

    /** The T20 Journey topology and Manifest document format version. */
    public static final String JOURNEY_FORMAT_VERSION = "1.0";

    /** The T20 Journey compiler version. */
    public static final String JOURNEY_COMPILER_VERSION = "journey-compiler/1.0";

    /** The exact runtime schema version a Journey is compiled against. */
    public static final String JOURNEY_RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    private JourneyVersions() {
    }
}
