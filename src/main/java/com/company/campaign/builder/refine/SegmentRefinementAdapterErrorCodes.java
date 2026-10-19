package com.company.campaign.builder.refine;

/**
 * Stable machine codes of the T12 refinement orchestration adapter and its audit projection.
 *
 * <p>Each terminal outcome carries exactly one bounded code. The codes are non-disclosing: none of them echoes
 * a patch value, a provider message, a trusted identifier, a Metadata label, a member identifier or an
 * exception message. A contract or editor refusal re-uses its own
 * {@link SegmentRefinementErrorCodes} value instead, so a caller can still tell a stale base hash from a
 * missing anchor without reading a free-text message.
 */
public final class SegmentRefinementAdapterErrorCodes {

    /** The request envelope or its bounded payload is missing or malformed. */
    public static final String REQUEST_INVALID = "SEGMENT_REFINEMENT_REQUEST_INVALID";

    /** The host trusted execution context could not be resolved. */
    public static final String TRUSTED_CONTEXT_UNAVAILABLE = "SEGMENT_REFINEMENT_TRUSTED_CONTEXT_UNAVAILABLE";

    /** The exact permitted Metadata snapshot does not match the trusted context pin. */
    public static final String METADATA_PIN_MISMATCH = "SEGMENT_REFINEMENT_METADATA_PIN_MISMATCH";

    /** The admitted Metadata discovery Tool refused, so no refinement may proceed. */
    public static final String METADATA_DISCOVERY_REJECTED = "SEGMENT_REFINEMENT_METADATA_DISCOVERY_REJECTED";

    /** The current document does not immutably bind the exact permitted Metadata snapshot. */
    public static final String DSL_BINDING_MISMATCH = "SEGMENT_REFINEMENT_DSL_BINDING_MISMATCH";

    /** The injected Skill is not exactly compatible with the refinement flow and the create-flow proof. */
    public static final String SKILL_INCOMPATIBLE = "SEGMENT_REFINEMENT_SKILL_INCOMPATIBLE";

    /** The T07 validation refused the refined document. */
    public static final String VALIDATION_REJECTED = "SEGMENT_REFINEMENT_VALIDATION_REJECTED";

    /** The T08 planner refused the validated document. */
    public static final String PLANNING_FAILED = "SEGMENT_REFINEMENT_PLANNING_FAILED";

    /** The genuine T09C Safe Preview refused or returned incoherent evidence. */
    public static final String PREVIEW_FAILED = "SEGMENT_REFINEMENT_PREVIEW_FAILED";

    /** The grounded explanation could not be produced from honestly available evidence. */
    public static final String EXPLANATION_FAILED = "SEGMENT_REFINEMENT_EXPLANATION_FAILED";

    /** An optional audit sink was unavailable, so the run fails closed with no artifact. */
    public static final String AUDIT_UNAVAILABLE = "SEGMENT_REFINEMENT_AUDIT_UNAVAILABLE";

    /** The injected evaluation observation boundary failed; the boundary is absent by default. */
    public static final String OBSERVATION_FAILED = "SEGMENT_REFINEMENT_OBSERVATION_FAILED";

    /** A Tool call was refused; the Tool boundary supplies the precise bounded code. */
    public static final String TOOL_REJECTED = "SEGMENT_REFINEMENT_TOOL_REJECTED";

    /** An illegal, skipped, repeated, backward or post-terminal state transition was attempted. */
    public static final String ILLEGAL_TRANSITION = "SEGMENT_REFINEMENT_ILLEGAL_TRANSITION";

    private SegmentRefinementAdapterErrorCodes() {
    }
}