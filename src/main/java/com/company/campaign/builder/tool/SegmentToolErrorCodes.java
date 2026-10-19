package com.company.campaign.builder.tool;

/**
 * Stable machine codes for the closed T11 Tool boundary.
 *
 * <p>Every refusal is one code so a caller can branch deterministically, and no code echoes a rejected
 * identifier, payload value or implementation detail.
 */
public final class SegmentToolErrorCodes {

    /** The requested Tool identifier is not a server-owned Tool. */
    public static final String TOOL_UNKNOWN = "SEGMENT_TOOL_UNKNOWN";

    /** The requested Tool names a host-only or unsafe capability and is never model-callable. */
    public static final String TOOL_FORBIDDEN_CAPABILITY = "SEGMENT_TOOL_FORBIDDEN_CAPABILITY";

    /** The Tool call names a Tool the exact Skill does not allow. */
    public static final String TOOL_NOT_ALLOWED = "SEGMENT_TOOL_NOT_ALLOWED";

    /** The Tool call repeats a call identifier or a Tool identifier already used in this run. */
    public static final String TOOL_DUPLICATE_CALL = "SEGMENT_TOOL_DUPLICATE_CALL";

    /** The Tool call is ordered before a Tool the run has already used. */
    public static final String TOOL_OUT_OF_ORDER = "SEGMENT_TOOL_OUT_OF_ORDER";

    /** The run has already reached a terminal disposition. */
    public static final String TOOL_POST_TERMINAL = "SEGMENT_TOOL_POST_TERMINAL";

    /** The run would exceed its Skill-bounded Tool-call count. */
    public static final String TOOL_CALL_LIMIT_EXCEEDED = "SEGMENT_TOOL_CALL_LIMIT_EXCEEDED";

    /** The canonical Tool payload exceeds the bounded byte limit. */
    public static final String TOOL_PAYLOAD_LIMIT_EXCEEDED = "SEGMENT_TOOL_PAYLOAD_LIMIT_EXCEEDED";

    /** The canonical Tool result exceeds the bounded size or item limit. */
    public static final String TOOL_OUTPUT_LIMIT_EXCEEDED = "SEGMENT_TOOL_OUTPUT_LIMIT_EXCEEDED";

    /** No host implementation is registered for an allowed Tool. */
    public static final String TOOL_NOT_IMPLEMENTED = "SEGMENT_TOOL_NOT_IMPLEMENTED";

    /** The Tool schema version is not the current server-owned version. */
    public static final String TOOL_SCHEMA_STALE = "SEGMENT_TOOL_SCHEMA_STALE";

    /** The Tool envelope is malformed or its result is incoherent. */
    public static final String TOOL_RESULT_INCOHERENT = "SEGMENT_TOOL_RESULT_INCOHERENT";

    /** The Tool implementation refused the request with a bounded code of its own. */
    public static final String TOOL_EXECUTION_FAILED = "SEGMENT_TOOL_EXECUTION_FAILED";

    /** A handler published a host-private artifact that does not belong to this exact call and outcome. */
    public static final String TOOL_ARTIFACT_INCOHERENT = "SEGMENT_TOOL_ARTIFACT_INCOHERENT";

    /** A caller asked for the host-private artifact of a call that never produced one. */
    public static final String TOOL_ARTIFACT_MISSING = "SEGMENT_TOOL_ARTIFACT_MISSING";

    /** A caller asked for the host-private artifact of a different call, Tool or Skill/catalog. */
    public static final String TOOL_ARTIFACT_MISMATCH = "SEGMENT_TOOL_ARTIFACT_MISMATCH";

    /** The Preview implementation returned a member sample this Skill never requested. */
    public static final String TOOL_SAMPLE_INCOHERENT = "SEGMENT_TOOL_SAMPLE_INCOHERENT";

    /** The Tool call arguments are missing, malformed or not permitted by the Tool schema. */
    public static final String TOOL_ARGUMENT_INVALID = "SEGMENT_TOOL_ARGUMENT_INVALID";

    /** The requested Metadata definition is absent or not accessible; the two are deliberately indistinguishable. */
    public static final String TOOL_RESOURCE_DENIED = "SEGMENT_TOOL_RESOURCE_DENIED";

    /** The host run does not yet hold the evidence an allowed Tool requires. */
    public static final String TOOL_EVIDENCE_MISSING = "SEGMENT_TOOL_EVIDENCE_MISSING";

    /** The deterministic resolution stopped at {@code NEEDS_INPUT}. */
    public static final String TOOL_NEEDS_INPUT = "SEGMENT_TOOL_NEEDS_INPUT";

    /** The deterministic resolution reported an unsupported capability. */
    public static final String TOOL_UNSUPPORTED = "SEGMENT_TOOL_UNSUPPORTED";

    /** The deterministic resolution or authorization denied the request. */
    public static final String TOOL_DENIED = "SEGMENT_TOOL_DENIED";

    /** The deterministic resolution or evidence is invalid. */
    public static final String TOOL_INVALID = "SEGMENT_TOOL_INVALID";

    private SegmentToolErrorCodes() {
    }
}