package com.company.campaign.evaluation.segment.gate;

/**
 * Stable machine-readable codes for the Segment AI Gate (T14).
 *
 * <p>Every code is part of the Gate wire contract. A Gate decision carries the codes that explain its
 * {@code INVALID} or {@code FAILED} verdict, and host readiness code branches on them, so an existing code
 * must never be renamed or reused for a different meaning. A new failure mode gets a new code.
 *
 * <p>These codes are deliberately separate from the T13 evaluation-asset codes: a Gate verdict must never be
 * confusable with a corpus-loading failure. The frozen T13 review issue codes are preserved verbatim in
 * bounded decision evidence rather than re-coded.
 */
public final class GateErrorCodes {

    // --- Report eligibility and pins -------------------------------------------------------------

    /** The declared T13 report hash does not match the report's recomputed canonical hash. */
    public static final String REPORT_HASH_MISMATCH = "GATE_REPORT_HASH_MISMATCH";

    /** A frozen candidate-corpus or review pin does not match the report contract. */
    public static final String REPORT_PIN_MISMATCH = "GATE_REPORT_PIN_MISMATCH";

    /** The report was not frozen as a Gate-eligible revision. */
    public static final String REPORT_NOT_GATE_ELIGIBLE = "GATE_REPORT_NOT_GATE_ELIGIBLE";

    /** The run itself was unsound, so no threshold could be judged. */
    public static final String REPORT_RUN_INVALID = "GATE_REPORT_RUN_INVALID";

    /** The report does not account for every required attempt. */
    public static final String ATTEMPTS_UNACCOUNTED = "GATE_ATTEMPTS_UNACCOUNTED";

    /** A required threshold metric is absent from the report evidence. */
    public static final String THRESHOLD_EVIDENCE_MISSING = "GATE_THRESHOLD_EVIDENCE_MISSING";

    /** A required threshold metric is undefined, carries invalid attempts or is duplicated. */
    public static final String THRESHOLD_EVIDENCE_INVALID = "GATE_THRESHOLD_EVIDENCE_INVALID";

    /** Two metric values in one scope declare the same metric identifier. */
    public static final String METRIC_EVIDENCE_DUPLICATE = "GATE_METRIC_EVIDENCE_DUPLICATE";

    /** High-risk Cases do not declare the exact five required attempts. */
    public static final String HIGH_RISK_REPEAT_REQUIREMENT_INVALID = "GATE_HIGH_RISK_REPEAT_REQUIREMENT_INVALID";

    // --- Deterministic layer ---------------------------------------------------------------------

    /** The deterministic evidence artifact omits one or more required checks. */
    public static final String DETERMINISTIC_EVIDENCE_INCOMPLETE = "GATE_DETERMINISTIC_EVIDENCE_INCOMPLETE";

    /** A required deterministic check is below 100%. */
    public static final String DETERMINISTIC_CHECK_FAILED = "GATE_DETERMINISTIC_CHECK_FAILED";

    /** The 100-repeat determinism evidence is not exactly 100 identical replays. */
    public static final String REPLAY_DETERMINISM_FAILED = "GATE_REPLAY_DETERMINISM_FAILED";

    /**
     * The deterministic evidence artifact does not declare the exact run-pin hash of the report it verifies.
     * The binding is missing or names different gated inputs, so the artifact cannot be reused across inputs.
     */
    public static final String DETERMINISTIC_EVIDENCE_PIN_MISMATCH =
            "GATE_DETERMINISTIC_EVIDENCE_PIN_MISMATCH";

    // --- Review readiness ------------------------------------------------------------------------

    /** The frozen corpus review evidence is pending, rejected, missing, stale or otherwise not ready. */
    public static final String REVIEW_EVIDENCE_NOT_READY = "GATE_REVIEW_EVIDENCE_NOT_READY";

    // --- Threshold result ------------------------------------------------------------------------

    /** At least one required threshold was missed by an otherwise sound, Gate-eligible run. */
    public static final String THRESHOLD_NOT_MET = "GATE_THRESHOLD_NOT_MET";

    // --- Approval --------------------------------------------------------------------------------

    /** An approval command or decision is null, malformed or internally inconsistent. */
    public static final String APPROVAL_INVALID = "GATE_APPROVAL_INVALID";

    /** The approval did not name the exact proposed-decision hash, or the decision hash is forged/stale. */
    public static final String APPROVAL_DECISION_HASH_MISMATCH = "GATE_APPROVAL_DECISION_HASH_MISMATCH";

    /** Trusted host authorization did not explicitly approve the exact owner, role and decision hash. */
    public static final String APPROVAL_UNAUTHORIZED = "GATE_APPROVAL_UNAUTHORIZED";

    /**
     * The approval evidence does not contain exactly one accountable owner approval for each of the PRODUCT,
     * DATA and TEST roles, so the approval quorum is incomplete.
     */
    public static final String APPROVAL_QUORUM_INCOMPLETE = "GATE_APPROVAL_QUORUM_INCOMPLETE";

    // --- Capability readiness --------------------------------------------------------------------

    /** No approved Gate decision exists for the capability. */
    public static final String APPROVAL_MISSING = "GATE_APPROVAL_MISSING";

    /** The latest approved Gate decision is not {@code PASSED}. */
    public static final String NOT_PASSED = "GATE_NOT_PASSED";

    /** A gated input differs from the latest approved {@code PASSED} record's frozen pins. */
    public static final String GATED_INPUT_CHANGED = "GATE_GATED_INPUT_CHANGED";

    /** A capability outside the single Gate-gated Segment capability was asked to activate. */
    public static final String CAPABILITY_NOT_GATED = "GATE_CAPABILITY_NOT_GATED";

    // --- Existing Audience Reference readiness ---------------------------------------------------

    /**
     * A reference Gate decision was approved but its owner quorum is not yet complete, so the reference
     * capability is pending rather than failed.
     */
    public static final String REFERENCE_APPROVAL_PENDING = "GATE_REFERENCE_APPROVAL_PENDING";

    /**
     * The compile carries no reference dependencies, so it supplies no reference inputs and consults no
     * reference Gate port at all.
     */
    public static final String REFERENCE_NOT_PRESENT = "GATE_REFERENCE_NOT_PRESENT";

    /** A dedicated reference Gate input, version, graph format or decision is null, malformed or unbounded. */
    public static final String REFERENCE_INPUT_INVALID = "GATE_REFERENCE_INPUT_INVALID";

    private GateErrorCodes() {
    }
}