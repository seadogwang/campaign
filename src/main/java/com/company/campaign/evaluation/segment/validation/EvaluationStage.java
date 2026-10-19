package com.company.campaign.evaluation.segment.validation;

/**
 * Which rule set produced a validation report.
 *
 * <p>The stages are cumulative in meaning — candidate rules include the semantic ones, and Gate rules
 * include the candidate ones — and they exist so a clean report cannot be misread as a stronger claim than
 * it is. A candidate corpus that passes every machine check is still not Gate-ready until three real owner
 * approvals exist, and no stage here is a {@code PASSED} Gate verdict.
 */
public enum EvaluationStage {

    /** Meaning-level checks only: fixture resolution, member sets, spans, disposition coherence. */
    SEMANTIC,

    /** Semantic checks plus corpus quality, T05 DSL binding, Metadata payload and review-ledger structure. */
    CANDIDATE,

    /** Candidate checks plus the human-approval readiness rules. */
    GATE
}