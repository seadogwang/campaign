package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded evidence of compiling one fixed explicit input exactly 100 times and comparing the results.
 *
 * <p>Exact member identifiers, utterances and model output never appear here. The repeated output is
 * represented by the single hash every replay must produce and the count of distinct hashes actually
 * observed: one distinct hash over exactly 100 replays is the whole claim, and a second distinct hash proves
 * non-determinism without disclosing either output.
 */
public record ReplayDeterminismEvidence(int replayCount, int distinctOutcomeHashCount, ContentHash replayHash) {

    public ReplayDeterminismEvidence {
        if (replayHash == null) {
            throw GateException.of(
                    GateErrorCodes.REPLAY_DETERMINISM_FAILED,
                    "The replay evidence requires the common outcome hash of the repeated replays");
        }
        if (replayCount < 1) {
            throw GateException.of(
                    GateErrorCodes.REPLAY_DETERMINISM_FAILED, "The replay evidence requires at least one replay");
        }
        if (distinctOutcomeHashCount < 1 || distinctOutcomeHashCount > replayCount) {
            throw GateException.of(
                    GateErrorCodes.REPLAY_DETERMINISM_FAILED,
                    "The distinct outcome-hash count must lie within the replay count");
        }
    }

    /** Exactly 100 replays that all produced the same hash. */
    public static ReplayDeterminismEvidence identical(int replayCount, ContentHash replayHash) {
        return new ReplayDeterminismEvidence(replayCount, 1, replayHash);
    }

    /** True only for exactly the required number of identical replays. */
    public boolean isSatisfied() {
        return replayCount == GateVersions.REQUIRED_REPLAY_COUNT && distinctOutcomeHashCount == 1;
    }
}