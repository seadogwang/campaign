package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
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
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.validation.SegmentValidationErrorCodes;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 2 of T13-R2: an expected {@code INVALID} Case that intentionally carries an expected DSL and its
 * exact deterministic validation issue codes is a legitimate reviewed answer, not corrupt expected evidence.
 * Its comparison requires the disposition and the exact issue-code sequence, treats AST/Plan metrics as not
 * applicable, and produces no mismatch taxonomy when every applicable dimension matches — while a wrong issue
 * sequence is still a deterministic mismatch.
 */
class SegmentEvaluationRunnerInvalidExpectationTest {

    private static final String TWO_CLAUSE_UTTERANCE = "Gold会员Silver";

    private static AttributePredicate tier(String nodeId, ComparisonOperator operator, String code) {
        return new AttributePredicate(nodeId, "member.tier", operator, ConditionValue.of(new EnumValue(code)));
    }

    private static SegmentDsl document(Predicate predicate) {
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(T13TestHost.snapshot()), predicate);
    }

    /** A two-clause intent whose compiled root is the {@code ALL} group {@code n-g-r}. */
    private static SegmentIntentModelPort twoClauseAllGroupPort() {
        String json = SliceTestFixtures.intentJson(
                List.of(
                        SliceTestFixtures.clause("c1", "MEMBER_TIER", "ATTRIBUTE", false,
                                "\"operator\":\"EQUAL\",", "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},",
                                "Gold会员", 0),
                        SliceTestFixtures.clause("c2", "MEMBER_TIER", "ATTRIBUTE", false,
                                "\"operator\":\"EQUAL\",", "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Silver\"},",
                                "Silver", 6)),
                "{\"type\":\"ALL\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]}",
                "[]");
        return SliceTestFixtures.StubModelPort.returning(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The reviewed invalid document the two-clause {@code ALL} intent compiles to. */
    private static SegmentDsl allGroupDocument() {
        return document(new GroupPredicate("n-g-r", PredicateType.ALL, List.of(
                tier("n-c-c1", ComparisonOperator.EQUAL, "GOLD"),
                tier("n-c-c2", ComparisonOperator.EQUAL, "SILVER"))));
    }

    /** A two-clause intent whose compiled root is the {@code ANY} group {@code n-g-r}. */
    private static SegmentIntentModelPort twoClauseAnyGroupPort() {
        String json = SliceTestFixtures.intentJson(
                List.of(
                        SliceTestFixtures.clause("c1", "MEMBER_TIER", "ATTRIBUTE", false,
                                "\"operator\":\"EQUAL\",", "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},",
                                "Gold会员", 0),
                        SliceTestFixtures.clause("c2", "MEMBER_TIER", "ATTRIBUTE", false,
                                "\"operator\":\"EQUAL\",", "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Silver\"},",
                                "Silver", 6)),
                "{\"type\":\"ANY\",\"children\":[{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                        + "{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]}",
                "[]");
        return SliceTestFixtures.StubModelPort.returning(json.getBytes(StandardCharsets.UTF_8));
    }

    private static T13TestCorpus.CaseHolder invalidCase(
            String caseId, SegmentDsl reviewedInvalidDsl, String... validationCodes) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                caseId, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.MEDIUM,
                TWO_CLAUSE_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.INVALID)
                .ast(reviewedInvalidDsl)
                .validationCodes(validationCodes)
                .selected("member.tier")
                .rejected("member.code")
                .memberIds(T13TestHost.TIER_FIXTURE);
        return holder;
    }

    private static void writeCorpus(Path root, T13TestCorpus.CaseHolder holder) {
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();
    }

    private static EvaluationReport.AttemptOutcome onlyAttempt(EvaluationReport report) {
        return report.cases().get(0).attempts().get(0);
    }

    @Test
    void aMatchingInvalidValidationIsComparedByDispositionIssueSequenceAndInvalidDsl(@TempDir Path root) {
        // The reviewed invalid document is exactly the three-node ALL group the provider's intent compiles to;
        // a two-node policy genuinely refuses it, and T07 reports exactly the declared issue code.
        T13TestCorpus.CaseHolder holder = invalidCase(
                "case-invalid-expected-match", allGroupDocument(),
                SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
        holder.slot("c1", "MEMBER_TIER", "EQUAL", "Gold会员", TextNode.valueOf("Gold"));
        holder.slot("c2", "MEMBER_TIER", "EQUAL", "Silver", TextNode.valueOf("Silver"));
        writeCorpus(root, holder);

        RestrictedPolicyHost host = new RestrictedPolicyHost(
                twoNodePolicy(), twoClauseAllGroupPort());
        SegmentEvaluationRunner.FrozenRun frozen = new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-invalid-expected-match"), host);

        // The reviewed invalid DSL froze against the genuine T07 refusal; no corpus-invalidity code was set.
        assertThat(frozen.expectedEvidenceCodes()).doesNotContainKey("case-invalid-expected-match");
        assertThat(frozen.expectedInvalidDslHashes()).containsKey("case-invalid-expected-match");

        EvaluationReport report = T13Scenarios.run(root, "t13-invalid-expected-match", host);

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.INVALID.name());
        assertThat(attempt.evidence().dispositionMatched()).isTrue();
        assertThat(attempt.evidence().expectedValidationIssueCodes())
                .containsExactly(SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
        assertThat(attempt.evidence().actualValidationIssueCodes())
                .containsExactly(SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
        assertThat(attempt.evidence().validationIssuesMatched()).isTrue();
        assertThat(attempt.evidence().invalidExpectedDslMatched()).isTrue();
        assertThat(attempt.evidence().expectedInvalidDslHash())
                .isEqualTo(attempt.evidence().actualInvalidDslHash());
        // An invalid document has no canonical AST or Plan, so both are not applicable, not missing.
        assertThat(attempt.evidence().canonicalAstMatched()).isTrue();
        assertThat(attempt.evidence().planApplicable()).isFalse();
        assertThat(attempt.evidence().actualCanonicalAstHash()).isNull();
        // Every applicable dimension matched, so there is no mismatch taxonomy and the run stays sound.
        assertThat(attempt.evidence().mismatched()).isFalse();
        assertThat(attempt.taxonomy()).isNull();
        assertThat(report.summary().runInvalid()).isFalse();
        // The AST metric is not applicable to an invalid document: it is excluded, never counted as a failure.
        assertThat(report.metrics().stream()
                .filter(metric -> metric.metricId().equals(MetricId.CANONICAL_AST_EXACT_MATCH.metricCode()))
                .findFirst().orElseThrow())
                .satisfies(metric -> {
                    assertThat(metric.denominator()).isZero();
                    assertThat(metric.excluded()).isEqualTo(1);
                    assertThat(metric.invalid()).isZero();
                });
    }

    @Test
    void aDifferentInvalidIssueCodeIsADeterministicMismatch(@TempDir Path root) {
        // The reviewed document is a two-child ALL group, genuinely refused for ALL width; the provider answers
        // with the same shape as an ANY group, genuinely refused for ANY width.
        T13TestCorpus.CaseHolder holder = invalidCase(
                "case-invalid-expected-code", allGroupDocument(),
                SegmentValidationErrorCodes.COMPLEXITY_ALL_WIDTH_EXCEEDED);
        holder.slot("c1", "MEMBER_TIER", "EQUAL", "Gold会员", TextNode.valueOf("Gold"));
        holder.slot("c2", "MEMBER_TIER", "EQUAL", "Silver", TextNode.valueOf("Silver"));
        writeCorpus(root, holder);

        RestrictedPolicyHost host = new RestrictedPolicyHost(narrowWidthPolicy(), twoClauseAnyGroupPort());
        SegmentEvaluationRunner.FrozenRun frozen = new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-invalid-expected-code"), host);
        assertThat(frozen.expectedEvidenceCodes()).doesNotContainKey("case-invalid-expected-code");
        assertThat(frozen.expectedInvalidDslHashes()).containsKey("case-invalid-expected-code");

        EvaluationReport report = T13Scenarios.run(root, "t13-invalid-expected-code", host);

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.INVALID.name());
        assertThat(attempt.evidence().expectedValidationIssueCodes())
                .containsExactly(SegmentValidationErrorCodes.COMPLEXITY_ALL_WIDTH_EXCEEDED);
        assertThat(attempt.evidence().actualValidationIssueCodes())
                .containsExactly(SegmentValidationErrorCodes.COMPLEXITY_ANY_WIDTH_EXCEEDED);
        assertThat(attempt.evidence().validationIssuesMatched()).isFalse();
        assertThat(attempt.evidence().invalidExpectedDslMatched()).isFalse();
        assertThat(attempt.evidence().mismatched()).isTrue();
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
        assertThat(report.summary().mismatchedAttemptCount()).isEqualTo(1);
        assertThat(report.summary().runInvalid()).isFalse();
    }

    @Test
    void anInvalidExpectationWhoseIssueSequenceContradictsT07IsInputInvalidity(@TempDir Path root) {
        // The reviewed document is genuinely refused for ALL width, but the reviewed expectation declares a
        // different issue code: the expectation itself is contradictory, which is input invalidity.
        T13TestCorpus.CaseHolder holder = invalidCase(
                "case-invalid-expected-contradiction", allGroupDocument(),
                SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
        holder.slot("c1", "MEMBER_TIER", "EQUAL", "Gold会员", TextNode.valueOf("Gold"));
        holder.slot("c2", "MEMBER_TIER", "EQUAL", "Silver", TextNode.valueOf("Silver"));
        writeCorpus(root, holder);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-invalid-expected-contradiction",
                new RestrictedPolicyHost(narrowWidthPolicy(), twoClauseAnyGroupPort()));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.evidence().expectedEvidenceCode())
                .isEqualTo(SegmentEvaluationRunner.EXPECTED_EVIDENCE_ISSUE_SEQUENCE_CONTRADICTED);
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.INPUT_OR_PIN_INVALIDITY);
        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
    }

    @Test
    void anInvalidExpectationWhoseDslActuallyValidatesIsInputInvalidity(@TempDir Path root) {
        // The reviewed expectation claims a deterministic refusal, but the genuine T07 accepts its DSL: the
        // reviewed claim contradicts the accepted pipeline, which is input invalidity.
        SegmentDsl valid = document(tier("n-c-c1", ComparisonOperator.EQUAL, "GOLD"));
        T13TestCorpus.CaseHolder holder = invalidCase(
                "case-invalid-expected-valid", valid,
                SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
        holder.slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"));
        writeCorpus(root, holder);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-invalid-expected-valid",
                new RestrictedPolicyHost(oneNodePolicy(), twoClauseAllGroupPort()));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.evidence().expectedEvidenceCode())
                .isEqualTo(SegmentEvaluationRunner.EXPECTED_EVIDENCE_INVALID_DSL_VALIDATED);
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.INPUT_OR_PIN_INVALIDITY);
        assertThat(report.summary().runInvalid()).isTrue();
    }

    private static SegmentValidationPolicy oneNodePolicy() {
        SegmentValidationPolicy standard = SliceTestFixtures.validationPolicy();
        return new SegmentValidationPolicy(
                1, standard.maxPredicateDepth(), standard.maxAnyWidth(), standard.maxAllWidth(),
                standard.maxRelationNestingDepth());
    }

    private static SegmentValidationPolicy twoNodePolicy() {
        SegmentValidationPolicy standard = SliceTestFixtures.validationPolicy();
        return new SegmentValidationPolicy(
                2, standard.maxPredicateDepth(), standard.maxAnyWidth(), standard.maxAllWidth(),
                standard.maxRelationNestingDepth());
    }

    private static SegmentValidationPolicy narrowWidthPolicy() {
        SegmentValidationPolicy standard = SliceTestFixtures.validationPolicy();
        return new SegmentValidationPolicy(
                standard.maxPredicateNodes(), standard.maxPredicateDepth(), 1, 1,
                standard.maxRelationNestingDepth());
    }

    /** A host whose frozen validation policy is deliberately narrower than the reviewed answer expects. */
    private static final class RestrictedPolicyHost extends T13TestHost {

        private final SegmentValidationPolicy policy;

        RestrictedPolicyHost(SegmentValidationPolicy policy, SegmentIntentModelPort port) {
            super(port);
            this.policy = policy;
        }

        @Override
        public SegmentValidationPolicy validationPolicy() {
            return policy;
        }

        @Override
        public SegmentAgentEnvironment agentEnvironment(
                EvaluationCasePins pins, SegmentStageObserver observer, SegmentIntentModelPort port) {
            return super.agentEnvironment(pins, observer, port).withValidationPolicy(policy);
        }
    }
}