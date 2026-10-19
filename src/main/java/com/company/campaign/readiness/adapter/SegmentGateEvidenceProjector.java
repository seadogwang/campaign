package com.company.campaign.readiness.adapter;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.gate.GateApprovedDecision;
import com.company.campaign.evaluation.segment.gate.GateRunPins;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessErrorCodes;
import com.company.campaign.readiness.ReadinessException;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessSupport;
import com.company.campaign.readiness.RequirementEvidence;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;

/**
 * The trusted projector that turns the accepted Segment AI Gate state into readiness evidence.
 *
 * <p>Only one state satisfies the AI requirement: a latest approved decision whose approval evidence
 * re-derives to its declared hashes, whose outcome is {@code PASSED}, whose corpus was eligible for a Gate,
 * and whose frozen gated pins are exactly the current ones. Every other state becomes a truthful
 * {@code BLOCKED} record naming the exact reason — a missing approval, an invalid approval, a failed or
 * invalid run, a development-only corpus, absent pins or a stale pin bundle.
 *
 * <p>The projector never invents evidence: a host that owns no Gate decision calls {@link GateFacts#absent}
 * and receives a record that blocks the requirement rather than one that quietly omits it.
 */
public final class SegmentGateEvidenceProjector {

    /** Evidence identifier suffix for the Gate approval requirement. */
    public static final String GATE_SUFFIX = "-gate";

    /** Evidence identifier suffix for the current-gated-pins requirement. */
    public static final String PINS_SUFFIX = "-pins";

    /** Evidence identifier suffix for the pin-change-triggers-evaluation requirement. */
    public static final String TRIGGER_SUFFIX = "-pin-trigger";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private SegmentGateEvidenceProjector() {
    }

    /**
     * The exact, minimal Gate facts a host may extract from the accepted Gate contracts.
     *
     * <p>It carries booleans, one closed verdict and canonical hashes — never a report body, a model output,
     * a corpus case or a human approval note.
     */
    public record GateFacts(
            String capabilityId,
            boolean approvalPresent,
            boolean approvalValid,
            GateOutcome outcome,
            boolean corpusGateEligible,
            ContentHash approvedPinsHash,
            ContentHash currentPinsHash,
            ContentHash decisionHash) {

        public GateFacts {
            ReadinessSupport.requireId(capabilityId, "capabilityId");
            if (approvalPresent && (outcome == null || decisionHash == null)) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_EVIDENCE_INVALID,
                        "A present Gate approval must name its verdict and its decision hash.");
            }
        }

        /** True when a host owns no approved decision at all for this capability. */
        public static GateFacts absent(String capabilityId) {
            return new GateFacts(capabilityId, false, false, null, false, null, null, null);
        }

        /**
         * Extracts the facts from the accepted Gate contracts: the looked-up approved decision and the
         * current frozen gated pins the live inputs produce.
         */
        public static GateFacts from(GateApprovedDecision approved, GateRunPins currentPins) {
            if (approved == null) {
                throw new ReadinessException(
                        ReadinessErrorCodes.READINESS_EVIDENCE_INVALID,
                        "An approved Gate decision is required to project Gate facts.");
            }
            ContentHash approvedPins = approved.decision().runPins().contentHash();
            ContentHash current = currentPins == null ? null : currentPins.contentHash();
            return new GateFacts(
                    approved.capabilityId(),
                    true,
                    approved.isValid(),
                    approved.decision().outcome(),
                    approved.decision().runPins().review().classification().isGateEligible(),
                    approvedPins,
                    current,
                    approved.decision().decisionHash());
        }

        /** The canonical hash of these facts, used as the exact evidence hash. */
        public ContentHash factsHash() {
            return CANONICAL_JSON.hash(this);
        }
    }

    /**
     * Projects the three AI Segment requirements the Gate can speak to.
     *
     * @param context the trusted host context; the origin and environment are derived from it, never asserted
     * @param evidenceIdPrefix the bounded prefix the three evidence identifiers are derived from
     */
    public static List<RequirementEvidence> project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            GateFacts facts,
            Instant observedAt,
            Instant expiresAt) {
        ReadinessHostContext host = ReadinessSupport.requireNonNull(context, "context");
        GateFacts value = ReadinessSupport.requireNonNull(facts, "facts");
        String prefix = ReadinessSupport.requireId(evidenceIdPrefix, "evidenceIdPrefix");
        Instant observed = ReadinessSupport.requireInstant(observedAt, "observedAt");

        return List.of(
                evidence(host, prefix, GATE_SUFFIX, ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED,
                        gateStatus(value), value, observed, expiresAt),
                evidence(host, prefix, PINS_SUFFIX, ReadinessRequirement.AI_GATED_PINS_CURRENT,
                        pinsStatus(value), value, observed, expiresAt),
                evidence(host, prefix, TRIGGER_SUFFIX,
                        ReadinessRequirement.AI_GATED_PIN_CHANGE_REQUIRES_EVALUATION,
                        triggerStatus(value), value, observed, expiresAt));
    }

    private static String gateStatus(GateFacts facts) {
        if (!facts.approvalPresent()) {
            return ReadinessReasonCodes.AI_GATE_APPROVAL_MISSING;
        }
        if (!facts.approvalValid()) {
            return ReadinessReasonCodes.AI_GATE_NOT_APPROVED;
        }
        if (facts.outcome() == GateOutcome.INVALID) {
            return ReadinessReasonCodes.AI_GATE_OUTCOME_INVALID;
        }
        if (facts.outcome() == GateOutcome.FAILED) {
            return ReadinessReasonCodes.AI_GATE_OUTCOME_FAILED;
        }
        if (!facts.corpusGateEligible()) {
            return ReadinessReasonCodes.AI_GATE_DEVELOPMENT_ONLY;
        }
        if (facts.approvedPinsHash() == null || facts.currentPinsHash() == null) {
            return ReadinessReasonCodes.AI_GATED_PINS_ABSENT;
        }
        if (!facts.approvedPinsHash().equals(facts.currentPinsHash())) {
            return ReadinessReasonCodes.AI_GATED_PINS_STALE;
        }
        return null;
    }

    private static String pinsStatus(GateFacts facts) {
        if (facts.approvedPinsHash() == null || facts.currentPinsHash() == null) {
            return ReadinessReasonCodes.AI_GATED_PINS_ABSENT;
        }
        if (!facts.approvedPinsHash().equals(facts.currentPinsHash())) {
            return ReadinessReasonCodes.AI_GATED_PINS_STALE;
        }
        return null;
    }

    private static String triggerStatus(GateFacts facts) {
        if (facts.approvedPinsHash() == null || facts.currentPinsHash() == null) {
            return ReadinessReasonCodes.AI_GATED_PINS_ABSENT;
        }
        if (!facts.approvedPinsHash().equals(facts.currentPinsHash())) {
            // A gated pin changed since the approved decision, so evaluation is required again.
            return ReadinessReasonCodes.AI_GATED_PIN_CHANGE_REQUIRES_EVALUATION;
        }
        return null;
    }

    private static RequirementEvidence evidence(
            ReadinessHostContext context,
            String prefix,
            String suffix,
            ReadinessRequirement requirement,
            String reasonCode,
            GateFacts facts,
            Instant observedAt,
            Instant expiresAt) {
        boolean production = context.environment().production();
        EvidenceOrigin origin = production ? EvidenceOrigin.PRODUCTION : EvidenceOrigin.REAL_LOCAL;
        ContentHash version = facts.currentPinsHash() != null
                ? facts.currentPinsHash()
                : (facts.decisionHash() != null ? facts.decisionHash() : facts.factsHash());
        EvidenceSubmission submission = new EvidenceSubmission(
                prefix + suffix,
                requirement,
                reasonCode == null ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                origin,
                facts.capabilityId(),
                version.value(),
                facts.factsHash(),
                "readiness/segment-ai-gate",
                production,
                observedAt,
                expiresAt,
                reasonCode);
        return RequirementEvidence.of(context.environment(), submission);
    }
}