package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.evaluation.segment.gate.CapabilityState;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovalLookupPort;
import com.company.campaign.evaluation.segment.gate.ReferenceReadinessProjector;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The one place the host-only Publish boundary re-proves a reference-bearing preparation, and the one place a
 * publication's deterministic retention holds are derived.
 *
 * <p>It is the Publish counterpart of {@link ReferencePreparationCheck}, and it is deliberately a second,
 * independent decision rather than a reuse of the first: preparing is not authority to publish, so every
 * reference fact a preparation froze is re-derived here from trusted host state immediately before anything is
 * committed.
 *
 * <p>The reference-bearing fact is detected from the Plan's own trusted material — the reference evidence its
 * preview card carries — and never from a client flag or a command field. Everything fails closed, with the
 * same dedicated reference codes the Prepare boundary uses, so a reference-bearing Publish that fails its
 * reference recheck is never diagnosable as a base {@code segment.rule-based} failure:
 *
 * <ul>
 *   <li>a reference-free Plan, whose preparation froze no reference evidence, consults no port at all and
 *       acquires no hold, so the accepted T17 path is unchanged;</li>
 *   <li>a preparation whose frozen reference evidence and whose Plan's own reference card disagree is refused,
 *       because the record would misstate what it authorizes;</li>
 *   <li>a host that cannot re-resolve the Plan's references, or that re-resolves a different closure than the
 *       preparation froze, refuses it — a moved version, hash, availability, program relation, DataScope or
 *       retention answer is drift, because the host port is the thing that reauthorizes each exact pin;</li>
 *   <li>a reference readiness that is not exactly {@code ACTIVE}, or whose current reference inputs are not
 *       the frozen revision, or whose approved decision is not the exact decision the preparation froze,
 *       refuses it — a missing, superseded, stale or wrong-capability approval is never a silent downgrade or
 *       a silent upgrade;</li>
 *   <li>a boundary built without the host reference ports refuses a reference-bearing preparation rather than
 *       defaulting to an approval it never had.</li>
 * </ul>
 *
 * <p>Only when all of those hold does the re-resolved closure supply the exact pins, one deterministic hold
 * each, that the Publish transaction acquires atomically with the definition.
 */
public final class ReferencePublishCheck {

    private static final String MESSAGE =
            "The preparation's frozen Existing Audience Reference evidence is not active production evidence.";

    /** The stable prefix of every retention hold this boundary derives, so hold identity is never a version. */
    private static final String HOLD_PREFIX = "retention-hold:";

    private ReferencePublishCheck() {
    }

    /**
     * Re-proves one preparation's frozen reference evidence against trusted host state right now.
     *
     * @return the exact re-resolved closure a retention hold must be acquired for, or {@code null} when the
     *     Plan carries no reference at all — in which case no port was consulted
     * @throws LifecycleException with
     *     {@link LifecycleErrorCodes#LIFECYCLE_REFERENCE_ACTIVATION_REJECTED} for every missing, mismatched or
     *     drifted reference fact, and with
     *     {@link LifecycleErrorCodes#LIFECYCLE_REFERENCE_ACTIVATION_UNAVAILABLE} when no trustworthy answer
     *     could be loaded at all
     */
    public static ResolvedReferenceDependencies require(
            ReferencePreparationLookupPort factsPort,
            ReferenceApprovalLookupPort approvalPort,
            CampaignPlan plan,
            PublishPreparation preparation) {
        ReferencePlanEvidence card = ReferencePreparationCheck.card(plan);
        ReferencePreparationEvidence frozen = preparation.referenceEvidence();
        if (card == null && frozen == null) {
            // A reference-free Plan never consults a reference port at Publish either, so the accepted path
            // acquires no hold and asks the host nothing.
            return null;
        }
        if (card == null || frozen == null) {
            throw rejected("referenceCoherence");
        }
        if (factsPort == null || approvalPort == null) {
            // A boundary built without the host reference ports already refused this Plan at Prepare, so a
            // stored reference-bearing preparation cannot have come through it. Refuse rather than inherit an
            // approval no host supplied.
            throw rejected("referenceWiring");
        }

        ReferencePreparationFacts facts = resolve(factsPort, plan);
        ContentHash resolved = facts.dependencies().dependenciesHash();
        if (!resolved.equals(frozen.referenceDependenciesHash())
                || !resolved.equals(card.referenceDependenciesHash())) {
            // The host re-resolved a different referenced-version closure than the preparation and the Plan
            // both froze: a version, hash, availability, program relation, scope or retention answer moved.
            throw rejected("referenceDependencies");
        }

        SegmentReadinessProjection projection = project(approvalPort, facts);
        if (!CapabilityState.ACTIVE.equals(projection.state())) {
            // No approved PASSED decision over exactly the current reference inputs covers this Plan: the
            // extension is development-only, pending, failed or stale.
            throw rejected("referenceReadiness");
        }
        if (projection.currentInputsHash() == null
                || !projection.currentInputsHash().equals(frozen.referenceInputsHash())) {
            // The activation is real, but over a different reference revision than the one frozen.
            throw rejected("referenceInputs");
        }
        if (projection.approvedDecisionHash() == null
                || !projection.approvedDecisionHash().equals(frozen.approvedDecisionHash())) {
            // The latest approved decision is not the one the preparation froze: a superseding approval is a
            // re-authorization failure, never a silent upgrade.
            throw rejected("referenceDecision");
        }
        return facts.dependencies();
    }

    /**
     * The deterministic retention holds one publication must acquire: one per exact re-resolved version pin.
     *
     * <p>Each hold is keyed by the publication that holds it, so the same publication always derives the same
     * hold identity — a retried transaction re-acquires its own hold idempotently rather than double-counting
     * the dependent — and two different publications can never collide on one hold. Nothing here retains,
     * releases or deletes a version: it only names the holds a host transaction must acquire.
     */
    public static List<AudienceRetentionHold> holds(
            PublishedCampaignDefinition definition, ResolvedReferenceDependencies dependencies) {
        if (dependencies == null || dependencies.versions().isEmpty()) {
            return List.of();
        }
        List<AudienceRetentionHold> holds = new ArrayList<>(dependencies.versions().size());
        for (AudienceVersionRef reference : dependencies.versions()) {
            holds.add(new AudienceRetentionHold(
                    holdId(definition.publishedId(), reference), reference,
                    definition.campaignId(), definition.publishedId(), definition.publishedAt()));
        }
        holds.sort(Comparator.comparing(AudienceRetentionHold::holdId));
        return List.copyOf(holds);
    }

    /**
     * The stable hold identity of one referenced version under one holding publication.
     *
     * <p>It binds the exact publication identity to the exact version pin, never to a version identity alone,
     * and it is a hash so no referenced version or owner value is readable from a hold identifier.
     */
    private static String holdId(String publishedId, AudienceVersionRef reference) {
        ContentHash digest = ContentHash.sha256(
                (publishedId + '|' + reference.pinKey()).getBytes(StandardCharsets.UTF_8));
        return HOLD_PREFIX + digest.value();
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