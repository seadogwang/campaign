package com.company.campaign.builder.agent;

/**
 * Stable machine codes of the formal T11 Agent adapter.
 *
 * <p>Each terminal outcome carries exactly one bounded code. The codes are non-disclosing: none of them echoes
 * provider text, a payload value, a restricted identifier or an exception message.
 */
public final class SegmentAgentErrorCodes {

    /** The request envelope or its bounded payload is missing or malformed. */
    public static final String REQUEST_INVALID = "SEGMENT_AGENT_REQUEST_INVALID";

    /** The host trusted execution context could not be resolved. */
    public static final String TRUSTED_CONTEXT_UNAVAILABLE = "SEGMENT_AGENT_TRUSTED_CONTEXT_UNAVAILABLE";

    /** The exact permitted Metadata snapshot does not match the trusted context pin. */
    public static final String METADATA_PIN_MISMATCH = "SEGMENT_AGENT_METADATA_PIN_MISMATCH";

    /** The injected Skill is not exactly compatible with this fixed Agent adapter flow. */
    public static final String SKILL_INCOMPATIBLE = "SEGMENT_AGENT_SKILL_INCOMPATIBLE";

    /** The bounded safe model request could not be built. */
    public static final String MODEL_REQUEST_INVALID = "SEGMENT_AGENT_MODEL_REQUEST_INVALID";

    /** The authorized Metadata discovery Tool refused, so no projection may reach the model. */
    public static final String METADATA_DISCOVERY_REJECTED = "SEGMENT_AGENT_METADATA_DISCOVERY_REJECTED";

    /** The injected model port failed. */
    public static final String PROVIDER_FAILED = "SEGMENT_AGENT_PROVIDER_FAILED";

    /** The model output failed the strict Intent guard. */
    public static final String MODEL_OUTPUT_REJECTED = "SEGMENT_AGENT_MODEL_OUTPUT_REJECTED";

    /** The Intent declared an unsupported requirement. */
    public static final String UNSUPPORTED_REQUIREMENT = "SEGMENT_AGENT_UNSUPPORTED_REQUIREMENT";

    /** The deterministic resolution failed. */
    public static final String RESOLUTION_REJECTED = "SEGMENT_AGENT_RESOLUTION_REJECTED";

    /** The resolved Intent could not be compiled to the host-owned DSL. */
    public static final String DSL_COMPILATION_FAILED = "SEGMENT_AGENT_DSL_COMPILATION_FAILED";

    /** The T07 validation rejected the document. */
    public static final String VALIDATION_REJECTED = "SEGMENT_AGENT_VALIDATION_REJECTED";

    /** The T08 planner rejected the validated document. */
    public static final String PLANNING_FAILED = "SEGMENT_AGENT_PLANNING_FAILED";

    /** The genuine T09C Safe Preview failed. */
    public static final String PREVIEW_FAILED = "SEGMENT_AGENT_PREVIEW_FAILED";

    /** The grounded explanation could not be produced from accepted evidence. */
    public static final String EXPLANATION_FAILED = "SEGMENT_AGENT_EXPLANATION_FAILED";

    /** An optional audit sink was unavailable, so the run fails closed with no artifact. */
    public static final String AUDIT_UNAVAILABLE = "SEGMENT_AGENT_AUDIT_UNAVAILABLE";

    /**
     * The injected evaluation observation boundary failed.
     *
     * <p>The boundary is absent by default; when a host injects one, a failure to observe an already-admitted
     * artifact is a fail-closed condition, because a run whose evidence cannot be observed must not be reported
     * as if it had been.
     */
    public static final String OBSERVATION_FAILED = "SEGMENT_AGENT_OBSERVATION_FAILED";

    /** The optional continuation receipt is stale, fabricated or not a current offered option. */
    public static final String CONTINUATION_REJECTED = "SEGMENT_AGENT_CONTINUATION_REJECTED";

    /** A Tool call was refused; the Tool boundary supplies the precise bounded code. */
    public static final String TOOL_REJECTED = "SEGMENT_AGENT_TOOL_REJECTED";

    /** An illegal, skipped, repeated, backward or post-terminal state transition was attempted. */
    public static final String ILLEGAL_TRANSITION = "SEGMENT_AGENT_ILLEGAL_TRANSITION";

    private SegmentAgentErrorCodes() {
    }
}