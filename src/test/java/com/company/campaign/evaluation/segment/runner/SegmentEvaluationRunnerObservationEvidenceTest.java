package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
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
 * Requirement 2 of T13-R1: the genuine non-terminal-success evidence — a deterministic clarification and an
 * invalid deterministic validation — is observed and compared, without weakening Tool admission or exposing an
 * artifact to the model.
 */
class SegmentEvaluationRunnerObservationEvidenceTest {

    private static final String TWO_CLAUSE_UTTERANCE = "Gold会员Silver";

    private static SegmentIntentModelPort twoClausePort() {
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

    /** A validation policy that admits the reviewed two-node expectation but refuses the three-node answer. */
    private static SegmentValidationPolicy twoNodePolicy() {
        SegmentValidationPolicy standard = SliceTestFixtures.validationPolicy();
        return new SegmentValidationPolicy(
                2, standard.maxPredicateDepth(), standard.maxAnyWidth(), standard.maxAllWidth(),
                standard.maxRelationNestingDepth());
    }

    @Test
    void aDeterministicClarificationIsObservedAndCompared(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-observation-clarification", SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION,
                RiskLevel.MEDIUM, T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID,
                T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.NEEDS_INPUT)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .selected("member.tier")
                .rejected("member.code")
                .questionCodes("AMBIGUOUS_GROUPING")
                .memberIds(T13TestHost.TIER_FIXTURE);
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-observation-clarification",
                new T13TestHost(SliceTestFixtures.StubModelPort.returning(
                        SliceTestFixtures.ambiguousGroupingJson())));

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        // The genuine resolution verdict is observed even though the Tool did not resolve it.
        assertThat(attempt.disposition()).isEqualTo(Disposition.NEEDS_INPUT.name());
        assertThat(attempt.observedStages()).contains("RESOLUTION");
        assertThat(attempt.evidence().actualClarificationQuestionCodes())
                .containsExactly("AMBIGUOUS_GROUPING");
        assertThat(attempt.evidence().clarificationCodesMatched()).isTrue();
        assertThat(attempt.evidence().mismatched()).isFalse();
    }

    @Test
    void anInvalidValidationIsObservedAndComparedByExactIssueCode(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-observation-validation", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS,
                RiskLevel.MEDIUM, TWO_CLAUSE_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .selected("member.tier")
                .rejected("member.code")
                .ast(T13Scenarios.tierDocument())
                .validationCodes(SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED)
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003");
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-observation-validation", new RestrictedPolicyHost(twoNodePolicy(), twoClausePort()));

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.disposition()).isEqualTo(Disposition.INVALID.name());
        // The genuine invalid validation result reached the comparison boundary, in its exact issue-code order.
        assertThat(attempt.observedStages()).contains("VALIDATION");
        assertThat(attempt.evidence().actualValidationIssueCodes())
                .containsExactly(SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
        assertThat(attempt.evidence().validationIssuesMatched()).isTrue();
        // The public envelope stays bounded: no canonical AST or plan is published for the refused document.
        assertThat(attempt.evidence().actualCanonicalAstHash()).isNull();
        assertThat(report.summary().runInvalid()).isFalse();
    }

    /** A host whose frozen validation policy is deliberately smaller than the answer the provider produces. */
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