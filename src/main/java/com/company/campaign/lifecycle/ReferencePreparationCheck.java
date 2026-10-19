package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.evaluation.segment.gate.CapabilityState;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovalLookupPort;
import com.company.campaign.evaluation.segment.gate.ReferenceReadinessProjector;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;

import java.util.Optional;

/**
 * The one place the host-only prepare boundary asks whether a reference-bearing Plan may be frozen.
 *
 * <p>It is the reference counterpart of {@link SegmentActivationCheck}, and it is strictly additive: the
 * accepted base Segment activation is still required exactly as before, and a Plan that carries trusted
 * reference evidence additionally has to prove the dedicated {@code segment.existing-audience-reference}
 * activation. Neither guard substitutes for the other, and an approval of one Gate identity is never read as
 * an approval of the other.
 *
 * <p>The reference-bearing fact is detected from the Plan's own trusted material — the reference evidence its
 * preview card carries — and never from a client flag, a command field or a Tool payload. Everything fails
 * closed:
 *
 * <ul>
 *   <li>a host that cannot resolve the Plan's references, or that resolves none, refuses the preparation;</li>
 *   <li>a resolution whose dependency-graph hash is not exactly the hash the Plan froze refuses it, so a
 *       referenced version that moved since the compile can never be silently frozen;</li>
 *   <li>a reference readiness that is not exactly {@code ACTIVE} refuses it, which covers a missing, pending,
 *       failed, wrong-capability or stale approved decision — there is no development state a production
 *       preparation may fall back to;</li>
 *   <li>an {@code ACTIVE} readiness whose current reference inputs are not exactly the revision the Plan froze
 *       refuses it, so the projection's own label is re-proved against the Plan rather than trusted.</li>
 * </ul>
 *
 * <p>Only when all of those hold is the activation's approved-decision provenance frozen into the preparation
 * alongside the exact dependency and input pins it rests on.
 */
public final class ReferencePreparationCheck {

    private static final String MESSAGE =
            "The exact ACTIVE production Existing Audience Reference activation evidence is not available.";

    private ReferencePreparationCheck() {
    }

    /**
     * The one dedicated reference evidence a Plan's trusted material carries, or {@code null} when it carries
     * none.
     *
     * <p>A Plan with more than one reference-bearing card cannot be prepared at all: a preparation freezes one
     * reference activation, so a Plan whose own frozen material would leave that activation ambiguous is
     * refused here rather than misstated later.
     */
    public static ReferencePlanEvidence card(CampaignPlan plan) {
        ReferencePlanEvidence found = null;
        int bearing = 0;
        for (PlanPreviewCard card : plan.previewCards()) {
            ReferencePlanEvidence evidence = card.referenceEvidence();
            if (evidence != null) {
                bearing++;
                found = evidence;
            }
        }
        if (bearing == 0) {
            return null;
        }
        if (bearing != 1) {
            throw rejected("referenceCoherence");
        }
        return found;
    }

    /**
     * Requires the exact dedicated reference activation of one reference-bearing Plan, or fails closed.
     *
     * @return the reference evidence to freeze into the preparation
     * @throws LifecycleException with
     *     {@link LifecycleErrorCodes#LIFECYCLE_REFERENCE_ACTIVATION_REJECTED} for every missing, mismatched or
     *     non-{@code ACTIVE} reference fact, and with
     *     {@link LifecycleErrorCodes#LIFECYCLE_REFERENCE_ACTIVATION_UNAVAILABLE} when no trustworthy answer
     *     could be loaded at all
     */
    public static ReferencePreparationEvidence require(
            ReferencePreparationLookupPort factsPort,
            ReferenceApprovalLookupPort approvalPort,
            CampaignPlan plan,
            ReferencePlanEvidence card) {
        if (factsPort == null || approvalPort == null) {
            // A boundary built without the host reference ports holds no reference approval to re-prove. It
            // refuses rather than defaulting to one it was never given.
            throw rejected("referenceWiring");
        }
        ReferencePreparationFacts facts = resolve(factsPort, plan);
        if (!facts.dependencies().dependenciesHash().equals(card.referenceDependenciesHash())) {
            // The host resolved a different referenced-version closure than the Plan froze.
            throw rejected("referenceDependencies");
        }
        SegmentReadinessProjection projection = project(approvalPort, facts);
        if (!CapabilityState.ACTIVE.equals(projection.state())) {
            // No approved PASSED decision over exactly the current reference inputs covers this Plan: the
            // extension is development-only, pending, failed, stale or bound to another capability.
            throw rejected("referenceReadiness");
        }
        if (projection.currentInputsHash() == null
                || !projection.currentInputsHash().equals(card.referenceInputsHash())) {
            // The activation is real, but over a different reference revision than the Plan's own evidence.
            throw rejected("referenceInputs");
        }
        if (projection.approvedDecisionHash() == null) {
            throw rejected("referenceDecision");
        }
        return new ReferencePreparationEvidence(
                ReferencePlanEvidence.CAPABILITY_ID,
                facts.dependencies().dependenciesHash(),
                projection.currentInputsHash(),
                projection.approvedDecisionHash());
    }

    private static ReferencePreparationFacts resolve(
            ReferencePreparationLookupPort factsPort, CampaignPlan plan) {
        Optional<ReferencePreparationFacts> loaded;
        try {
            loaded = factsPort.factsFor(plan);
        } catch (RuntimeException unavailable) {
            // A resolver that cannot answer is indistinguishable from one that refuses; neither is "no
            // reference dependencies", because the Plan's own evidence says it depends on some.
            throw unavailableEvidence("referenceResolution");
        }
        if (loaded == null || loaded.isEmpty()) {
            throw rejected("referenceDependencies");
        }
        return loaded.get();
    }

    private static SegmentReadinessProjection project(
            ReferenceApprovalLookupPort approvalPort, ReferencePreparationFacts facts) {
        try {
            return new ReferenceReadinessProjector(approvalPort)
                    .project(ReferencePlanEvidence.CAPABILITY_ID, facts.inputs());
        } catch (RuntimeException malformed) {
            throw unavailableEvidence("referenceReadiness");
        }
    }

    private static LifecycleException rejected(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_REJECTED, invariant, MESSAGE);
    }

    private static LifecycleException unavailableEvidence(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_UNAVAILABLE,
                invariant,
                "No trustworthy production Existing Audience Reference evidence was available.");
    }
}