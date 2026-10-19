package com.company.campaign.builder.authoring;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.evaluation.segment.gate.CapabilityState;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The narrow, fail-closed production activation guard of the {@code segment.rule-based} capability.
 *
 * <p>It is a <em>port</em>, not a path: it performs no Apply, no Save, no Publish and no Runtime work, and it
 * stores, mutates, recompiles and publishes nothing. It exists so a later T17/T21/T22 production path has one
 * place to ask whether the Segment capability may be consumed for real, and it answers with a single bounded
 * refusal or a single silent acceptance.
 *
 * <p>It accepts exactly one shape of evidence: a T14 {@link SegmentReadinessProjection} whose capability is
 * this one, whose state is exactly {@code ACTIVE}, whose {@code aiReady} projection is true, and which carries
 * both an approved decision hash and a current Gate-input hash — together with a compiled {@link CampaignPlan}
 * that holds exactly one bound {@code SEGMENT_PREVIEW} card of the <em>real</em> rule-based Segment component
 * (the card's {@code componentLogicalId} resolves in the Plan Manifest to a component whose capability code and
 * contract version are exactly this capability's), whose own readiness is {@code ACTIVE} and whose Gate-input
 * and approved-decision pins are precisely the projection's.
 *
 * <p><strong>Provenance.</strong> The projection is host-owned T14 projection evidence that the caller supplies:
 * this guard consumes it as a typed fact and loads no Gate evidence itself — it reads no Gate store, runs no
 * gate evaluation and re-derives no readiness. It therefore claims no cryptographic provenance beyond the typed
 * projection it was handed; the exactness it enforces is pin equality and component identity, not a signature.
 *
 * <p>Everything else fails closed: an {@code EXPERIMENTAL} or {@code EVALUATION_REQUIRED} projection, a
 * projection of another capability, a plan that carries no segment card, a plan compiled under development
 * readiness, a card bound to a component of another capability, a stale Gate-input hash, a superseded decision,
 * and absent or malformed evidence. Development authoring and Preview remain available while the capability is
 * non-active, because this guard only gates a production consumption that does not yet exist.
 */
public final class SegmentActivationGuard {

    private static final String MESSAGE =
            "The rule-based Segment capability is not production-activatable.";

    private SegmentActivationGuard() {
    }

    /**
     * Requires that one compiled Segment Plan may be consumed by a production path, or fails closed.
     *
     * @throws SegmentAuthoringException with {@link SegmentAuthoringErrorCodes#ACTIVATION_REJECTED} for every
     *     non-{@code ACTIVE} projection and every malformed, stale or mismatched piece of evidence
     */
    public static void requireActivatable(SegmentReadinessProjection readiness, CampaignPlan plan) {
        if (readiness == null || plan == null) {
            reject("activationEvidence");
        }
        if (!SegmentAuthoringVersions.CAPABILITY_CODE.equals(readiness.capabilityId())) {
            reject("activationCapability");
        }
        if (!CapabilityState.ACTIVE.equals(readiness.state()) || !readiness.aiReady()) {
            reject("activationState");
        }
        ContentHash approvedDecision = readiness.approvedDecisionHash();
        ContentHash gateInputs = readiness.currentInputsHash();
        if (approvedDecision == null || gateInputs == null) {
            reject("activationPins");
        }
        List<PlanPreviewCard> cards = segmentCards(plan);
        if (cards.size() != 1) {
            reject("activationCards");
        }
        PlanPreviewCard card = cards.get(0);
        requireRealSegmentComponent(plan, card);
        if (!CapabilityReadiness.ACTIVE.equals(card.readinessState())) {
            // A Plan compiled under development readiness is development evidence; a later ACTIVE projection
            // must never relabel it as production-ready.
            reject("activationCardReadiness");
        }
        if (!gateInputs.equals(card.gateInputsHash())) {
            reject("activationGateInputs");
        }
        if (!approvedDecision.equals(card.approvedDecisionHash())) {
            reject("activationDecision");
        }
    }

    /** The non-throwing form of the same single implementation, for a host that branches instead of failing. */
    public static boolean isActivatable(SegmentReadinessProjection readiness, CampaignPlan plan) {
        try {
            requireActivatable(readiness, plan);
            return true;
        } catch (SegmentAuthoringException rejected) {
            return false;
        }
    }

    /**
     * Requires the sole {@code SEGMENT_PREVIEW} card to be the projection of a real rule-based Segment
     * component of this exact contract version.
     *
     * <p>A card kind is a presentation fact that any {@link CapabilityContract} could emit, so
     * {@code SEGMENT_PREVIEW} alone proves nothing about what the card projects. The guard therefore resolves
     * the card's own {@code componentLogicalId} in the Plan Manifest and requires a component whose capability
     * code and contract version are exactly this capability's. A foreign capability that emits a forged
     * {@code SEGMENT_PREVIEW} card — even with matching readiness and Gate hashes — is rejected here, because
     * binding readiness evidence to the wrong component would activate semantics that were never gated.
     */
    private static void requireRealSegmentComponent(CampaignPlan plan, PlanPreviewCard card) {
        ResolvedComponent component = plan.manifest().component(card.componentLogicalId());
        if (component == null
                || !SegmentAuthoringVersions.CAPABILITY_CODE.equals(component.capabilityCode())
                || !SegmentAuthoringVersions.CONTRACT_VERSION.equals(component.contractVersion())) {
            reject("activationComponent");
        }
    }

    /** Every bound card of the one closed segment card kind, in plan order. */
    private static List<PlanPreviewCard> segmentCards(CampaignPlan plan) {
        List<PlanPreviewCard> cards = new ArrayList<>();
        for (PlanPreviewCard card : plan.previewCards()) {
            if (PlanPreviewCard.SEGMENT_PREVIEW.equals(card.cardKind())) {
                cards.add(card);
            }
        }
        return cards;
    }

    private static void reject(String invariant) {
        throw new SegmentAuthoringException(SegmentAuthoringErrorCodes.ACTIVATION_REJECTED, invariant, MESSAGE);
    }
}