package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The canonical proposed reference Gate decision of one Existing Audience Reference evaluation.
 *
 * <p>It is a <em>proposal</em>, never an approval, and it is its own Gate identity: an approval of the base
 * {@code segment.rule-based} capability is never an approval of this one. It binds the reference verdict and
 * the complete frozen {@link ReferenceReadinessInputs} the run was judged against, plus the explicit
 * host-supplied decision timestamp. It carries no owner, no approval and no capability state: only a host
 * approval may turn it into an {@link ReferenceApprovedDecision}.
 *
 * <p>{@link #decisionHash()} covers the decision with the hash field excluded, and {@link #of} is the only way
 * to build a decision carrying a correct hash. {@link #recomputeHash()} re-derives it, so a forged or stale
 * reference decision is detectable before any approval.
 */
public record ReferenceGateDecision(
        String decisionSchemaVersion,
        String decisionId,
        GateOutcome outcome,
        ReferenceReadinessInputs inputs,
        Instant decidedAt,
        ContentHash decisionHash
) {

    /** The exact contract version of the reference Gate decision. */
    public static final String DECISION_SCHEMA_VERSION = "segment-reference-gate-decision/1.0";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReferenceGateDecision {
        if (!DECISION_SCHEMA_VERSION.equals(decisionSchemaVersion)) {
            throw GateException.of(
                    GateErrorCodes.REFERENCE_INPUT_INVALID,
                    "A reference Gate decision must declare the exact reference decision contract version");
        }
        if (decisionId == null || !REFERENCE_ID.matcher(decisionId).matches()) {
            throw GateException.of(
                    GateErrorCodes.REFERENCE_INPUT_INVALID,
                    "A reference Gate decision requires a bounded decision id");
        }
        if (outcome == null || inputs == null || decidedAt == null) {
            throw GateException.of(
                    GateErrorCodes.REFERENCE_INPUT_INVALID,
                    "A reference Gate decision requires its verdict and frozen reference inputs");
        }
    }

    /** Builds a decision and stamps it with its own canonical hash. */
    public static ReferenceGateDecision of(
            String decisionId, GateOutcome outcome, ReferenceReadinessInputs inputs, Instant decidedAt) {
        ReferenceGateDecision withoutHash = new ReferenceGateDecision(
                DECISION_SCHEMA_VERSION, decisionId, outcome, inputs, decidedAt, null);
        return withoutHash.withDecisionHash(CANONICAL_JSON.hash(withoutHash.material()));
    }

    /** The same decision carrying the given hash. */
    public ReferenceGateDecision withDecisionHash(ContentHash hash) {
        return new ReferenceGateDecision(
                decisionSchemaVersion, decisionId, outcome, inputs, decidedAt, hash);
    }

    /** The canonical bytes the decision hash covers: the decision without its own hash component. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material());
    }

    /** The canonical decision hash with the hash field excluded. */
    public ContentHash recomputeHash() {
        return CANONICAL_JSON.hash(material());
    }

    private Object material() {
        return new Material(decisionSchemaVersion, decisionId, outcome, inputs, decidedAt);
    }

    /** The self-excluded hash material: every component except the decision hash itself. */
    private record Material(
            String decisionSchemaVersion,
            String decisionId,
            GateOutcome outcome,
            ReferenceReadinessInputs inputs,
            Instant decidedAt
    ) {
    }
}