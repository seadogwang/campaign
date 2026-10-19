package com.company.campaign.evaluation.segment.gate;

/**
 * The exact contract versions of the Segment AI Gate.
 *
 * <p>They are constants rather than configuration, so a decision can state the contract it was produced
 * under and a reader can tell a T14 Gate decision from a T13 diagnostic report without guessing.
 */
public final class GateVersions {

    /** The version of the canonical proposed Gate decision contract. */
    public static final String DECISION_SCHEMA_VERSION = "segment-ai-gate-decision/1.0";

    /**
     * The version of the host approval record contract.
     *
     * <p>Version 2.0 carries the complete PRODUCT/DATA/TEST owner-approval quorum instead of one owner role.
     */
    public static final String APPROVAL_SCHEMA_VERSION = "segment-ai-gate-approval/2.0";

    /**
     * The version of the deterministic-layer evidence artifact.
     *
     * <p>Version 2.0 declares the exact {@code GateRunPins.contentHash()} the artifact verifies, so a passing
     * artifact cannot be reused with different gated inputs.
     */
    public static final String DETERMINISTIC_EVIDENCE_VERSION = "segment-ai-gate-deterministic-evidence/2.0";

    /** The version of the capability-readiness projection contract. */
    public static final String READINESS_SCHEMA_VERSION = "segment-ai-gate-readiness/1.0";

    /** The exact number of identical explicit-input replays the deterministic layer must prove. */
    public static final int REQUIRED_REPLAY_COUNT = 100;

    /** The exact number of times a high-risk Case must run. */
    public static final int REQUIRED_HIGH_RISK_ATTEMPTS = 5;

    private GateVersions() {
    }
}