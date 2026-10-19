package com.company.campaign.lifecycle;

import com.company.campaign.builder.authoring.SegmentActivationGuard;
import com.company.campaign.builder.authoring.SegmentAuthoringException;
import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;

import java.util.Optional;

/**
 * The one place the host-only lifecycle boundary asks the accepted T16 {@code SegmentActivationGuard}
 * whether a compiled Segment Plan may be consumed for real.
 *
 * <p>Both production steps call it: Prepare Publish, so a preparation is never even frozen for a Segment
 * that could not be published, and Publish itself, so the authority to publish is re-proved at the instant
 * of publication rather than inherited from the preparation.
 *
 * <p>Readiness evidence is loaded through the host-only {@link SegmentReadinessLookupPort} and never
 * accepted on a public command, a Tool payload or a model output. Everything fails closed: a port that
 * cannot answer, an empty projection, a projection of another capability and every projection the guard
 * itself rejects all refuse the operation, and none of them is distinguishable from the others outward.
 */
public final class SegmentActivationCheck {

    private static final String MESSAGE =
            "The exact ACTIVE production Segment activation evidence is not available.";

    private SegmentActivationCheck() {
    }

    /**
     * Loads the latest host-owned readiness projection and requires it to activate this exact compiled Plan.
     *
     * @throws LifecycleException with {@link LifecycleErrorCodes#LIFECYCLE_ACTIVATION_REJECTED} for missing,
     *     malformed, stale or mismatched evidence, and with
     *     {@link LifecycleErrorCodes#LIFECYCLE_ACTIVATION_UNAVAILABLE} when no trustworthy answer could be
     *     loaded at all
     */
    public static SegmentReadinessProjection require(
            SegmentReadinessLookupPort port, CampaignPlan plan) {
        if (port == null || plan == null) {
            throw rejected("activationEvidence");
        }
        Optional<SegmentReadinessProjection> loaded;
        try {
            loaded = port.currentReadiness(SegmentAuthoringVersions.CAPABILITY_CODE);
        } catch (RuntimeException unavailable) {
            // A port that cannot answer is indistinguishable from one that refuses; neither may be guessed at.
            throw unavailableEvidence();
        }
        if (loaded == null || loaded.isEmpty()) {
            throw rejected("activationReadiness");
        }
        SegmentReadinessProjection readiness = loaded.get();
        try {
            SegmentActivationGuard.requireActivatable(readiness, plan);
        } catch (SegmentAuthoringException rejected) {
            throw rejected("activationGuard");
        }
        return readiness;
    }

    private static LifecycleException rejected(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED, invariant, MESSAGE);
    }

    private static LifecycleException unavailableEvidence() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_ACTIVATION_UNAVAILABLE,
                "activationReadiness",
                "No trustworthy production Segment readiness evidence was available.");
    }
}