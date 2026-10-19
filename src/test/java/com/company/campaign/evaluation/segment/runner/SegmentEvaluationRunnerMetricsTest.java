package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.LocaleTag;
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
 * Section 5: each metric is computed and reported separately with its own immutable identifier, numerator,
 * denominator, exclusion rule and exact decimal value, with an explicitly undefined zero denominator, and with
 * the per-category and per-risk breakdowns T14 needs.
 */
class SegmentEvaluationRunnerMetricsTest {

    private static EvaluationReport.MetricValue metric(EvaluationReport report, MetricId id) {
        return report.metrics().stream()
                .filter(value -> value.metricId().equals(id.metricCode()))
                .findFirst()
                .orElseThrow();
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
    void everyMetricIsReportedSeparatelyWithItsOwnRule(@TempDir Path root) {
        T13TestCorpus.CaseHolder matched = T13Scenarios.tierCase(
                "case-metric-match", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS);

        T13TestCorpus.CaseHolder setMismatch = T13Scenarios.tierCase(
                        "case-metric-set-mismatch", RiskLevel.MEDIUM,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001");

        T13TestCorpus.CaseHolder unsupported = new T13TestCorpus.CaseHolder(
                "case-metric-unsupported", SegmentCategory.UNSUPPORTED_CONCEPTS_AND_RAW_SQL, RiskLevel.MEDIUM,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        unsupported.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.UNSUPPORTED)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .memberIds(T13TestHost.TIER_FIXTURE)
                .forbidden("ASSUME_RAW_SQL_SUPPORTED");

        T13TestCorpus.CaseHolder denied = new T13TestCorpus.CaseHolder(
                "case-metric-denied", SegmentCategory.PROMPT_INJECTION_UNAUTHORIZED_AND_CROSS_TENANT,
                RiskLevel.HIGH, T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, "fixture-t13-context-foreign");
        denied.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.DENIED)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .memberIds(T13TestHost.TIER_FIXTURE)
                .forbidden("ASSUME_CROSS_TENANT_ALLOWED");

        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .context("fixture-t13-context-foreign", foreignContext().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(matched)
                .caseSpec(setMismatch)
                .caseSpec(unsupported)
                .caseSpec(denied)
                .write();

        EvaluationReport report = T13Scenarios.run(
                root, "t13-metrics",
                new T13TestHost(T13Scenarios.tierPort()).context("fixture-t13-context-foreign", foreignContext()));

        assertThat(report.metrics()).hasSize(MetricId.values().length);
        assertThat(report.metrics()).extracting(EvaluationReport.MetricValue::metricId)
                .containsExactlyElementsOf(java.util.Arrays.stream(MetricId.values())
                        .map(MetricId::metricCode).toList());

        // Every Case pins an expected member set, so the metric is defined over every attempt, and exactly
        // the deliberately wrong expectation fails.
        assertThat(metric(report, MetricId.MEMBER_SET_EXACT_MATCH).denominator())
                .isEqualTo(report.summary().executedAttemptCount());
        assertThat(report.cases()).allSatisfy(outcome -> {
            // Two Cases are deliberately wrong: one pins a wrong member set, and the UNSUPPORTED Case is
            // answered by a provider that resolves it, which is exactly the mismatch the metric must report.
            boolean expectedMatch = outcome.caseId().equals("case-metric-match")
                    || outcome.caseId().equals("case-metric-denied");
            assertThat(outcome.attempts()).allSatisfy(attempt -> {
                assertThat(attempt.evidence().memberSets()).as(outcome.caseId()).isNotEmpty();
                assertThat(attempt.evidence().memberSetsMatched()).as(outcome.caseId())
                        .isEqualTo(expectedMatch);
            });
        });
        // The UNSUPPORTED Case was answered with a resolved answer, so recall is zero over one expected case.
        assertThat(metric(report, MetricId.UNSUPPORTED_RECALL).numerator()).isZero();
        assertThat(metric(report, MetricId.UNSUPPORTED_RECALL).denominator()).isEqualTo(1);
        // The DENIED Case is high-risk, so all five attempts are counted and all five are denied.
        assertThat(metric(report, MetricId.DENIED_RECALL).numerator()).isEqualTo(5);
        assertThat(metric(report, MetricId.DENIED_RECALL).denominator()).isEqualTo(5);
        assertThat(metric(report, MetricId.DENIED_RECALL).value()).isEqualTo("1");
        assertThat(metric(report, MetricId.SILENT_CRITICAL_ASSUMPTION_RATE).numerator()).isZero();
        assertThat(metric(report, MetricId.SILENT_CRITICAL_ASSUMPTION_RATE).denominator()).isEqualTo(6);
        assertThat(metric(report, MetricId.CLARIFICATION_RECALL).defined()).isFalse();
        assertThat(metric(report, MetricId.CLARIFICATION_PRECISION).defined()).isFalse();
        assertThat(metric(report, MetricId.REFINEMENT_TARGET_ACCURACY).defined()).isFalse();

        // Per-category and per-risk breakdowns are present and carry the full metric set.
        assertThat(report.perCategory()).containsKeys(
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS.name(),
                SegmentCategory.UNSUPPORTED_CONCEPTS_AND_RAW_SQL.name(),
                SegmentCategory.PROMPT_INJECTION_UNAUTHORIZED_AND_CROSS_TENANT.name());
        assertThat(report.perRisk()).containsKeys(RiskLevel.MEDIUM.name(), RiskLevel.HIGH.name());
        assertThat(report.perCategory().values()).allSatisfy(values ->
                assertThat(values).hasSize(MetricId.values().length));
        assertThat(metric(report, MetricId.FIRST_PASS_SUCCESS).denominator())
                .isEqualTo(report.summary().executedAttemptCount());
    }

    @Test
    void anUndefinedDenominatorIsReportedExplicitlyAndNeverAsZeroOrOne() {
        EvaluationReport.MetricValue undefined = new EvaluationReport.MetricValue(
                MetricId.CLARIFICATION_RECALL.metricCode(), 0, 0, 7, 0, false, null);
        assertThat(undefined.defined()).isFalse();
        assertThat(undefined.value()).isNull();
        assertThat(undefined.excluded()).isEqualTo(7);

        EvaluationReport.MetricValue defined = new EvaluationReport.MetricValue(
                MetricId.CLARIFICATION_RECALL.metricCode(), 2, 3, 0, 0, true, "0.666666666667");
        assertThat(defined.value()).isEqualTo("0.666666666667");
    }

    @Test
    void exactDecimalRatiosAreDeterministicAndHaveNoFloatingPointStep() {
        assertThat(SegmentEvaluationRunner.ratio(1, 3)).isEqualTo("0.333333333333");
        assertThat(SegmentEvaluationRunner.ratio(2, 3)).isEqualTo("0.666666666667");
        assertThat(SegmentEvaluationRunner.ratio(1, 1)).isEqualTo("1");
        assertThat(SegmentEvaluationRunner.ratio(0, 5)).isEqualTo("0");
        assertThat(SegmentEvaluationRunner.ratio(1, 8)).isEqualTo("0.125");
    }

    @Test
    void anUndefinedMetricIsNeverSilentlyZeroOrOne() {
        EvaluationReport.MetricValue one = new EvaluationReport.MetricValue(
                MetricId.UNSUPPORTED_RECALL.metricCode(), 0, 0, 4, 0, false, null);
        assertThat(one.value()).isNull();
        assertThat(one.denominator()).isZero();
        assertThat(one.numerator()).isZero();
    }

    @Test
    void aSilentCriticalAssumptionIsCountedWhenAnObservationViolatesAForbiddenAssumption(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-metric-assumption", SegmentCategory.NOT_NOT_IN_NULL_NEGATIVE_RANGES, RiskLevel.HIGH,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .slot("c1", "MEMBER_TIER", "EQUAL", T13Scenarios.TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .selected("member.tier")
                .ast(T13Scenarios.tierDocument())
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_TIER_EQUALS_GOLD")
                .forbidden("ASSUME_NULL_TIER_IS_EXCLUDED");
        T13Scenarios.corpusWith(root, holder);

        String withAssumption = SliceTestFixtures.enumTierJson().replace(
                "\"assumptions\":[]", "\"assumptions\":[\"ASSUME_NULL_TIER_IS_EXCLUDED\"]");
        EvaluationReport report = T13Scenarios.run(
                root, "t13-metrics-assumption",
                new T13TestHost(SliceTestFixtures.StubModelPort.returning(withAssumption)));

        EvaluationReport.MetricValue rate = metric(report, MetricId.SILENT_CRITICAL_ASSUMPTION_RATE);
        // The Case is high-risk, so all five attempts carry the same forbidden-assumption expectation.
        assertThat(rate.numerator()).isEqualTo(5);
        assertThat(rate.denominator()).isEqualTo(5);
        assertThat(rate.value()).isEqualTo("1");
        assertThat(report.cases().get(0).attempts().get(0).evidence().forbiddenAssumptionsMatched()).isFalse();
    }

}