package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.ExplanationClaim;
import com.company.campaign.builder.agent.ExplanationClaimKind;
import com.company.campaign.builder.agent.SegmentAgentException;
import com.company.campaign.builder.refine.RefinementOpKind;
import com.company.campaign.builder.refine.SegmentRefinementEditor;
import com.company.campaign.builder.refine.SegmentRefinementOperation;
import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T13-R3 requirement 1: the grounded-explanation dimension is applicable exactly where the accepted execution
 * path can genuinely produce a grounded explanation, and whenever it is applicable a disagreement contributes
 * to the mismatch verdict, the attempt taxonomy and the summary mismatch count.
 *
 * <p>A non-applicable path is represented deterministically by {@code explanationApplicable=false} and never
 * manufactures a mismatch out of an absent claim list, and the comparison never bridges the reviewed corpus's
 * upper-case machine codes and the accepted positional {@code claim-N} identifiers.
 */
class SegmentEvaluationRunnerGroundedExplanationTest {

    private static final String ROOT = "n-root";
    private static final ContentHash HASH = new ContentHash("sha256:" + "a".repeat(64));

    /**
     * The bounded comparison evidence of one resolved create attempt in which every comparable dimension
     * matches, so the grounded-explanation dimensions under test are isolated from the rest of the verdict.
     */
    private static EvaluationReport.ComparisonEvidence createEvidence(
            boolean explanationApplicable,
            boolean explanationClaimsMatched,
            List<String> expectedClaimIds,
            List<String> actualClaimIds) {
        return new EvaluationReport.ComparisonEvidence(
                T13Versions.COMPARISON_VERSION, false, null,
                true, Disposition.RESOLVED.name(), Disposition.RESOLVED.name(),
                true, 1, 1, true, true, true,
                true, HASH, HASH,
                true, List.of(), List.of(),
                true, null, null,
                true, true, true, HASH, HASH,
                true, List.of(), List.of(),
                explanationApplicable, explanationClaimsMatched, expectedClaimIds, actualClaimIds,
                true, true, null, null, true, true, false, List.of());
    }

    @Test
    void aMatchingApplicableClaimIdSequenceIsNotAMismatch() {
        EvaluationReport.ComparisonEvidence evidence = createEvidence(
                true, true, List.of("CLAIM_TIER_EQUALS_GOLD"), List.of("CLAIM_TIER_EQUALS_GOLD"));

        assertThat(evidence.explanationApplicable()).isTrue();
        assertThat(evidence.explanationClaimsMatched()).isTrue();
        assertThat(evidence.mismatched()).isFalse();
    }

    @Test
    void aWrongApplicableClaimIdSequenceIsAComparisonMismatch() {
        EvaluationReport.ComparisonEvidence evidence = createEvidence(
                true, false, List.of("CLAIM_TIER_EQUALS_GOLD"), List.of("CLAIM_TIER_NOT_GOLD"));

        assertThat(evidence.mismatched()).isTrue();
    }

    @Test
    void aMissingApplicableClaimIdSequenceIsAComparisonMismatch() {
        EvaluationReport.ComparisonEvidence evidence =
                createEvidence(true, false, List.of("CLAIM_TIER_EQUALS_GOLD"), List.of());

        assertThat(evidence.actualExplanationClaimIds()).isEmpty();
        assertThat(evidence.mismatched()).isTrue();
    }

    @Test
    void aReversedApplicableClaimIdSequenceIsAComparisonMismatch() {
        // The same two identifiers in the opposite order form the same set but not the same sequence. The
        // accepted comparison is an exact ordered sequence, so the reversal is a mismatch; the old set
        // comparison would have silently accepted it.
        List<String> expected = List.of("claim-1", "claim-2");
        List<String> actual = List.of("claim-2", "claim-1");

        assertThat(SegmentEvaluationRunner.claimSequencesMatch(expected, actual)).isFalse();
        EvaluationReport.ComparisonEvidence evidence = createEvidence(true, false, expected, actual);
        assertThat(evidence.expectedExplanationClaimIds()).containsExactly("claim-1", "claim-2");
        assertThat(evidence.actualExplanationClaimIds()).containsExactly("claim-2", "claim-1");
        assertThat(evidence.explanationClaimsMatched()).isFalse();
        assertThat(evidence.mismatched()).isTrue();
    }

    @Test
    void aCardinalityDifferenceInAnApplicableClaimIdSequenceIsAComparisonMismatch() {
        // A repeated identifier and a missing identifier are both cardinality differences. Neither is a set
        // difference, so a set comparison would treat both pairs as equal; the exact sequence comparison does not.
        List<String> expected = List.of("claim-1", "claim-2");
        List<String> duplicated = List.of("claim-1", "claim-2", "claim-2");
        List<String> omitted = List.of("claim-1");

        assertThat(SegmentEvaluationRunner.claimSequencesMatch(expected, duplicated)).isFalse();
        assertThat(SegmentEvaluationRunner.claimSequencesMatch(expected, omitted)).isFalse();
        assertThat(createEvidence(true, false, expected, duplicated).mismatched()).isTrue();
        assertThat(createEvidence(true, false, expected, omitted).mismatched()).isTrue();
    }

    @Test
    void anIdenticalApplicableClaimIdSequenceMatchesExactly() {
        // The exact sequence — order and cardinality both retained — is the only sequence that matches.
        List<String> sequence = List.of("claim-2", "claim-1", "claim-1");

        assertThat(SegmentEvaluationRunner.claimSequencesMatch(sequence, sequence)).isTrue();
        assertThat(createEvidence(true, true, sequence, sequence).mismatched()).isFalse();
    }

    @Test
    void aNonApplicableDimensionNeverManufacturesAMismatchFromAbsentClaims() {
        // An inapplicable dimension is reported deterministically false, and it does not drive the verdict even
        // when the reviewed expectation declares claim identifiers the path could not produce.
        EvaluationReport.ComparisonEvidence evidence =
                createEvidence(false, false, List.of("CLAIM_CLARIFICATION_REQUIRED"), List.of());

        assertThat(evidence.explanationApplicable()).isFalse();
        assertThat(evidence.explanationClaimsMatched()).isFalse();
        assertThat(evidence.mismatched()).isFalse();
    }

    @Test
    void aNonResolvedCaseDoesNotFailOnlyBecauseItHasNoGroundedExplanation(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-explanation-needs-input", SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION,
                RiskLevel.MEDIUM, T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID,
                T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.NEEDS_INPUT)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .selected("member.tier")
                .rejected("member.code")
                .questionCodes("AMBIGUOUS_GROUPING")
                .memberIds(T13TestHost.TIER_FIXTURE)
                .claims("CLAIM_CLARIFICATION_REQUIRED");
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-explanation-needs-input",
                new T13TestHost(SliceTestFixtures.StubModelPort.returning(
                        SliceTestFixtures.ambiguousGroupingJson())));

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.disposition()).isEqualTo(Disposition.NEEDS_INPUT.name());
        // The reviewed expectation does declare a claim identifier, and the accepted path produced none, so a
        // naive comparison would report a mismatch. Applicability is explicit, so it does not.
        assertThat(attempt.evidence().expectedExplanationClaimIds()).containsExactly("CLAIM_CLARIFICATION_REQUIRED");
        assertThat(attempt.evidence().actualExplanationClaimIds()).isEmpty();
        assertThat(attempt.evidence().explanationApplicable()).isFalse();
        assertThat(attempt.evidence().mismatched()).isFalse();
        assertThat(attempt.taxonomy()).isNull();
        assertThat(report.summary().mismatchedAttemptCount()).isZero();
    }

    @Test
    void aRefinementAttemptIsNotApplicableAndDoesNotFailOnlyBecauseItHasNoComparableClaims(@TempDir Path root) {
        SegmentDsl base = document(all(
                tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                tier("n-b", ComparisonOperator.NOT_EQUAL, "SILVER")));
        SegmentRefinementPatch patch = SegmentRefinementPatch.publish(
                ContractVersions.CURRENT, base.editableDslHash(),
                List.of(new SegmentRefinementOperation.MoveNode("op-1", "n-b", ROOT, "n-a")));
        SegmentDsl refined = new SegmentRefinementEditor().apply(base, patch);

        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-explanation-refinement", SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE,
                RiskLevel.MEDIUM, "refinement utterance", T13TestHost.SNAPSHOT_ID,
                T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .currentSegment(base)
                .ast(refined)
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .refinement(RefinementOperation.MOVE_NODE, "n-b", ROOT)
                .claims("CLAIM_REORDER_TIER_CONDITION");
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();

        EvaluationReport report = T13Scenarios.run(
                root, "t13-explanation-refinement",
                new T13TestHost(T13Scenarios.tierPort()).refinementPatch(patch));

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.evidence().refinementAttempt()).isTrue();
        assertThat(attempt.evidence().refinementMatched()).isTrue();
        assertThat(attempt.evidence().explanationApplicable()).isFalse();
        assertThat(attempt.evidence().mismatched()).isFalse();
        assertThat(attempt.taxonomy()).isNull();
    }

    @Test
    void theReviewedCorpusAndTheAcceptedExplanationVocabulariesAreStructurallyIncompatible(@TempDir Path root) {
        // The reviewed corpus validates its claim identifiers against the upper-case machine-code grammar, so it
        // cannot even declare an accepted positional claim id.
        T13TestCorpus.CaseHolder rejected = new T13TestCorpus.CaseHolder(
                "case-explanation-vocabulary", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS,
                RiskLevel.MEDIUM, T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID,
                T13TestHost.CONTEXT_FIXTURE);
        rejected.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .ast(T13Scenarios.tierDocument())
                .memberIds(T13TestHost.TIER_FIXTURE)
                .claims("claim-1");
        assertThatThrownBy(() -> T13Scenarios.corpusWith(root, rejected))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("groundedExplanationClaimIds");

        // The accepted explanation's claim id grammar cannot express a reviewed corpus code either, so the two
        // vocabularies can never intersect. T13-R3 reports that gap honestly instead of bridging it.
        assertThatThrownBy(() -> new ExplanationClaim(
                "CLAIM_TIER_EQUALS_GOLD", ExplanationClaimKind.CLAUSE_DEFINITION, List.of(), List.of("n-tier"),
                List.of("member.tier"), null, null, null, null, null))
                .isInstanceOf(SegmentAgentException.class)
                .hasMessageContaining("deterministic claim id");
    }

    private static SegmentDsl document(Predicate predicate) {
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(T13TestHost.snapshot()), predicate);
    }

    private static AttributePredicate tier(String nodeId, ComparisonOperator operator, String code) {
        return new AttributePredicate(nodeId, "member.tier", operator, ConditionValue.of(new EnumValue(code)));
    }

    private static Predicate all(Predicate... children) {
        return new com.company.campaign.segment.dsl.predicate.GroupPredicate(
                ROOT, com.company.campaign.segment.dsl.predicate.PredicateType.ALL, List.of(children));
    }
}