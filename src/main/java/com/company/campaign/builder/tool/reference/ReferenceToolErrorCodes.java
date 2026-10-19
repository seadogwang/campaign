package com.company.campaign.builder.tool.reference;

/**
 * Stable machine codes of the host-owned read-only Existing Audience Reference Tool surface.
 *
 * <p>Every code is a closed constant so a caller, audit record or evaluation pin can react to a refusal
 * without parsing prose. None of them names a rejected Tool identifier, a candidate text, an audience label or
 * a version pin, because the refusal itself must disclose nothing.
 */
public final class ReferenceToolErrorCodes {

    /** The identifier is not one of the two registered read-only reference Tools. */
    public static final String REFERENCE_TOOL_UNKNOWN = "REFERENCE_TOOL_UNKNOWN";

    /** The identifier names a host-only mutation action, refused before the unknown-identifier rule. */
    public static final String REFERENCE_TOOL_FORBIDDEN_CAPABILITY = "REFERENCE_TOOL_FORBIDDEN_CAPABILITY";

    /** A Tool answer exceeded the bound its own contract declared, so it is refused rather than truncated. */
    public static final String REFERENCE_TOOL_OUTPUT_LIMIT_EXCEEDED = "REFERENCE_TOOL_OUTPUT_LIMIT_EXCEEDED";

    /** The host directory did not answer with acceptable material. */
    public static final String REFERENCE_TOOL_DIRECTORY_INVALID = "REFERENCE_TOOL_DIRECTORY_INVALID";

    /** A host-supplied input of one Tool call is missing, malformed or out of bounds. */
    public static final String REFERENCE_TOOL_INPUT_INVALID = "REFERENCE_TOOL_INPUT_INVALID";

    /** The registered contract and the observed answer contradict each other. */
    public static final String REFERENCE_TOOL_INCONSISTENT = "REFERENCE_TOOL_INCONSISTENT";

    private ReferenceToolErrorCodes() {
    }
}