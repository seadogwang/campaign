package com.company.campaign.readiness.adapter;

import com.company.campaign.action.ProviderCapability;
import com.company.campaign.production.provider.ProductionProviderEvidence;
import com.company.campaign.production.provider.ProductionProviderVerificationStatus;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessSupport;
import com.company.campaign.readiness.RequirementEvidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The trusted projector that turns an accepted Provider capability into readiness evidence.
 *
 * <p>The rule it enforces is the one the task pack states: a capability with {@code production=false} can
 * satisfy only the LOCAL simulated Provider failure-mode requirement, and can never satisfy a LIVE Provider
 * requirement. The two are projected separately and never share an origin:
 *
 * <ul>
 *   <li>the simulated failure-mode requirement admits only {@link EvidenceOrigin#SIMULATED} evidence, so a
 *       real production capability cannot be substituted for fault-injection coverage;</li>
 *   <li>the LIVE requirement is satisfied only by a production-capable, submission-ready capability
 *       <em>together with</em> current, successful live verification evidence of the exact configuration,
 *       recorded in a production environment — a production-capable capability observed without that evidence
 *       is a blocker naming exactly that, a failed live verification is a blocker naming its failure, and
 *       evidence that is stale or non-production is a blocker naming that.</li>
 * </ul>
 *
 * <p>{@link #project(ReadinessHostContext, String, ProviderCapability, Instant, Instant)} deliberately admits no
 * evidence and therefore can never satisfy the LIVE requirement, however production-capable its capability is:
 * a capability is configuration-shaped, and configuration alone is never live evidence.
 */
public final class ProviderCapabilityEvidenceProjector {

    /** Evidence identifier suffix for the simulated failure-mode requirement. */
    public static final String SIMULATED_SUFFIX = "-provider-simulated";

    /** Evidence identifier suffix for the LIVE provider requirement. */
    public static final String LIVE_SUFFIX = "-provider-live";

    /** The one bounded, non-sensitive evidence reference the LIVE projection emits. */
    public static final String EVIDENCE_REFERENCE = "readiness/provider/live-idempotency-and-reconciliation";

    private ProviderCapabilityEvidenceProjector() {
    }

    /**
     * Projects the simulated failure-mode requirement and one unverified LIVE Provider requirement.
     *
     * <p>Because no live verification evidence is supplied, the LIVE requirement is always a blocker here; the
     * production, submission-ready capability only decides which bounded reason code names the blocker.
     */
    public static List<RequirementEvidence> project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            ProviderCapability capability,
            Instant observedAt,
            Instant expiresAt) {
        return project(context, evidenceIdPrefix, capability, null, observedAt, expiresAt);
    }

    /**
     * Projects the simulated failure-mode requirement and the LIVE Provider requirement.
     *
     * <p>The LIVE requirement is satisfied only when the capability is production-capable and submission-ready,
     * the supplied {@link ProductionProviderEvidence} describes exactly that capability, its last live
     * verification succeeded, and that verification is still current at {@code observedAt} and was recorded in a
     * production scope.
     */
    public static List<RequirementEvidence> project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            ProviderCapability capability,
            ProductionProviderEvidence evidence,
            Instant observedAt,
            Instant expiresAt) {
        ReadinessHostContext host = ReadinessSupport.requireNonNull(context, "context");
        ProviderCapability value = ReadinessSupport.requireNonNull(capability, "capability");
        String prefix = ReadinessSupport.requireId(evidenceIdPrefix, "evidenceIdPrefix");
        ReadinessSupport.requireInstant(observedAt, "observedAt");

        List<RequirementEvidence> evidenceRecords = new ArrayList<>(2);
        evidenceRecords.add(simulatedFailureModes(host, prefix, value, observedAt, expiresAt));
        evidenceRecords.add(liveProvider(host, prefix, value, evidence, observedAt, expiresAt));
        return List.copyOf(evidenceRecords);
    }

    private static RequirementEvidence simulatedFailureModes(
            ReadinessHostContext context,
            String prefix,
            ProviderCapability capability,
            Instant observedAt,
            Instant expiresAt) {
        boolean simulated = !capability.production();
        EvidenceSubmission submission = new EvidenceSubmission(
                prefix + SIMULATED_SUFFIX,
                ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES,
                simulated ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                EvidenceOrigin.SIMULATED,
                capability.providerId(),
                capability.formatVersion(),
                capability.capabilityHash(),
                "readiness/provider/simulated-failure-modes",
                false,
                observedAt,
                expiresAt,
                simulated ? null : ReadinessReasonCodes.SIMULATED_PROVIDER_FAILURE_MODES_ABSENT);
        return RequirementEvidence.of(context.environment(), submission);
    }

    private static RequirementEvidence liveProvider(
            ReadinessHostContext context,
            String prefix,
            ProviderCapability capability,
            ProductionProviderEvidence evidence,
            Instant observedAt,
            Instant expiresAt) {
        boolean productionScope = context.environment().production();
        String reasonCode = liveReasonCode(capability, evidence, productionScope, observedAt);
        boolean satisfied = reasonCode == null;
        EvidenceOrigin origin;
        if (satisfied) {
            origin = EvidenceOrigin.PRODUCTION;
        } else if (capability.production()) {
            origin = EvidenceOrigin.REAL_LOCAL;
        } else {
            origin = EvidenceOrigin.SIMULATED;
        }
        EvidenceSubmission submission = new EvidenceSubmission(
                prefix + LIVE_SUFFIX,
                ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION,
                satisfied ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                origin,
                capability.providerId(),
                capability.formatVersion(),
                capability.capabilityHash(),
                EVIDENCE_REFERENCE,
                satisfied,
                observedAt,
                expiresAt,
                reasonCode);
        return RequirementEvidence.of(context.environment(), submission);
    }

    /**
     * The one bounded reason a LIVE Provider requirement is not met, or {@code null} when it is met.
     *
     * <p>The order is deliberate: a non-production or incomplete capability is named first, then the absence of
     * live evidence, then a mismatch, a failed verification, staleness and a non-production scope.
     */
    private static String liveReasonCode(
            ProviderCapability capability,
            ProductionProviderEvidence evidence,
            boolean productionScope,
            Instant observedAt) {
        if (!capability.production()) {
            return ReadinessReasonCodes.PROVIDER_NOT_PRODUCTION;
        }
        if (!capability.satisfiesSubmission()) {
            return ReadinessReasonCodes.PROVIDER_CAPABILITY_INCOMPLETE;
        }
        if (evidence == null) {
            // A production-capable capability alone is configuration, so it is never live evidence.
            return productionScope
                    ? ReadinessReasonCodes.PROVIDER_EVIDENCE_ABSENT
                    : ReadinessReasonCodes.PRODUCTION_RUN_NOT_PERFORMED;
        }
        if (!evidence.describes(capability)) {
            return ReadinessReasonCodes.PROVIDER_CAPABILITY_INCOMPLETE;
        }
        if (evidence.lastLiveVerificationStatus() == ProductionProviderVerificationStatus.FAILED_LIVE) {
            return ReadinessReasonCodes.PROVIDER_LIVE_VERIFICATION_FAILED;
        }
        if (!evidence.liveVerified()) {
            return ReadinessReasonCodes.PROVIDER_EVIDENCE_ABSENT;
        }
        if (!productionScope) {
            return ReadinessReasonCodes.PROVIDER_EVIDENCE_NOT_PRODUCTION;
        }
        if (evidence.expiredAt(observedAt)) {
            return ReadinessReasonCodes.PROVIDER_EVIDENCE_EXPIRED;
        }
        return null;
    }
}