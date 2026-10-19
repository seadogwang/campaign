package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The complete, immutable deterministic-layer evidence artifact of one Gate evaluation.
 *
 * <p>It is supplied by the trusted host that ran the deterministic suite, never by a model, a corpus asset or
 * the evaluation runner. The Gate neither reruns the deterministic layer nor manufactures its evidence: it
 * validates that the artifact names every required check exactly once, that every check, plus the 100-repeat
 * determinism evidence, passed, and — critically — that the artifact is bound to the exact
 * {@link GateRunPins#contentHash()} of the report it verifies.
 *
 * <p>Structural strictness is applied at construction — unknown checks and duplicate checks are rejected — so
 * a fabricated artifact cannot smuggle an unidentified or double-counted check past the Gate. A missing
 * required check is representable, because reporting it as an {@code INVALID} Gate issue is more useful than
 * an unconstructible value; {@link #isComplete()} names that condition. A missing pin binding is representable
 * for the same reason: {@link #bindsRunPins(GateRunPins)} names it and the evaluator reports it as
 * {@code INVALID} rather than evaluating thresholds against reusable evidence.
 */
public record DeterministicLayerEvidence(
        String evidenceVersion,
        ContentHash runPinsHash,
        List<DeterministicCheck> checks,
        ReplayDeterminismEvidence replayDeterminism
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public DeterministicLayerEvidence {
        if (!GateVersions.DETERMINISTIC_EVIDENCE_VERSION.equals(evidenceVersion)) {
            throw GateException.of(
                    GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE,
                    "Deterministic evidence must declare the exact T14 evidence version");
        }
        if (checks == null) {
            throw GateException.of(
                    GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE,
                    "Deterministic evidence must declare its check list");
        }
        // Nulls and duplicates are rejected before the defensive copy, because a copying helper would
        // fail with a bare NullPointerException instead of the bounded Gate code.
        List<DeterministicCheck> validated = new ArrayList<>(checks.size());
        Set<DeterministicCheckId> seen = new LinkedHashSet<>();
        for (DeterministicCheck check : checks) {
            if (check == null) {
                throw GateException.of(
                        GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE,
                        "Deterministic evidence contains a null check");
            }
            if (!seen.add(check.checkId())) {
                throw GateException.of(
                        GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE,
                        "Deterministic evidence names check " + check.checkId() + " more than once");
            }
            validated.add(check);
        }
        checks = List.copyOf(validated);
        if (replayDeterminism == null) {
            throw GateException.of(
                    GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE,
                    "Deterministic evidence must declare its 100-repeat determinism result");
        }
        // The run-pin binding may be absent. It is not defaulted to anything: a missing binding is explicit
        // and the evaluator reports it as INVALID, never as a passing check.
    }

    /**
     * A complete, fully passing deterministic artifact bound to one exact run-pin hash.
     *
     * <p>Every ordinary check runs {@code inputsPerCheck} explicit inputs and passes; the replay-determinism
     * check always runs exactly the required 100 replays, because that count is the requirement itself.
     */
    public static DeterministicLayerEvidence complete(
            ContentHash runPinsHash, int inputsPerCheck, ReplayDeterminismEvidence replayDeterminism) {
        List<DeterministicCheck> checks = new ArrayList<>(DeterministicCheckId.ordered().size());
        for (DeterministicCheckId id : DeterministicCheckId.ordered()) {
            int total = id == DeterministicCheckId.REPLAY_DETERMINISM
                    ? GateVersions.REQUIRED_REPLAY_COUNT
                    : inputsPerCheck;
            checks.add(DeterministicCheck.passing(id, total));
        }
        return new DeterministicLayerEvidence(
                GateVersions.DETERMINISTIC_EVIDENCE_VERSION, runPinsHash, checks, replayDeterminism);
    }

    /** True only when every required check is named exactly once. */
    public boolean isComplete() {
        Set<DeterministicCheckId> present = new LinkedHashSet<>();
        for (DeterministicCheck check : checks) {
            present.add(check.checkId());
        }
        return present.containsAll(DeterministicCheckId.ordered());
    }

    /** True only when every named check ran at least one input and passed all of them. */
    public boolean allChecksPassing() {
        for (DeterministicCheck check : checks) {
            if (!check.isComplete()) {
                return false;
            }
        }
        return true;
    }

    /**
     * True only when the replay-determinism check and its detailed evidence agree on exactly the required
     * number of identical replays.
     */
    public boolean replayDeterminismSatisfied() {
        if (!replayDeterminism.isSatisfied()) {
            return false;
        }
        for (DeterministicCheck check : checks) {
            if (check.checkId() == DeterministicCheckId.REPLAY_DETERMINISM) {
                return check.total() == GateVersions.REQUIRED_REPLAY_COUNT && check.passed() == check.total();
            }
        }
        return false;
    }

    /**
     * True only when this artifact declares the exact run-pin hash of the report it verifies.
     *
     * <p>A missing binding is not a match, so evidence that names no gated inputs can never be accepted.
     */
    public boolean bindsRunPins(GateRunPins runPins) {
        return runPinsHash != null && runPins != null && runPinsHash.equals(runPins.contentHash());
    }

    /** The canonical hash of this artifact, for bounded decision evidence. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(this);
    }
}