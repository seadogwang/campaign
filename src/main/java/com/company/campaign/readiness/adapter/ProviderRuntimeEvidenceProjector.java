package com.company.campaign.readiness.adapter;

import com.company.campaign.production.runtime.ProviderRuntimeTopologyEvidence;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessSupport;
import com.company.campaign.readiness.RequirementEvidence;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The trusted projector that represents configured durable isolation and deterministic cycle coverage of a
 * production provider runtime host as readiness evidence.
 *
 * <p>It projects exactly the two LOCAL facts configuration can honestly prove — that both mutable execution
 * boundaries are backed by accepted durable stores, and that the cycle is deterministic and bounded — and it
 * refuses to promote either into a live-operations claim. The three LIVE requirements it speaks to — live
 * schedule safety, operational alerting and operational rollback — are always {@code BLOCKED}, because a
 * configuration value, however well bounded, is never production evidence. The live Provider requirement is
 * projected separately by {@link ProviderCapabilityEvidenceProjector} and is likewise satisfied only by current,
 * successful live verification evidence in a production scope.
 */
public final class ProviderRuntimeEvidenceProjector {

    /** Evidence identifier suffix for the Runtime isolation requirement. */
    public static final String ISOLATION_SUFFIX = "-provider-runtime-isolation";

    /** Evidence identifier suffix for the deterministic cycle-coverage requirement. */
    public static final String CYCLE_SUFFIX = "-provider-runtime-cycle";

    /** Evidence identifier suffix for the live schedule requirement. */
    public static final String SCHEDULE_SUFFIX = "-provider-runtime-schedule-live";

    /** Evidence identifier suffix for the operational alerting requirement. */
    public static final String ALERTS_SUFFIX = "-provider-runtime-alerts-live";

    /** Evidence identifier suffix for the operational rollback requirement. */
    public static final String ROLLBACK_SUFFIX = "-provider-runtime-rollback-live";

    private ProviderRuntimeEvidenceProjector() {
    }

    /**
     * Projects two LOCAL coverage requirements and three always-blocked LIVE requirements from the bounded,
     * redacted topology evidence of one production provider runtime host.
     */
    public static List<RequirementEvidence> project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            ProviderRuntimeTopologyEvidence facts,
            Instant observedAt,
            Instant expiresAt) {
        ReadinessHostContext host = ReadinessSupport.requireNonNull(context, "context");
        ProviderRuntimeTopologyEvidence value = ReadinessSupport.requireNonNull(facts, "facts");
        String prefix = ReadinessSupport.requireId(evidenceIdPrefix, "evidenceIdPrefix");
        ReadinessSupport.requireInstant(observedAt, "observedAt");

        boolean production = host.environment().production();
        EvidenceOrigin localOrigin = production ? EvidenceOrigin.PRODUCTION : EvidenceOrigin.REAL_LOCAL;
        return List.of(
                local(host, prefix + ISOLATION_SUFFIX, localOrigin, production,
                        ReadinessRequirement.LOCAL_RUNTIME_ISOLATION,
                        value.durableIsolationConfigured(),
                        ReadinessReasonCodes.RUNTIME_ISOLATION_ABSENT,
                        value, observedAt, expiresAt),
                local(host, prefix + CYCLE_SUFFIX, localOrigin, production,
                        ReadinessRequirement.LOCAL_CONCURRENCY_AND_LIMITS,
                        value.deterministicCycleCoverage(),
                        ReadinessReasonCodes.CONCURRENCY_COVERAGE_ABSENT,
                        value, observedAt, expiresAt),
                blocked(host, prefix + SCHEDULE_SUFFIX, ReadinessRequirement.LIVE_SCHEDULE_SAFETY,
                        ReadinessReasonCodes.SCHEDULE_COVERAGE_LOCAL_ONLY, value, observedAt, expiresAt),
                blocked(host, prefix + ALERTS_SUFFIX, ReadinessRequirement.LIVE_OPERATIONAL_ALERTS,
                        ReadinessReasonCodes.PRODUCTION_EVIDENCE_ABSENT, value, observedAt, expiresAt),
                blocked(host, prefix + ROLLBACK_SUFFIX, ReadinessRequirement.LIVE_OPERATIONAL_ROLLBACK,
                        ReadinessReasonCodes.PRODUCTION_EVIDENCE_ABSENT, value, observedAt, expiresAt));
    }

    private static RequirementEvidence local(
            ReadinessHostContext context,
            String evidenceId,
            EvidenceOrigin origin,
            boolean production,
            ReadinessRequirement requirement,
            boolean covered,
            String missingReason,
            ProviderRuntimeTopologyEvidence facts,
            Instant observedAt,
            Instant expiresAt) {
        EvidenceSubmission submission = new EvidenceSubmission(
                evidenceId,
                requirement,
                covered ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                origin,
                facts.subject(),
                facts.configurationHash().value(),
                facts.configurationHash(),
                "readiness/" + requirement.code().toLowerCase(Locale.ROOT),
                production && covered,
                observedAt,
                expiresAt,
                covered ? null : missingReason);
        return RequirementEvidence.of(context.environment(), submission);
    }

    private static RequirementEvidence blocked(
            ReadinessHostContext context,
            String evidenceId,
            ReadinessRequirement requirement,
            String reasonCode,
            ProviderRuntimeTopologyEvidence facts,
            Instant observedAt,
            Instant expiresAt) {
        EvidenceSubmission submission = new EvidenceSubmission(
                evidenceId,
                requirement,
                EvidenceStatus.BLOCKED,
                EvidenceOrigin.REAL_LOCAL,
                facts.subject(),
                facts.configurationHash().value(),
                facts.configurationHash(),
                "readiness/" + requirement.code().toLowerCase(Locale.ROOT),
                false,
                observedAt,
                expiresAt,
                reasonCode);
        return RequirementEvidence.of(context.environment(), submission);
    }
}