package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.SegmentAgentErrorCodes;
import com.company.campaign.builder.refine.SegmentRefinementAdapterErrorCodes;

/**
 * The closed, bounded failure taxonomy of one T13 attempt.
 *
 * <p>It separates the classes that must never be conflated: an unsound run, an infrastructure outage, a model
 * that broke the schema contract, a model that answered a different question, a Tool boundary refusal, a
 * downstream semantic rejection, a genuine fixture execution failure, a comparison mismatch and an observation
 * failure. Only the members whose {@link #invalidatesRun()} is true make the whole report {@code INVALID};
 * everything else is recorded as an accounted-for attempt with a bounded code.
 *
 * <p>Classification is derived from the stable failure code a stage published, never from prose, a provider
 * message, a stack trace or a comparison diff.
 */
public enum FailureTaxonomy {

    /**
     * A frozen input, Metadata or fixture pin was missing, stale or contradictory, or the reviewed expected
     * evidence itself could not genuinely validate or plan.
     *
     * <p>Missing, stale or contradictory input evidence is an unsound run rather than a model answer, so it
     * makes the whole report {@code INVALID}. It is the one bounded class that both a moved host pin and a
     * reviewed expectation the genuine T07/T08 stages refuse are recorded under.
     */
    INPUT_OR_PIN_INVALIDITY(true),

    /** The corpus review classification is unusable (neither development-usable nor gate-eligible). */
    REVIEW_CLASSIFICATION_INVALIDITY(false),

    /** The provider threw, timed out or otherwise failed before returning bytes. */
    PROVIDER_OR_INFRASTRUCTURE_FAILURE(true),

    /** The first raw response failed the strict model-output JSON/schema/codec contract. */
    FIRST_PASS_SCHEMA_REJECTION(false),

    /** The single bounded repair response also failed the strict model-output contract. */
    REPAIR_SCHEMA_REJECTION(false),

    /** The model answered, but the answer is semantically the wrong one. */
    MODEL_SEMANTIC_MISMATCH(false),

    /** A Tool was refused, or the resolver produced no accepted definition. */
    TOOL_ADMISSION_OR_RESOLUTION_FAILURE(false),

    /** Validation or planning semantically refused the accepted artifact, as an accounted pipeline outcome. */
    VALIDATION_PLANNING_OR_FIXTURE_FAILURE(false),

    /**
     * The genuine T09 fixture execution refused to run the accepted plan.
     *
     * <p>It is split from {@link #VALIDATION_PLANNING_OR_FIXTURE_FAILURE} deliberately: a semantic validation
     * or planning rejection is an accounted model/pipeline outcome that must never invalidate an otherwise
     * sound run, while a fixture that cannot execute the accepted plan is an infrastructure/execution failure
     * that makes the report {@code INVALID}.
     */
    FIXTURE_EXECUTION_FAILURE(true),

    /** Every stage succeeded, and the observed evidence differs from the frozen expectation. */
    COMPARISON_MISMATCH(false),

    /** The injected observation boundary or a configured audit sink failed. */
    OBSERVER_OR_AUDIT_FAILURE(true);

    private final boolean invalidatesRun;

    FailureTaxonomy(boolean invalidatesRun) {
        this.invalidatesRun = invalidatesRun;
    }

    /** True only for the classes that make the whole report {@code INVALID}. */
    public boolean invalidatesRun() {
        return invalidatesRun;
    }

    /**
     * Classifies one attempt from the exact failure code the Agent or refinement boundary published.
     *
     * @param failureCode a {@code SEGMENT_AGENT_*} or {@code SEGMENT_REFINEMENT_*} code, or {@code null}
     */
    public static FailureTaxonomy classify(String failureCode) {
        if (failureCode == null) {
            return COMPARISON_MISMATCH;
        }
        return switch (failureCode) {
            case SegmentAgentErrorCodes.PROVIDER_FAILED -> PROVIDER_OR_INFRASTRUCTURE_FAILURE;
            case SegmentAgentErrorCodes.OBSERVATION_FAILED,
                 SegmentRefinementAdapterErrorCodes.OBSERVATION_FAILED,
                 SegmentAgentErrorCodes.AUDIT_UNAVAILABLE,
                 SegmentRefinementAdapterErrorCodes.AUDIT_UNAVAILABLE -> OBSERVER_OR_AUDIT_FAILURE;
            case SegmentAgentErrorCodes.REQUEST_INVALID,
                 SegmentAgentErrorCodes.METADATA_PIN_MISMATCH,
                 SegmentAgentErrorCodes.MODEL_REQUEST_INVALID,
                 SegmentAgentErrorCodes.SKILL_INCOMPATIBLE,
                 SegmentAgentErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE,
                 SegmentAgentErrorCodes.CONTINUATION_REJECTED,
                 SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                 SegmentRefinementAdapterErrorCodes.METADATA_PIN_MISMATCH,
                 SegmentRefinementAdapterErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE,
                 SegmentRefinementAdapterErrorCodes.SKILL_INCOMPATIBLE,
                 SegmentRefinementAdapterErrorCodes.DSL_BINDING_MISMATCH -> INPUT_OR_PIN_INVALIDITY;
            case SegmentAgentErrorCodes.METADATA_DISCOVERY_REJECTED,
                 SegmentAgentErrorCodes.RESOLUTION_REJECTED,
                 SegmentAgentErrorCodes.TOOL_REJECTED,
                 SegmentAgentErrorCodes.ILLEGAL_TRANSITION,
                 SegmentRefinementAdapterErrorCodes.METADATA_DISCOVERY_REJECTED,
                 SegmentRefinementAdapterErrorCodes.TOOL_REJECTED,
                 SegmentRefinementAdapterErrorCodes.ILLEGAL_TRANSITION -> TOOL_ADMISSION_OR_RESOLUTION_FAILURE;
            case SegmentAgentErrorCodes.DSL_COMPILATION_FAILED,
                 SegmentAgentErrorCodes.VALIDATION_REJECTED,
                 SegmentAgentErrorCodes.PLANNING_FAILED,
                 SegmentAgentErrorCodes.PREVIEW_FAILED,
                 SegmentAgentErrorCodes.EXPLANATION_FAILED,
                 SegmentRefinementAdapterErrorCodes.VALIDATION_REJECTED,
                 SegmentRefinementAdapterErrorCodes.PLANNING_FAILED,
                 SegmentRefinementAdapterErrorCodes.PREVIEW_FAILED,
                 SegmentRefinementAdapterErrorCodes.EXPLANATION_FAILED -> VALIDATION_PLANNING_OR_FIXTURE_FAILURE;
            case SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED -> FIRST_PASS_SCHEMA_REJECTION;
            default -> MODEL_SEMANTIC_MISMATCH;
        };
    }
}