package com.company.campaign.readiness.adapter;

import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessSupport;
import com.company.campaign.readiness.RequirementEvidence;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;

/**
 * The trusted projector that represents accepted deterministic schedule, runtime and action coverage as
 * readiness evidence.
 *
 * <p>It projects what the accepted T21/T22/T23 deterministic layers actually prove — concurrency, stale
 * revisions, idempotency, timeouts, query cost and rollback coverage, and Runtime availability isolation
 * from Builder/LLM services — and it refuses to promote any of it into a live-operations claim. The two LIVE
 * requirements it can speak to are always {@code BLOCKED}: accepted deterministic coverage is exactly that,
 * and a schedule or rollback that has not been observed in production is not production evidence.
 */
public final class ScheduleRuntimeEvidenceProjector {

    /** Evidence identifier suffix for the concurrency and limits requirement. */
    public static final String CONCURRENCY_SUFFIX = "-concurrency";

    /** Evidence identifier suffix for the Runtime isolation requirement. */
    public static final String ISOLATION_SUFFIX = "-runtime-isolation";

    /** Evidence identifier suffix for the live schedule requirement. */
    public static final String SCHEDULE_SUFFIX = "-schedule-live";

    /** Evidence identifier suffix for the operational rollback requirement. */
    public static final String ROLLBACK_SUFFIX = "-rollback-live";

    private ScheduleRuntimeEvidenceProjector() {
    }

    /**
     * The accepted deterministic coverage a host has actually executed.
     *
     * @param coverageHash the exact hash of the executed deterministic coverage report — required, so a
     *     readiness record always pins the run it rests on
     */
    public record CoverageFacts(
            String subject,
            boolean concurrencyAndLimitsCovered,
            boolean runtimeIsolationCovered,
            ContentHash coverageHash) {

        public CoverageFacts {
            ReadinessSupport.requireId(subject, "subject");
            ReadinessSupport.requireNonNull(coverageHash, "coverageHash");
        }

        /** No deterministic coverage was executed. */
        public static CoverageFacts absent(String subject, ContentHash coverageHash) {
            return new CoverageFacts(subject, false, false, coverageHash);
        }
    }

    /** Projects two LOCAL coverage requirements and two always-blocked LIVE requirements. */
    public static List<RequirementEvidence> project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            CoverageFacts facts,
            Instant observedAt,
            Instant expiresAt) {
        ReadinessHostContext host = ReadinessSupport.requireNonNull(context, "context");
        CoverageFacts value = ReadinessSupport.requireNonNull(facts, "facts");
        String prefix = ReadinessSupport.requireId(evidenceIdPrefix, "evidenceIdPrefix");
        ReadinessSupport.requireInstant(observedAt, "observedAt");

        boolean production = host.environment().production();
        EvidenceOrigin localOrigin = production ? EvidenceOrigin.PRODUCTION : EvidenceOrigin.REAL_LOCAL;
        return List.of(
                local(host, prefix + CONCURRENCY_SUFFIX, localOrigin, production,
                        ReadinessRequirement.LOCAL_CONCURRENCY_AND_LIMITS,
                        value.concurrencyAndLimitsCovered(),
                        ReadinessReasonCodes.CONCURRENCY_COVERAGE_ABSENT,
                        value, observedAt, expiresAt),
                local(host, prefix + ISOLATION_SUFFIX, localOrigin, production,
                        ReadinessRequirement.LOCAL_RUNTIME_ISOLATION,
                        value.runtimeIsolationCovered(),
                        ReadinessReasonCodes.RUNTIME_ISOLATION_ABSENT,
                        value, observedAt, expiresAt),
                blocked(host, prefix + SCHEDULE_SUFFIX, ReadinessRequirement.LIVE_SCHEDULE_SAFETY,
                        ReadinessReasonCodes.SCHEDULE_COVERAGE_LOCAL_ONLY, value, observedAt, expiresAt),
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
            CoverageFacts facts,
            Instant observedAt,
            Instant expiresAt) {
        EvidenceSubmission submission = new EvidenceSubmission(
                evidenceId,
                requirement,
                covered ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                origin,
                facts.subject(),
                facts.coverageHash().value(),
                facts.coverageHash(),
                "readiness/" + requirement.code().toLowerCase(java.util.Locale.ROOT),
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
            CoverageFacts facts,
            Instant observedAt,
            Instant expiresAt) {
        EvidenceSubmission submission = new EvidenceSubmission(
                evidenceId,
                requirement,
                EvidenceStatus.BLOCKED,
                EvidenceOrigin.REAL_LOCAL,
                facts.subject(),
                facts.coverageHash().value(),
                facts.coverageHash(),
                "readiness/" + requirement.code().toLowerCase(java.util.Locale.ROOT),
                false,
                observedAt,
                expiresAt,
                reasonCode);
        return RequirementEvidence.of(context.environment(), submission);
    }
}