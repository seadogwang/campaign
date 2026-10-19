package com.company.campaign.journey.compiler;

/**
 * Stable machine-readable failure codes owned by the T20 deterministic Journey compiler.
 *
 * <p>These values are part of the wire contract: a host or an API error envelope branches on them, so an
 * existing code is never renamed or reused for a different meaning and a new failure mode gets a new code.
 * Every code carries fixed prose only; no code ever echoes a payload value, a logical identifier, template or
 * reward data, PII or an unrestricted exception.
 */
public final class JourneyCompileErrorCodes {

    /** The request supplied a compiler or runtime schema version this build does not support. */
    public static final String VERSION_UNSUPPORTED = "JOURNEY_COMPILE_VERSION_UNSUPPORTED";

    /** The frozen pack index declares a runtime schema version other than the requested one. */
    public static final String RUNTIME_INDEX_MISMATCH = "JOURNEY_COMPILE_RUNTIME_INDEX_MISMATCH";

    /** The request was absent, so no Journey could be compiled. */
    public static final String INPUT_MISSING = "JOURNEY_COMPILE_INPUT_MISSING";

    /** The graph or node validation failed, so no Journey artifact is produced. */
    public static final String GRAPH_INVALID = "JOURNEY_COMPILE_GRAPH_INVALID";

    private JourneyCompileErrorCodes() {
    }
}
