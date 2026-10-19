package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.cnyFx;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.enhancedMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.enhancedSnapshot;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.order;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.payload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.plan;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMembers;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardScope;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 6 and 7: COUNT-over-empty, aggregate filters, refunds, every aggregate function and every
 * empty-set behavior exactly, and rolling/fixed/lifetime plus inclusive/exclusive edges taken only from the
 * T08 plan bindings.
 */
class FixtureExecutorMetricTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void countOverEmptyIsZeroAndCountsOnlyRelatedRows() {
        assertThat(run(metric("order.count", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.integerValue(0))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-003", "m-005"));

        assertThat(run(metric("order.count", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.integerValue(0))))
                .isEqualTo(MemberIdSet.of("m-004", "m-006"));
    }

    @Test
    void sumUsesExactDecimalsAndAZeroEmptySet() {
        assertThat(run(metric("order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("157.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-001"));
        assertThat(run(metric("order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("0.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-004", "m-006"));
    }

    @Test
    void averageMinAndMaxApplyTheirOwnEmptySetBehavior() {
        assertThat(run(metric("order.avg_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.moneyValue("50.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-003"));
        // NULL over an empty set is not a number, so an explicit lower bound cannot match m-004 or m-006.
        assertThat(run(metric("order.avg_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.moneyValue("-1.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-003", "m-005"));

        // order.min_amount supports LIFETIME only, so its definition declares no event-time field and the
        // executor must not invent order.paid_at to filter by the watermark: m-002's 9.00 order o-007 (paid
        // after the watermark) is therefore included. This is why it differs from the separately tested,
        // event-time-aware order.net_paid_amount (aLifetimeMetricWindowIsBoundedByTheDataWatermark).
        assertThat(run(metric("order.min_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.LESS_THAN, SegmentValidationFixtures.moneyValue("10.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002"));
        assertThat(run(metric("order.max_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.moneyValue("1000.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-003"));
    }

    @Test
    void aStringAggregateUsesTheMetricStringSemantics() {
        assertThat(run(metric("order.latest_channel", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.stringValue("ONLINE"))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-005"));
        assertThat(run(metric("order.latest_channel", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.stringValue("STORE"))))
                .isEqualTo(MemberIdSet.of("m-003"));
    }

    @Test
    void aRollingMetricWindowUsesTheResolvedPlanBoundaries() {
        assertThat(run(metric("order.net_paid_amount", SegmentValidationFixtures.rolling(1, ONE_DAY),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("107.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-001"));
    }

    @Test
    void aFixedMetricWindowUsesTheDeclaredInstants() {
        assertThat(run(metric("order.net_paid_amount",
                SegmentValidationFixtures.fixedWindow("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("25.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-005"));
    }

    @Test
    void aLifetimeMetricWindowIsBoundedByTheDataWatermark() {
        // o-007 for m-002 is paid exactly after the declared watermark, so LIFETIME excludes it.
        assertThat(run(metric("order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("209.00", "CNY"))))
                .isEqualTo(MemberIdSet.of());
        assertThat(run(metric("order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("200.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-002"));
    }

    @Test
    void aLifetimeMetricExcludesAnEventExactlyAtTheDataWatermark() {
        FixtureDataset dataset = load(
                repositoryRoot,
                payload(
                        List.of(standardMembers().get(0)),
                        List.of(order(
                                "o-watermark", "m-001", "50.00", "CNY",
                                "2026-10-01T01:55:00Z", "0.00", "PAID", "ONLINE")),
                        List.of(),
                        List.of()),
                MemberIdSet.of("m-001"));
        SemanticQueryPlan plan = plan(
                SegmentValidationFixtures.snapshot(),
                metric(
                        "order.net_paid_amount",
                        SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.moneyValue("0.00", "CNY")));

        assertThat(EXECUTOR.execute(plan, dataset, standardMapping(), standardScope(), cnyFx("7.00")).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001"));
    }

    @Test
    void anInclusiveEndBoundaryComesOnlyFromTheT08PlanBinding() {
        MetadataSnapshot snapshot = QueryPlanFixtures.inclusiveBothSnapshot();
        TimeWindow rolling = SegmentValidationFixtures.rolling(1, ONE_DAY);

        // Under INCLUSIVE_BOTH the order paid exactly at the resolved end instant is included...
        assertThat(runAgainst(snapshot, standardMapping(), metric("order.inclusive_amount", rolling,
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("9.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-002"));
        // ...while the other orders inside the same window are unaffected.
        assertThat(runAgainst(snapshot, standardMapping(), metric("order.inclusive_amount", rolling,
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("107.00", "CNY"))))
                .isEqualTo(MemberIdSet.of("m-001"));
    }

    @Test
    void refundTreatmentSubtractsRefundsExactly() {
        MetadataSnapshot snapshot = enhancedSnapshot();
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        SemanticQueryPlan plan = plan(snapshot, metric("order.refunded_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("147.00", "CNY")));

        assertThat(EXECUTOR.execute(plan, dataset, enhancedMapping(), standardScope(), cnyFx("7.00")).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001"));
    }

    @Test
    void anImmutableMetadataFilterIsAppliedBeforeAggregation() {
        MetadataSnapshot snapshot = enhancedSnapshot();
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        SemanticQueryPlan plan = plan(snapshot, metric("order.filtered_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.moneyValue("107.00", "CNY")));

        // The COMPLETED order o-002 is filtered out, so only the two PAID orders of m-001 remain.
        assertThat(EXECUTOR.execute(plan, dataset, enhancedMapping(), standardScope(), cnyFx("7.00")).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001"));
    }

    @Test
    void countDistinctCountsDistinctValuesAndZeroOverAnEmptySet() {
        MetadataSnapshot snapshot = enhancedSnapshot();
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        SemanticQueryPlan distinct = plan(snapshot, metric("order.distinct_channel", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.integerValue(1)));
        assertThat(EXECUTOR.execute(distinct, dataset, enhancedMapping(), standardScope(), cnyFx("7.00")).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001"));

        SemanticQueryPlan empty = plan(snapshot, metric("order.distinct_channel", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL, SegmentValidationFixtures.integerValue(0)));
        assertThat(EXECUTOR.execute(empty, dataset, enhancedMapping(), standardScope(), cnyFx("7.00")).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-004", "m-006"));
    }

    @Test
    void aMetricAndAnAttributeInsideOneBooleanGroupKeepTheirOwnSemantics() {
        Predicate predicate = SegmentValidationFixtures.all("n-and", List.of(
                metric("order.count", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.integerValue(0)),
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));

        assertThat(run(predicate)).isEqualTo(MemberIdSet.of("m-001", "m-003"));
    }

    private static final WindowUnit ONE_DAY = WindowUnit.DAY;

    private MemberIdSet run(Predicate predicate) {
        return runAgainst(SegmentValidationFixtures.snapshot(), standardMapping(), predicate);
    }

    private MemberIdSet runAgainst(MetadataSnapshot snapshot, FixtureMapping mapping, Predicate predicate) {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        return EXECUTOR.execute(plan(snapshot, predicate), dataset, mapping, standardScope(), cnyFx("7.00"))
                .memberIdSet();
    }

    private static Predicate metric(
            String metricId, TimeWindow window, ComparisonOperator operator,
            com.company.campaign.segment.dsl.value.ScalarValue value) {
        return SegmentValidationFixtures.metric(
                "n-" + metricId.replace('.', '-'), metricId, window, operator,
                SegmentValidationFixtures.value(value));
    }
}
