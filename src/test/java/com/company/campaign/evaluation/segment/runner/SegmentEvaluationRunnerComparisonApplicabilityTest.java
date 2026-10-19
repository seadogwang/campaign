package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextResolver;
import com.company.campaign.security.context.VerifiedIdentity;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 1 of T13-R1: comparison applicability is disposition- and path-aware, so a correct
 * {@code NEEDS_INPUT}, {@code UNSUPPORTED}, {@code DENIED}, create or refinement outcome is never reported as
 * an AST/Plan mismatch merely because both sides are absent or because the refinement path has no Intent.
 */
class SegmentEvaluationRunnerComparisonApplicabilityTest {

    private static EvaluationReport.AttemptOutcome onlyAttempt(EvaluationReport report) {
        return report.cases().get(0).attempts().get(0);
    }

    /** A create Case whose every expected dimension is exactly what the deterministic tier provider answers. */
    private static T13TestCorpus.CaseHolder matchingTierCase(String caseId) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                caseId, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.MEDIUM,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .selected("member.tier")
                .rejected("member.code")
                .ast(T13Scenarios.tierDocument())
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_TIER_EQUALS_GOLD");
        return holder;
    }

    /** A context whose tenant contradicts the pinned Metadata owner, so the Agent denies the run. */
    private static com.company.campaign.security.context.TrustedExecutionContext foreignContext() {
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                "tenant-002", "program-001", "user-002", "scope-008", T13TestHost.SNAPSHOT_ID,
                LocaleTag.of(Locale.SIMPLIFIED_CHINESE), T13TestHost.context().snapshotRef().timezone(),
                "CNY", T13TestHost.context().snapshotRef().asOf(),
                T13TestHost.context().snapshotRef().dataWatermark());
        return TrustedContextResolver.resolve(
                new VerifiedIdentity("user-002", "https://issuer.example", Instant.parse("2026-10-01T00:00:00Z")),
                new PrincipalMembership(
                        "membership-2", "user-002", "tenant-002", "program-001", "scope-008",
                        MembershipStatus.ACTIVE, MetadataFixtures.allPermissions()),
                reference);
    }

    @Test
    void aCreateCaseWhoseEveryComparableDimensionMatchesStillReportsTheExplanationVocabularyGap(
            @TempDir Path root) {
        T13Scenarios.corpusWith(root, matchingTierCase("case-applicability-01"));

        EvaluationReport report = T13Scenarios.run(root, "t13-applicability-01", T13Scenarios.tierPort());

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.RESOLVED.name());
        assertThat(attempt.evidence().planApplicable()).isTrue();
        // Every create-path dimension the reviewed expectation can express matches the accepted pipeline
        // exactly. The grounded-explanation dimension is applicable on this accepted success path, and the
        // reviewed corpus claim code can never equal an accepted positional claim-N identifier, so T13-R3
        // reports that comparison honestly as a mismatch instead of suppressing it.
        assertThat(attempt.evidence().intentSlotsMatched()).isTrue();
        assertThat(attempt.evidence().intentSourceEvidenceMatched()).isTrue();
        assertThat(attempt.evidence().selectedCandidatesMatched()).isTrue();
        assertThat(attempt.evidence().rejectedCandidatesMatched()).isTrue();
        assertThat(attempt.evidence().canonicalAstMatched()).isTrue();
        assertThat(attempt.evidence().expectedPlanMatched()).isTrue();
        assertThat(attempt.evidence().clarificationCodesMatched()).isTrue();
        assertThat(attempt.evidence().forbiddenAssumptionsMatched()).isTrue();
        assertThat(attempt.evidence().explanationApplicable()).isTrue();
        assertThat(attempt.evidence().expectedExplanationClaimIds()).containsExactly("CLAIM_TIER_EQUALS_GOLD");
        assertThat(attempt.evidence().actualExplanationClaimIds())
                .isNotEmpty()
                .allSatisfy(id -> assertThat(id).matches("^claim-[1-9][0-9]{0,5}$"));
        assertThat(attempt.evidence().explanationClaimsMatched()).isFalse();
        assertThat(attempt.evidence().mismatched()).isTrue();
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
        assertThat(report.summary().mismatchedAttemptCount()).isEqualTo(1);
    }

    @Test
    void aFullyMatchingNeedsInputCaseHasNoComparisonMismatch(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-applicability-02", SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION,
                RiskLevel.MEDIUM, T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID,
                T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.NEEDS_INPUT)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .selected("member.tier")
                .rejected("member.code")
                .questionCodes("AMBIGUOUS_GROUPING")
                .memberIds(T13TestHost.TIER_FIXTURE)
                .forbidden("ASSUME_INDEX_PATCH");
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-applicability-02",
                new T13TestHost(SliceTestFixtures.StubModelPort.returning(
                        SliceTestFixtures.ambiguousGroupingJson())));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.NEEDS_INPUT.name());
        // A prohibited disposition that produced no AST and no Plan is a match, not a mismatch.
        assertThat(attempt.evidence().canonicalAstMatched()).isTrue();
        assertThat(attempt.evidence().planApplicable()).isFalse();
        assertThat(attempt.evidence().mismatched()).isFalse();
        assertThat(attempt.taxonomy()).isNull();
    }

    @Test
    void aFullyMatchingUnsupportedCaseHasNoComparisonMismatch(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-applicability-03", SegmentCategory.UNSUPPORTED_CONCEPTS_AND_RAW_SQL, RiskLevel.MEDIUM,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.UNSUPPORTED)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .memberIds(T13TestHost.TIER_FIXTURE)
                .forbidden("ASSUME_RAW_SQL_SUPPORTED");
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-applicability-03", new T13TestHost(T13Scenarios.unsupportedPort()));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.UNSUPPORTED.name());
        assertThat(attempt.evidence().canonicalAstMatched()).isTrue();
        assertThat(attempt.evidence().mismatched()).isFalse();
        assertThat(attempt.taxonomy()).isNull();
        assertThat(attempt.observedStages()).doesNotContain("RESOLUTION");
    }

    @Test
    void aFullyMatchingDeniedCaseHasNoComparisonMismatch(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-applicability-04", SegmentCategory.PROMPT_INJECTION_UNAUTHORIZED_AND_CROSS_TENANT,
                RiskLevel.MEDIUM, T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID,
                "fixture-t13-context-foreign");
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.DENIED)
                .memberIds(T13TestHost.TIER_FIXTURE)
                .forbidden("ASSUME_CROSS_TENANT_ALLOWED");
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .context("fixture-t13-context-foreign", foreignContext().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();

        EvaluationReport report = T13Scenarios.run(
                root, "t13-applicability-04",
                new T13TestHost(T13Scenarios.tierPort())
                        .context("fixture-t13-context-foreign", foreignContext()));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.DENIED.name());
        assertThat(attempt.evidence().canonicalAstMatched()).isTrue();
        assertThat(attempt.evidence().mismatched()).isFalse();
        assertThat(attempt.taxonomy()).isNull();
    }

    @Test
    void aProhibitedOutcomeThatUnexpectedlyProducesEvidenceIsAMismatch(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-applicability-05", SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION,
                RiskLevel.MEDIUM, T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID,
                T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.NEEDS_INPUT)
                .questionCodes("AMBIGUOUS_GROUPING")
                .memberIds(T13TestHost.TIER_FIXTURE);
        T13Scenarios.corpusWith(root, holder);

        // The provider resolves the utterance, so the pipeline produces AST evidence a NEEDS_INPUT expectation
        // forbids: the evidence must be reported as a mismatch, not silently treated as absent.
        EvaluationReport report = T13Scenarios.run(root, "t13-applicability-05", T13Scenarios.tierPort());

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.RESOLVED.name());
        assertThat(attempt.evidence().canonicalAstMatched()).isFalse();
        assertThat(attempt.evidence().mismatched()).isTrue();
        assertThat(attempt.taxonomy()).isNotNull();
    }

    @Test
    void equalSourceTextAtTheWrongOffsetsIsAMismatch(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-applicability-06", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.MEDIUM,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .selected("member.tier")
                .rejected("member.code")
                .ast(T13Scenarios.tierDocument())
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_TIER_EQUALS_GOLD");
        // The same text, but pinned at the wrong half-open range.
        holder.slotWithSpan("c1", "MEMBER_TIER", "EQUAL", true,
                new SourceSpan(T13Scenarios.TIER_UTTERANCE, 1, 7), TextNode.valueOf("Gold"));
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(root, "t13-applicability-06", T13Scenarios.tierPort());

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.evidence().intentSlotsMatched()).isTrue();
        assertThat(attempt.evidence().intentSourceEvidenceMatched()).isFalse();
        assertThat(attempt.evidence().mismatched()).isTrue();
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
    }
}