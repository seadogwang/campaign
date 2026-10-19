package com.company.campaign.readiness.testsupport;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessAssessment;
import com.company.campaign.readiness.ReadinessAuditAction;
import com.company.campaign.readiness.ReadinessAuditDisposition;
import com.company.campaign.readiness.ReadinessAuditEvent;
import com.company.campaign.readiness.ReadinessCatalog;
import com.company.campaign.readiness.ReadinessEnvironment;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessLevel;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessVersions;
import com.company.campaign.readiness.RequirementEvidence;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deterministic fixtures for the T25 readiness tests.
 *
 * <p>Every value here is fixed: the tenant, program, actor, request correlation and evaluation instant are
 * constants, so a test never depends on the machine clock, the machine locale or a generated identifier.
 */
public final class ReadinessTestSupport {

    public static final Instant AS_OF = Instant.parse("2026-01-01T00:00:00Z");
    public static final Instant OBSERVED_AT = Instant.parse("2025-12-31T00:00:00Z");
    public static final String TENANT = "tenant-1";
    public static final String PROGRAM = "program-1";
    public static final String ACTOR = "actor-1";
    public static final String CORRELATION = "request-1";

    private ReadinessTestSupport() {
    }

    /** A validated accepted trusted execution context for the fixed fixture identity. */
    public static TrustedExecutionContext trustedContext() {
        VerifiedIdentity identity = new VerifiedIdentity("user-1", "https://issuer.test", AS_OF);
        PrincipalMembership membership = new PrincipalMembership(
                "membership-1",
                "user-1",
                TENANT,
                PROGRAM,
                "scope-1",
                MembershipStatus.ACTIVE,
                Set.of(CampaignPermission.CAMPAIGN_READ, CampaignPermission.SEGMENT_EVALUATE));
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                TENANT,
                PROGRAM,
                "user-1",
                "scope-1",
                "snapshot-1",
                new LocaleTag("en-US"),
                ZoneId.of("UTC"),
                "USD",
                AS_OF,
                AS_OF.minusSeconds(60));
        return TrustedExecutionContext.of(identity, membership, reference);
    }

    /** A host context for the given environment, derived from the validated trusted context. */
    public static ReadinessHostContext context(ReadinessEnvironment environment) {
        return ReadinessHostContext.of(trustedContext(), environment, ACTOR, CORRELATION);
    }

    /** A canonical hash of a deterministic fixture label. */
    public static ContentHash hash(String label) {
        return ContentHash.sha256(label.getBytes(StandardCharsets.UTF_8));
    }

    /** The admissible origin for one requirement in one environment. */
    public static EvidenceOrigin originFor(ReadinessRequirement requirement, ReadinessEnvironment environment) {
        if (requirement == ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES) {
            return EvidenceOrigin.SIMULATED;
        }
        return environment.production() ? EvidenceOrigin.PRODUCTION : EvidenceOrigin.REAL_LOCAL;
    }

    /** A satisfied submission for one requirement, using an origin the requirement admits. */
    public static EvidenceSubmission satisfied(
            ReadinessRequirement requirement, ReadinessEnvironment environment, String evidenceId) {
        EvidenceOrigin origin = originFor(requirement, environment);
        return new EvidenceSubmission(
                evidenceId,
                requirement,
                EvidenceStatus.SATISFIED,
                origin,
                "subject-1",
                "1.0",
                hash(requirement.code() + "|" + evidenceId),
                "readiness/test/" + requirement.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                requirement.productionOnly() && environment.production(),
                OBSERVED_AT,
                null,
                null);
    }

    /** A blocked submission carrying an explicit closed reason code. */
    public static EvidenceSubmission blocked(
            ReadinessRequirement requirement,
            ReadinessEnvironment environment,
            String evidenceId,
            String reasonCode) {
        return new EvidenceSubmission(
                evidenceId,
                requirement,
                EvidenceStatus.BLOCKED,
                originFor(requirement, environment),
                "subject-1",
                "1.0",
                hash(requirement.code() + "|" + evidenceId),
                "readiness/test/" + requirement.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                false,
                OBSERVED_AT,
                null,
                reasonCode);
    }

    /** A submission that says the external run behind a requirement never happened. */
    public static EvidenceSubmission absent(
            ReadinessRequirement requirement, ReadinessEnvironment environment, String evidenceId) {
        return blocked(requirement, environment, evidenceId,
                ReadinessReasonCodes.PRODUCTION_RUN_NOT_PERFORMED);
    }

    /** One stamped evidence record for one requirement. */
    public static RequirementEvidence evidence(
            ReadinessEnvironment environment, ReadinessRequirement requirement, String evidenceId) {
        return RequirementEvidence.of(environment, satisfied(requirement, environment, evidenceId));
    }

    /** One stamped, satisfied evidence record for every requirement the level demands. */
    public static List<RequirementEvidence> completeEvidence(
            ReadinessEnvironment environment, ReadinessLevel level) {
        List<RequirementEvidence> evidence = new ArrayList<>();
        for (ReadinessRequirement requirement : ReadinessCatalog.requirementsFor(level)) {
            evidence.add(evidence(environment, requirement, "ev-" + requirement.code().toLowerCase(
                    java.util.Locale.ROOT).replace('_', '-')));
        }
        return List.copyOf(evidence);
    }

    /** The audit material one evidence append carries; the store decides the final disposition. */
    public static ReadinessAuditEvent evidenceAudit(
            ReadinessHostContext context, RequirementEvidence evidence) {
        return new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.RECORD_EVIDENCE,
                ReadinessAuditDisposition.RECORDED,
                evidence.reasonCode(),
                AS_OF,
                context.tenantId(),
                context.programId(),
                context.environment(),
                context.actorRef(),
                context.requestCorrelation(),
                evidence.requirement().code(),
                "NONE>" + evidence.status().name(),
                evidence.identityHash(),
                evidence.subjectFingerprint(),
                null);
    }

    /** The audit material one assessment append carries. */
    public static ReadinessAuditEvent assessmentAudit(
            ReadinessHostContext context, ReadinessAssessment assessment) {
        return new ReadinessAuditEvent(
                ReadinessVersions.AUDIT_FORMAT_VERSION,
                ReadinessAuditAction.EVALUATE_READINESS,
                ReadinessAuditDisposition.RECORDED,
                assessment.blockers().isEmpty() ? null : assessment.blockers().get(0).code(),
                AS_OF,
                context.tenantId(),
                context.programId(),
                context.environment(),
                context.actorRef(),
                context.requestCorrelation(),
                null,
                "EVALUATED>" + assessment.status().name(),
                null,
                null,
                assessment.assessmentHash());
    }

    /** An evidence submission with an explicit origin and status, for origin-refusal tests. */
    public static RequirementEvidence evidenceWith(
            ReadinessEnvironment environment,
            ReadinessRequirement requirement,
            String evidenceId,
            EvidenceStatus status,
            EvidenceOrigin origin,
            boolean productionGateEvidence,
            Instant observedAt,
            Instant expiresAt,
            String reasonCode) {
        return RequirementEvidence.of(environment, new EvidenceSubmission(
                evidenceId,
                requirement,
                status,
                origin,
                "subject-1",
                "1.0",
                hash(requirement.code() + "|" + evidenceId),
                "readiness/test/" + requirement.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                productionGateEvidence,
                observedAt,
                expiresAt,
                reasonCode));
    }
}