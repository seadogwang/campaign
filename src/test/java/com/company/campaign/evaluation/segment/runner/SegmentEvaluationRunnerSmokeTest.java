package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentEvaluationRunnerSmokeTest {

    private static void writeCorpus(Path root, String caseId, RiskLevel risk, T13TestCorpus.CaseHolder holder) {
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();
    }

    @Test
    void aResolvedCaseRunsTheGenuinePipelineAndComparesItsMemberSet(@TempDir Path root) {
        JsonNode value = TextNode.valueOf("Gold");
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-smoke-01", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.MEDIUM,
                "Gold会员", T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .slot("c1", "MEMBER_TIER", "EQUAL", "Gold会员", value)
                .selected("member.tier")
                .ast(tierDocument())
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_TIER_EQUALS_GOLD");
        writeCorpus(root, "case-smoke-01", RiskLevel.MEDIUM, holder);

        EvaluationReport report = new SegmentEvaluationRunner(root).run(
                T13TestAssets.request("t13-smoke-report"),
                new T13TestHost(AgentTestFixtures.portReturning(com.company.campaign.builder.segment.testsupport.SliceTestFixtures.enumTierJson())));

        assertThat(report.cases()).hasSize(1);
        EvaluationReport.CaseOutcome outcome = report.cases().get(0);
        assertThat(outcome.attempts()).hasSize(1);
        EvaluationReport.AttemptOutcome attempt = outcome.attempts().get(0);
        assertThat(attempt.disposition()).isEqualTo(Disposition.RESOLVED.name());
        assertThat(attempt.observedStages()).contains("INTENT", "RESOLUTION", "VALIDATION", "PLAN", "PREVIEW");
        assertThat(attempt.evidence().dispositionMatched()).isTrue();
        assertThat(attempt.evidence().intentSlotsMatched()).isTrue();
        assertThat(attempt.evidence().canonicalAstMatched()).isTrue();
        assertThat(attempt.evidence().memberSets()).hasSize(1);
        assertThat(attempt.evidence().memberSets().get(0).matched()).isTrue();
        assertThat(attempt.evidence().memberSets().get(0).symmetricDifferenceCount()).isZero();
        assertThat(attempt.replayId()).startsWith("replay-");
    }

    @Test
    void aHighRiskCaseRunsExactlyFiveTimes(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-smoke-02", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.HIGH,
                "Gold会员", T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .slot("c1", "MEMBER_TIER", "EQUAL", "Gold会员", TextNode.valueOf("Gold"))
                .selected("member.tier")
                .ast(tierDocument())
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_TIER_EQUALS_GOLD");
        writeCorpus(root, "case-smoke-02", RiskLevel.HIGH, holder);

        EvaluationReport report = new SegmentEvaluationRunner(root).run(
                T13TestAssets.request("t13-smoke-high"),
                new T13TestHost(AgentTestFixtures.portReturning(com.company.campaign.builder.segment.testsupport.SliceTestFixtures.enumTierJson())));

        assertThat(report.cases().get(0).attempts()).hasSize(5);
        assertThat(report.summary().requiredAttemptCount()).isEqualTo(5);
        assertThat(report.summary().notRunAttemptCount()).isZero();
        EvaluationReport.MetricValue repeat = metric(report, MetricId.REPEAT_CONSISTENCY);
        assertThat(repeat.numerator()).isEqualTo(1);
        assertThat(repeat.denominator()).isEqualTo(1);
        assertThat(repeat.value()).isEqualTo("1");
    }

    private static EvaluationReport.MetricValue metric(EvaluationReport report, MetricId id) {
        return report.metrics().stream()
                .filter(value -> value.metricId().equals(id.metricCode()))
                .findFirst()
                .orElseThrow();
    }

    static SegmentDsl tierDocument() {
        Predicate predicate = new AttributePredicate(
                "n-tier", "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD")));
        MetadataBinding binding = MetadataBinding.fromSnapshot(T13TestHost.snapshot());
        return SegmentDsl.publish(SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER, binding, predicate);
    }
}