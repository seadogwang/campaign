package com.company.campaign.readiness;

import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T25 readiness model and evaluator tests.
 *
 * <p>They prove the closed catalog, the ordered levels, the deterministic status and blocker derivation, and
 * that every origin which is not production-capable is refused by a production-only requirement. The
 * production-environment cases construct a complete, production-stamped evidence set on purpose: they prove
 * the evaluator <em>can</em> reach a READY verdict, which is what makes the blocked verdicts elsewhere
 * meaningful. They are contract fixtures, not evidence that any production run happened.
 */
class ReadinessModelAndEvaluatorTest {

    private static final ReadinessEnvironment LOCAL = ReadinessEnvironment.LOCAL;
    private static final ReadinessEnvironment PRODUCTION = ReadinessEnvironment.PRODUCTION;
    private static final Instant AS_OF = ReadinessTestSupport.AS_OF;

    private final ReadinessEvaluator evaluator = new ReadinessEvaluator();

    private ReadinessAssessment evaluate(
            ReadinessLevel level, ReadinessEnvironment environment, List<RequirementEvidence> evidence) {
        return evaluator.evaluate(new ReadinessEvaluationRequest(
                "assessment-1", level, environment, AS_OF, evidence));
    }

    // ------------------------------------------------------------------ the closed catalog

    @Test
    void theCatalogIsOrderedAndEveryHigherLevelInheritsTheLowerOnes() {
        assertThat(ReadinessCatalog.requirementsFor(ReadinessLevel.LOCAL_READY))
                .hasSize(ReadinessCatalog.introducedAt(ReadinessLevel.LOCAL_READY).size());
        assertThat(ReadinessCatalog.requirementsFor(ReadinessLevel.AI_READY))
                .containsAll(ReadinessCatalog.requirementsFor(ReadinessLevel.LOCAL_READY))
                .hasSize(14);
        assertThat(ReadinessCatalog.requirementsFor(ReadinessLevel.LIVE_READY))
                .containsAll(ReadinessCatalog.requirementsFor(ReadinessLevel.LOCAL_READY))
                .containsAll(ReadinessCatalog.requirementsFor(ReadinessLevel.AI_READY))
                // T35B adds the one production-only LIVE_PRODUCTION_STRUCTURED_MODEL requirement.
                .hasSize(24);
        assertThat(ReadinessCatalog.VERSION).isEqualTo("campaign-readiness-catalog/1.0");
    }

    @Test
    void aLevelIncludesEveryLevelAtOrBelowIt() {
        assertThat(ReadinessLevel.LIVE_READY.includes(ReadinessLevel.LOCAL_READY)).isTrue();
        assertThat(ReadinessLevel.LIVE_READY.includes(ReadinessLevel.AI_READY)).isTrue();
        assertThat(ReadinessLevel.AI_READY.includes(ReadinessLevel.LIVE_READY)).isFalse();
        assertThat(ReadinessLevel.LOCAL_READY.includes(ReadinessLevel.LOCAL_READY)).isTrue();
        assertThat(ReadinessLevel.AI_READY.levelsUpTo())
                .containsExactly(ReadinessLevel.LOCAL_READY, ReadinessLevel.AI_READY);
    }

    @Test
    void theCatalogRejectsAnUnknownRequirementCode() {
        assertThat(ReadinessCatalog.isKnownCode("LOCAL_POSTGRESQL_INTEGRATION")).isTrue();
        assertThat(ReadinessCatalog.isKnownCode("NOT_A_REQUIREMENT")).isFalse();
        assertThatThrownBy(() -> ReadinessRequirement.fromCode("NOT_A_REQUIREMENT"))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_REQUIREMENT_UNKNOWN));
    }

    @Test
    void everyProductionOnlyRequirementAdmitsExactlyTheProductionOrigin() {
        for (ReadinessRequirement requirement : ReadinessCatalog.all()) {
            if (requirement.productionOnly()) {
                assertThat(requirement.allowedOrigins())
                        .containsExactly(EvidenceOrigin.PRODUCTION);
            }
            assertThat(requirement.allowedOrigins()).doesNotContain(EvidenceOrigin.EVALUATION_ASSET);
            assertThat(requirement.allowedOrigins()).isNotEmpty();
        }
        assertThat(ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES.allowedOrigins())
                .containsExactly(EvidenceOrigin.SIMULATED);
    }

    @Test
    void theClosedReasonAndBlockerCatalogsRejectUnknownCodes() {
        assertThat(ReadinessReasonCodes.isKnown("READINESS_PROVIDER_NOT_PRODUCTION")).isTrue();
        assertThat(ReadinessReasonCodes.isKnown("SOME_FREE_TEXT")).isFalse();
        assertThat(ReadinessBlockerCodes.isKnown("READINESS_REQUIREMENT_MISSING")).isTrue();
        assertThat(ReadinessBlockerCodes.isKnown("SOME_OTHER_TEXT")).isFalse();
    }

    // ------------------------------------------------------------------ the LOCAL level

    @Test
    void aCompleteLocalEvidenceSetIsReady() {
        ReadinessAssessment assessment = evaluate(
                ReadinessLevel.LOCAL_READY, LOCAL,
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY));

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(assessment.ready()).isTrue();
        assertThat(assessment.blockers()).isEmpty();
        assertThat(assessment.acceptedEvidence()).hasSize(10);
        assertThat(assessment.environment()).isEqualTo(LOCAL);
        assertThat(assessment.formatVersion()).isEqualTo("campaign-readiness-assessment/1.0");
    }

    @Test
    void anAbsentRequirementBlocksInsteadOfDefaulting() {
        ReadinessAssessment assessment = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, List.of());

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(assessment.ready()).isFalse();
        assertThat(assessment.blockers()).hasSize(10);
        assertThat(assessment.blockerCodes())
                .containsOnly(ReadinessBlockerCodes.REQUIREMENT_MISSING);
        assertThat(assessment.acceptedEvidence()).isEmpty();
    }

    @Test
    void aTruthfullyBlockedEvidenceRecordBlocksWithItsOwnReasonCode() {
        List<RequirementEvidence> evidence = new ArrayList<>();
        evidence.add(RequirementEvidence.of(LOCAL, new EvidenceSubmission(
                "ev-postgresql",
                ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION,
                EvidenceStatus.BLOCKED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("postgresql-absent"),
                "readiness/test/postgresql",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                ReadinessReasonCodes.POSTGRESQL_NOT_EXERCISED)));

        ReadinessAssessment assessment = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, evidence);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(assessment.blockers()).isNotEmpty();
        assertThat(assessment.blockers().get(0).code())
                .isEqualTo(ReadinessReasonCodes.POSTGRESQL_NOT_EXERCISED);
        assertThat(assessment.blockers().get(0).requirement())
                .isEqualTo(ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION);
        assertThat(assessment.blockers().get(0).ordinal()).isZero();
    }

    @Test
    void expiredEvidenceNeverSatisfiesARequirement() {
        List<RequirementEvidence> evidence = new ArrayList<>(
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY));
        evidence.removeIf(record -> record.requirement() == ReadinessRequirement.LOCAL_BROWSER_END_TO_END);
        evidence.add(RequirementEvidence.of(LOCAL, new EvidenceSubmission(
                "ev-browser",
                ReadinessRequirement.LOCAL_BROWSER_END_TO_END,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("browser"),
                "readiness/test/browser",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                Instant.parse("2025-12-31T12:00:00Z"),
                null)));

        ReadinessAssessment assessment = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, evidence);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(assessment.blockerCodes()).contains(ReadinessBlockerCodes.EVIDENCE_EXPIRED);
    }

    @Test
    void evidenceObservedAfterTheAssessmentInstantIsInvalid() {
        List<RequirementEvidence> evidence = new ArrayList<>(
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY));
        evidence.removeIf(record -> record.requirement() == ReadinessRequirement.LOCAL_API_END_TO_END);
        evidence.add(RequirementEvidence.of(LOCAL, new EvidenceSubmission(
                "ev-api",
                ReadinessRequirement.LOCAL_API_END_TO_END,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("api"),
                "readiness/test/api",
                false,
                AS_OF.plusSeconds(3_600),
                null,
                null)));

        ReadinessAssessment assessment = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, evidence);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.INVALID);
        assertThat(assessment.blockerCodes()).contains(ReadinessBlockerCodes.EVIDENCE_FUTURE_OBSERVED);
    }

    @Test
    void twoDistinctRecordsForOneRequirementMakeTheLevelInvalid() {
        List<RequirementEvidence> evidence = new ArrayList<>(
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY));
        evidence.add(RequirementEvidence.of(LOCAL, new EvidenceSubmission(
                "ev-migrations-second",
                ReadinessRequirement.LOCAL_ORDERED_MIGRATIONS,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-2",
                "1.0",
                ReadinessTestSupport.hash("migrations-second"),
                "readiness/test/migrations-second",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                null)));

        ReadinessAssessment assessment = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, evidence);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.INVALID);
        assertThat(assessment.blockerCodes()).contains(ReadinessBlockerCodes.EVIDENCE_DUPLICATE);
    }

    @Test
    void evidenceRecordedInAnotherEnvironmentIsInvalid() {
        List<RequirementEvidence> evidence = new ArrayList<>(
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY));
        evidence.removeIf(record -> record.requirement() == ReadinessRequirement.LOCAL_API_END_TO_END);
        evidence.add(ReadinessTestSupport.evidence(
                ReadinessEnvironment.CI, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api"));

        ReadinessAssessment assessment = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, evidence);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.INVALID);
        assertThat(assessment.blockerCodes()).contains(ReadinessBlockerCodes.EVIDENCE_WRONG_ENVIRONMENT);
    }

    @Test
    void aProductionOriginInsideANonProductionEnvironmentIsInvalid() {
        List<RequirementEvidence> evidence = new ArrayList<>(
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LOCAL_READY));
        evidence.removeIf(record -> record.requirement() == ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION);
        evidence.add(RequirementEvidence.of(LOCAL, new EvidenceSubmission(
                "ev-postgresql",
                ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.PRODUCTION,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("postgresql"),
                "readiness/test/postgresql",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                null)));

        ReadinessAssessment assessment = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, evidence);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.INVALID);
        assertThat(assessment.blockerCodes()).contains(ReadinessBlockerCodes.EVIDENCE_ORIGIN_MISMATCH);
    }

    @Test
    void blockerOrderIsDeterministicAndFollowsTheCatalog() {
        ReadinessAssessment first = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, List.of());
        ReadinessAssessment second = evaluate(ReadinessLevel.LOCAL_READY, LOCAL, List.of());

        assertThat(first.assessmentHash()).isEqualTo(second.assessmentHash());
        assertThat(first.blockers()).isEqualTo(second.blockers());
        List<ReadinessRequirement> ordered = first.blockers().stream()
                .map(ReadinessBlocker::requirement)
                .toList();
        assertThat(ordered).isEqualTo(ReadinessCatalog.requirementsFor(ReadinessLevel.LOCAL_READY));
        for (int index = 0; index < first.blockers().size(); index++) {
            assertThat(first.blockers().get(index).ordinal()).isEqualTo(index);
        }
    }

    // ------------------------------------------------------------------ the AI level

    @Test
    void theAiLevelCannotBeReachedWithAiEvidenceAlone() {
        List<RequirementEvidence> aiOnly = ReadinessCatalog
                .introducedAt(ReadinessLevel.AI_READY).stream()
                .map(requirement -> ReadinessTestSupport.evidence(
                        LOCAL, requirement, "ev-" + requirement.code().toLowerCase(
                                java.util.Locale.ROOT).replace('_', '-')))
                .toList();

        ReadinessAssessment assessment = evaluate(ReadinessLevel.AI_READY, LOCAL, aiOnly);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(assessment.blockers()).hasSize(10);
        assertThat(assessment.blockers().stream().map(ReadinessBlocker::requirement).toList())
                .allSatisfy(requirement -> assertThat(requirement.level())
                        .isEqualTo(ReadinessLevel.LOCAL_READY));
    }

    @Test
    void aCompleteLocalAndAiEvidenceSetIsReadyAtTheAiLevel() {
        ReadinessAssessment assessment = evaluate(
                ReadinessLevel.AI_READY, LOCAL,
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.AI_READY));

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(assessment.blockers()).isEmpty();
        assertThat(assessment.acceptedEvidence()).hasSize(14);
    }

    // ------------------------------------------------------------------ the LIVE level

    @Test
    void aLocalEvidenceSetCannotClaimLiveReadiness() {
        ReadinessAssessment assessment = evaluate(
                ReadinessLevel.LIVE_READY, LOCAL,
                ReadinessTestSupport.completeEvidence(LOCAL, ReadinessLevel.LIVE_READY));

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.BLOCKED);
        // T35B adds the production-only structured-model requirement to the prior nine LIVE requirements.
        assertThat(assessment.blockers()).hasSize(10);
        assertThat(assessment.blockerCodes()).contains(ReadinessBlockerCodes.EVIDENCE_WRONG_ORIGIN);
        assertThat(assessment.acceptedEvidence()).hasSize(14);
    }

    @Test
    void simulatedFixtureSyntheticDevelopmentOnlyAndEvaluationAssetOriginsCannotSatisfyLive() {
        List<EvidenceOrigin> substitutes = List.of(
                EvidenceOrigin.SIMULATED,
                EvidenceOrigin.FIXTURE,
                EvidenceOrigin.SYNTHETIC,
                EvidenceOrigin.DEVELOPMENT_ONLY,
                EvidenceOrigin.EVALUATION_ASSET);

        for (EvidenceOrigin origin : substitutes) {
            List<RequirementEvidence> evidence = new ArrayList<>(
                    ReadinessTestSupport.completeEvidence(PRODUCTION, ReadinessLevel.LIVE_READY));
            evidence.removeIf(record -> record.requirement()
                    == ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION);
            evidence.add(RequirementEvidence.of(PRODUCTION, new EvidenceSubmission(
                    "ev-provider",
                    ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION,
                    EvidenceStatus.SATISFIED,
                    origin,
                    "subject-1",
                    "1.0",
                    ReadinessTestSupport.hash("provider"),
                    "readiness/test/provider",
                    false,
                    ReadinessTestSupport.OBSERVED_AT,
                    null,
                    null)));

            ReadinessAssessment assessment = evaluate(ReadinessLevel.LIVE_READY, PRODUCTION, evidence);

            assertThat(assessment.status()).describedAs("origin %s", origin)
                    .isEqualTo(ReadinessStatus.BLOCKED);
            assertThat(assessment.blockerCodes()).describedAs("origin %s", origin)
                    .contains(ReadinessBlockerCodes.EVIDENCE_WRONG_ORIGIN);
        }
    }

    @Test
    void aProductionOnlyRequirementRefusesEvidenceThatIsNotExplicitlyProductionStamped() {
        List<RequirementEvidence> evidence = new ArrayList<>(
                ReadinessTestSupport.completeEvidence(PRODUCTION, ReadinessLevel.LIVE_READY));
        evidence.removeIf(record -> record.requirement()
                == ReadinessRequirement.LIVE_BACKUP_AND_RESTORE);
        evidence.add(RequirementEvidence.of(PRODUCTION, new EvidenceSubmission(
                "ev-backup",
                ReadinessRequirement.LIVE_BACKUP_AND_RESTORE,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.PRODUCTION,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("backup"),
                "readiness/test/backup",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                null)));

        ReadinessAssessment assessment = evaluate(ReadinessLevel.LIVE_READY, PRODUCTION, evidence);

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(assessment.blockerCodes()).contains(ReadinessBlockerCodes.EVIDENCE_NOT_PRODUCTION);
    }

    @Test
    void aCompleteProductionStampedEvidenceSetIsReadyAtTheLiveLevel() {
        ReadinessAssessment assessment = evaluate(
                ReadinessLevel.LIVE_READY, PRODUCTION,
                ReadinessTestSupport.completeEvidence(PRODUCTION, ReadinessLevel.LIVE_READY));

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(assessment.blockers()).isEmpty();
        // T35B adds the one production-only LIVE_PRODUCTION_STRUCTURED_MODEL requirement.
        assertThat(assessment.acceptedEvidence()).hasSize(24);
    }

    // ------------------------------------------------------------------ record invariants

    @Test
    void aNonSatisfiedSubmissionMustCarryAClosedReasonCode() {
        assertThatThrownBy(() -> new EvidenceSubmission(
                "ev-1",
                ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION,
                EvidenceStatus.BLOCKED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("x"),
                "readiness/test/x",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                null))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_REASON_UNKNOWN));
    }

    @Test
    void aSatisfiedSubmissionMustNotCarryAReasonCode() {
        assertThatThrownBy(() -> new EvidenceSubmission(
                "ev-1",
                ReadinessRequirement.LOCAL_POSTGRESQL_INTEGRATION,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.REAL_LOCAL,
                "subject-1",
                "1.0",
                ReadinessTestSupport.hash("x"),
                "readiness/test/x",
                false,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                ReadinessReasonCodes.POSTGRESQL_NOT_EXERCISED))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_EVIDENCE_INVALID));
    }

    @Test
    void evidenceCannotClaimProductionGateStatusFromANonProductionOriginOrEnvironment() {
        ContentHash placeholder = ReadinessTestSupport.hash("placeholder");
        assertThatThrownBy(() -> new RequirementEvidence(
                ReadinessVersions.EVIDENCE_FORMAT_VERSION,
                "ev-1",
                ReadinessRequirement.LIVE_BACKUP_AND_RESTORE,
                EvidenceStatus.SATISFIED,
                EvidenceOrigin.SIMULATED,
                ReadinessEnvironment.PRODUCTION,
                "subject-1",
                "1.0",
                placeholder,
                "readiness/test/x",
                true,
                ReadinessTestSupport.OBSERVED_AT,
                null,
                null,
                placeholder,
                placeholder))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_EVIDENCE_ORIGIN_REJECTED));
    }

    @Test
    void evidenceMustReproduceItsOwnIdentityAndMaterialHashes() {
        RequirementEvidence evidence = ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");
        assertThat(evidence.identityHash()).isEqualTo(RequirementEvidence.identityOf(
                evidence.requirement(), evidence.origin(), evidence.subject(), evidence.version(),
                evidence.evidenceHash()));
        assertThat(evidence.materialHash()).isNotEqualTo(evidence.identityHash());

        assertThatThrownBy(() -> new RequirementEvidence(
                evidence.formatVersion(),
                evidence.evidenceId(),
                evidence.requirement(),
                evidence.status(),
                evidence.origin(),
                evidence.environment(),
                evidence.subject(),
                evidence.version(),
                evidence.evidenceHash(),
                evidence.evidenceReference(),
                evidence.productionGateEvidence(),
                evidence.observedAt(),
                evidence.expiresAt(),
                evidence.reasonCode(),
                ReadinessTestSupport.hash("tampered"),
                evidence.materialHash()))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_EVIDENCE_HASH_MISMATCH));
    }

    @Test
    void anAssessmentCannotRelabelItsStatusOrReorderItsBlockers() {
        ReadinessBlocker blocker = new ReadinessBlocker(
                0, ReadinessBlockerCodes.REQUIREMENT_MISSING,
                ReadinessRequirement.LOCAL_API_END_TO_END, ReadinessLevel.LOCAL_READY);

        assertThatThrownBy(() -> ReadinessAssessment.of(
                "assessment-1", ReadinessLevel.LOCAL_READY, ReadinessStatus.READY, LOCAL, AS_OF,
                List.of(), List.of(blocker)))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID));

        assertThatThrownBy(() -> ReadinessAssessment.of(
                "assessment-1", ReadinessLevel.LOCAL_READY, ReadinessStatus.BLOCKED, LOCAL, AS_OF,
                List.of(), List.of()))
                .isInstanceOf(ReadinessException.class);

        assertThatThrownBy(() -> new ReadinessBlocker(
                0, "NOT_A_CLOSED_CODE",
                ReadinessRequirement.LOCAL_API_END_TO_END, ReadinessLevel.LOCAL_READY))
                .isInstanceOf(ReadinessException.class)
                .satisfies(thrown -> assertThat(((ReadinessException) thrown).code())
                        .isEqualTo(ReadinessErrorCodes.READINESS_REASON_UNKNOWN));
    }

    @Test
    void aRedactedEvidenceRecordPrintsNoSubjectOrReference() {
        RequirementEvidence evidence = ReadinessTestSupport.evidence(
                LOCAL, ReadinessRequirement.LOCAL_API_END_TO_END, "ev-api");
        assertThat(evidence.toString())
                .doesNotContain("subject-1")
                .doesNotContain("readiness/test")
                .contains("LOCAL_API_END_TO_END");
    }
}
