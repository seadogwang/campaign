package com.company.campaign.readiness.postgres;

import com.company.campaign.readiness.AssessmentAppendResult;
import com.company.campaign.readiness.EvidenceAppendResult;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceScope;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.MyBatisPlusReadinessStore;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.company.campaign.readiness.ReadinessAssessment;
import com.company.campaign.readiness.ReadinessAuditAction;
import com.company.campaign.readiness.ReadinessAuditDisposition;
import com.company.campaign.readiness.ReadinessAuditEvent;
import com.company.campaign.readiness.ReadinessDisposition;
import com.company.campaign.readiness.ReadinessEnvironment;
import com.company.campaign.readiness.ReadinessErrorCodes;
import com.company.campaign.readiness.ReadinessEvaluationRequest;
import com.company.campaign.readiness.ReadinessEvaluator;
import com.company.campaign.readiness.ReadinessException;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessLevel;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessVersions;
import com.company.campaign.readiness.RequirementEvidence;
import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import com.company.campaign.shared.canonical.ContentHash;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The environment-gated real-PostgreSQL verification.
 *
 * <p>It is skipped unless {@code CAMPAIGN_READINESS_POSTGRES_URL} (plus a user and a password) is supplied,
 * and when it does run it uses only real PostgreSQL: the ordered {@code V1}–{@code V3} assets are applied by
 * real Flyway into a dedicated schema, and every proof is exercised through a real
 * {@link MyBatisPlusReadinessStore} over a real pool. There is no test double, no embedded engine and no H2 fallback
 * anywhere on this path — a missing setting means the run does not happen, never that a substitute runs
 * instead.
 *
 * <p>Proved in one restart-crossing run: append, idempotent replay, conflicting-identity refusal, stale
 * revision that writes nothing, assessment persistence, evidence-plus-audit atomicity under a real constraint
 * failure, and durability across a fresh pool and a fresh store. On success it writes a bounded, hash-pinned
 * report naming PostgreSQL as the engine that answered.
 */
@EnabledIfEnvironmentVariable(
        named = ReadinessPostgresTestEnvironment.URL_VARIABLE,
        matches = ".+",
        disabledReason = "real PostgreSQL is not configured")
class ReadinessPostgresIntegrationTest {

    private static final ReadinessRequirement REQUIREMENT = ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION;
    private static final Instant OBSERVED_AT = Instant.parse("2025-12-31T00:00:00Z");
    private static final Instant EVALUATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void realPostgresqlCarriesTheOrderedMigrationsAndEveryReadinessStoreProof() throws Exception {
        ReadinessEnvironment environment = ReadinessPostgresTestEnvironment.environment();
        boolean cleanAllowed = ReadinessPostgresTestEnvironment.mayClean();
        Map<String, Boolean> proofs = new LinkedHashMap<>();
        long revisionAfterRestart;
        ContentHash assessmentHash;

        try (HikariDataSource dataSource = ReadinessPostgresTestEnvironment.dataSource()) {
            Flyway flyway = ReadinessPostgresTestEnvironment.flyway(dataSource, cleanAllowed);
            if (cleanAllowed) {
                flyway.clean();
            }
            flyway.migrate();

            List<Integer> applied = ReadinessPostgresTestEnvironment.appliedVersions(flyway);
            assertThat(applied)
                    .as("the ordered migration set applied through real Flyway")
                    .containsExactly(1, 2, 3);
            assertThat(flyway.info().pending()).isEmpty();

            ReadinessHostContext context = ReadinessTestSupport.context(environment);
            EvidenceScope scope = context.scope();
            MyBatisPlusReadinessStore store = new MyBatisPlusReadinessStore(
                    MyBatisTestSupport.sessionFactory(dataSource));

            RequirementEvidence record = evidence(environment, "ev-append", "reference-append", "hash-append");
            ReadinessAuditEvent audit = ReadinessTestSupport.evidenceAudit(context, record);

            // ---------------------------------------------------------------- append
            EvidenceAppendResult appended = store.appendEvidence(scope, record, audit, 0L);
            assertThat(appended.disposition()).isEqualTo(ReadinessDisposition.RECORDED);
            assertThat(appended.revision()).isEqualTo(1L);
            assertThat(store.revision(scope)).isEqualTo(1L);
            proofs.put("append", true);

            // ---------------------------------------------------------------- idempotent replay
            EvidenceAppendResult replay = store.appendEvidence(scope, record, audit, 1L);
            assertThat(replay.disposition()).isEqualTo(ReadinessDisposition.DUPLICATE);
            assertThat(store.revision(scope)).isEqualTo(1L);
            proofs.put("idempotentReplay", true);

            // ------------------------------------------- conflicting identity, same identity different material
            RequirementEvidence conflicting = RequirementEvidence.of(
                    environment, submission("ev-append", REQUIREMENT, "reference-different", "hash-append",
                            environment));
            assertThat(conflicting.identityHash()).isEqualTo(record.identityHash());
            assertThat(conflicting.materialHash()).isNotEqualTo(record.materialHash());
            EvidenceAppendResult conflict = store.appendEvidence(scope, conflicting, audit, 1L);
            assertThat(conflict.disposition()).isEqualTo(ReadinessDisposition.CONFLICT);
            assertThat(store.revision(scope)).isEqualTo(1L);
            proofs.put("conflictRefusal", true);

            // ---------------------------------------------------------------- stale revision writes nothing
            RequirementEvidence later = evidence(environment, "ev-later", "reference-later", "hash-later");
            EvidenceAppendResult stale = store.appendEvidence(
                    scope, later, ReadinessTestSupport.evidenceAudit(context, later), 0L);
            assertThat(stale.disposition()).isEqualTo(ReadinessDisposition.STALE_REVISION);
            assertThat(store.revision(scope)).isEqualTo(1L);
            assertThat(store.evidence(scope, ReadinessLevel.LOCAL_READY)).hasSize(1);
            proofs.put("staleRevisionRollback", true);

            // ---------------------------------------------------------------- assessment persistence
            ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                    "assessment-1", ReadinessLevel.LOCAL_READY, environment, EVALUATED_AT,
                    store.evidence(scope, ReadinessLevel.LOCAL_READY)));
            AssessmentAppendResult appendedAssessment = store.appendAssessment(
                    scope, assessment, ReadinessTestSupport.assessmentAudit(context, assessment), 1L);
            assertThat(appendedAssessment.disposition()).isEqualTo(ReadinessDisposition.RECORDED);

            ReadinessAssessment reloaded = store.latestAssessment(scope, ReadinessLevel.LOCAL_READY).orElseThrow();
            assertThat(reloaded.assessmentHash()).isEqualTo(assessment.assessmentHash());
            assertThat(reloaded.status()).isEqualTo(assessment.status());
            assertThat(reloaded.level()).isEqualTo(assessment.level());
            assertThat(reloaded.acceptedEvidence()).isEqualTo(assessment.acceptedEvidence());
            assertThat(reloaded.blockers()).isEqualTo(assessment.blockers());
            assessmentHash = assessment.assessmentHash();
            proofs.put("assessmentPersistence", true);

            // ------------------------------------------------- evidence plus audit atomicity
            long revisionBeforeFailure = store.revision(scope);
            int evidenceBeforeFailure = store.evidence(scope, ReadinessLevel.LOCAL_READY).size();
            int auditBeforeFailure = store.auditEvents(scope, 256).size();
            assertThat(auditBeforeFailure).isEqualTo(2);

            // The evidence row inserts, then the audit row violates a real column bound, so the whole unit must
            // roll back: no evidence row, no audit row and no revision bump survive.
            ReadinessAuditEvent oversizedAudit = new ReadinessAuditEvent(
                    ReadinessVersions.AUDIT_FORMAT_VERSION,
                    ReadinessAuditAction.RECORD_EVIDENCE,
                    ReadinessAuditDisposition.RECORDED,
                    null,
                    OBSERVED_AT,
                    context.tenantId(),
                    context.programId(),
                    environment,
                    context.actorRef(),
                    context.requestCorrelation(),
                    REQUIREMENT.code(),
                    "A".repeat(32) + ">" + "B".repeat(32),
                    record.identityHash(),
                    record.subjectFingerprint(),
                    null);
            Throwable failure = catchThrowable(() -> store.appendEvidence(scope, later, oversizedAudit, revisionBeforeFailure));
            assertThat(failure).isInstanceOf(ReadinessException.class);
            assertThat(((ReadinessException) failure).code())
                    .isEqualTo(ReadinessErrorCodes.READINESS_STORE_UNAVAILABLE);

            assertThat(store.revision(scope)).isEqualTo(revisionBeforeFailure);
            assertThat(store.evidence(scope, ReadinessLevel.LOCAL_READY)).hasSize(evidenceBeforeFailure);
            assertThat(store.auditEvents(scope, 256)).hasSize(auditBeforeFailure);
            proofs.put("auditAtomicity", true);

            revisionAfterRestart = store.revision(scope);
        }

        // ---------------------------------------------------------------- restart durability
        try (HikariDataSource restarted = ReadinessPostgresTestEnvironment.dataSource()) {
            MyBatisPlusReadinessStore reopened = new MyBatisPlusReadinessStore(
                    MyBatisTestSupport.sessionFactory(restarted));
            ReadinessHostContext context = ReadinessTestSupport.context(environment);
            EvidenceScope scope = context.scope();

            assertThat(reopened.revision(scope)).isEqualTo(revisionAfterRestart);
            assertThat(reopened.evidence(scope, ReadinessLevel.LOCAL_READY)).hasSize(1);
            ReadinessAssessment durable = reopened.latestAssessment(scope, ReadinessLevel.LOCAL_READY).orElseThrow();
            assertThat(durable.assessmentHash()).isEqualTo(assessmentHash);
            assertThat(reopened.auditEvents(scope, 256)).hasSize(2);
            proofs.put("restartDurability", true);

            // ---------------------------------------------------------------- bounded, hash-pinned report
            ReadinessPostgresVerificationReport report = ReadinessPostgresVerificationReport.of(
                    ReadinessPostgresTestEnvironment.serverVersion(restarted),
                    ReadinessPostgresTestEnvironment.schema(),
                    environment,
                    List.of(1, 2, 3),
                    proofs,
                    Instant.now());
            assertThat(report.engine()).isEqualTo("POSTGRESQL");
            assertThat(report.everythingProved()).isTrue();
            assertThat(report.canonicalText()).doesNotContain("jdbc:", "password");
            report.write(ReadinessPostgresTestEnvironment.reportPath());
        }
    }

    private static RequirementEvidence evidence(
            ReadinessEnvironment environment, String evidenceId, String reference, String hashSeed) {
        return RequirementEvidence.of(
                environment, submission(evidenceId, REQUIREMENT, reference, hashSeed, environment));
    }

    private static EvidenceSubmission submission(
            String evidenceId,
            ReadinessRequirement requirement,
            String reference,
            String hashSeed,
            ReadinessEnvironment environment) {
        return new EvidenceSubmission(
                evidenceId,
                requirement,
                EvidenceStatus.SATISFIED,
                environment.production() ? EvidenceOrigin.PRODUCTION : EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ContentHash.sha256(hashSeed.getBytes(StandardCharsets.UTF_8)),
                reference,
                false,
                OBSERVED_AT,
                null,
                null);
    }
}
