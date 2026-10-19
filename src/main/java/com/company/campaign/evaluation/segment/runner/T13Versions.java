package com.company.campaign.evaluation.segment.runner;

/**
 * The exact contract versions of the T13 Evaluation Runner.
 *
 * <p>They are constants rather than configuration, so a report can state the contract it was produced under
 * and a reader can tell a T13 report from a T03 report format without guessing.
 */
public final class T13Versions {

    /** The version of the immutable run contract frozen before the first Case starts. */
    public static final String RUN_CONTRACT_VERSION = "segment-evaluation-run-contract/1.0";

    /** The version of the emitted report contract. */
    public static final String REPORT_SCHEMA_VERSION = "segment-evaluation-report/1.0";

    /** The version of the bounded comparison-evidence contract. */
    public static final String COMPARISON_VERSION = "segment-evaluation-comparison/1.0";

    /** The version of the deterministic replay identifier derivation. */
    public static final String REPLAY_ID_VERSION = "segment-evaluation-replay/1.0";

    private T13Versions() {
    }
}