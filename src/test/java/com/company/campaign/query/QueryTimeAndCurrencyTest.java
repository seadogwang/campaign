package com.company.campaign.query;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.query.plan.CurrencyBinding;
import com.company.campaign.query.plan.ResolvedWindow;
import com.company.campaign.query.plan.ResolvedWindowBinding;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 9-11: deterministic concrete time boundaries, explicit boundary/asOf/timezone/watermark pins
 * and the three distinct currency actions with a trusted reporting currency and no invented FX rate.
 */
class QueryTimeAndCurrencyTest {

    private static CurrencyBinding binding(SemanticQueryPlan plan, String definitionId) {
        return plan.currencyBindings().stream()
                .filter(binding -> binding.definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow();
    }

    private static ResolvedWindowBinding.MetricWindow onlyMetricWindow(SemanticQueryPlan plan) {
        return plan.windowBindings().stream()
                .filter(ResolvedWindowBinding.MetricWindow.class::isInstance)
                .map(ResolvedWindowBinding.MetricWindow.class::cast)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rollingWeekAndSevenDaysProduceIdenticalConcreteBoundariesAndHash() {
        SemanticQueryPlan oneWeek = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.rolling(1, WindowUnit.WEEK),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        SemanticQueryPlan sevenDays = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.rolling(7, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));

        assertThat(oneWeek.planHash()).isEqualTo(sevenDays.planHash());
        ResolvedWindow.Rolling rolling = (ResolvedWindow.Rolling) onlyMetricWindow(oneWeek).resolvedWindow();
        assertThat(rolling.end()).isEqualTo(QueryPlanFixtures.AS_OF);
        assertThat(rolling.start()).isEqualTo(QueryPlanFixtures.AS_OF.minusSeconds(7L * 24L * 3600L));
    }

    @Test
    void fixedOffsetsCanonicalizedToTheSameInstantsStayEqual() {
        SemanticQueryPlan offsetForm = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount",
                SegmentValidationFixtures.fixedWindow("2026-01-01T00:00:00+08:00", "2026-01-02T00:00:00+08:00"),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        SemanticQueryPlan utcForm = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount",
                SegmentValidationFixtures.fixedWindow("2025-12-31T16:00:00Z", "2026-01-01T16:00:00Z"),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));

        assertThat(offsetForm.planHash()).isEqualTo(utcForm.planHash());
        ResolvedWindow.Fixed fixed = (ResolvedWindow.Fixed) onlyMetricWindow(utcForm).resolvedWindow();
        assertThat(fixed.start()).isEqualTo(Instant.parse("2025-12-31T16:00:00Z"));
        assertThat(fixed.end()).isEqualTo(Instant.parse("2026-01-01T16:00:00Z"));
    }

    @Test
    void lifetimeInventsNoStartAndKeepsAsOfAndWatermark() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-count", "order.count", SegmentValidationFixtures.lifetime(), ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))));

        ResolvedWindowBinding.MetricWindow window = onlyMetricWindow(plan);
        assertThat(window.resolvedWindow()).isInstanceOf(ResolvedWindow.Lifetime.class);
        ResolvedWindow.Lifetime lifetime = (ResolvedWindow.Lifetime) window.resolvedWindow();
        assertThat(lifetime.evaluationAsOf()).isEqualTo(QueryPlanFixtures.AS_OF);
        assertThat(lifetime.dataWatermark()).isEqualTo(QueryPlanFixtures.WATERMARK);
        assertThat(window.includeStart()).isFalse();
        assertThat(window.includeEnd()).isFalse();
        assertThat(window.boundarySemantics()).isEqualTo(WindowBoundarySemantics.NOT_APPLICABLE);
    }

    @Test
    void boundaryInclusivityComesFromThePinnedMetadataSemantics() {
        SemanticQueryPlan exclusive = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        ResolvedWindowBinding.MetricWindow exclusiveWindow = onlyMetricWindow(exclusive);
        assertThat(exclusiveWindow.boundarySemantics())
                .isEqualTo(WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END);
        assertThat(exclusiveWindow.includeStart()).isTrue();
        assertThat(exclusiveWindow.includeEnd()).isFalse();

        var snapshot = QueryPlanFixtures.inclusiveBothSnapshot();
        var document = QueryPlanFixtures.document(snapshot, SegmentValidationFixtures.metric(
                "n-i", "order.inclusive_amount", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        SemanticQueryPlan inclusive = QueryPlanFixtures.plan(
                document, snapshot, SegmentValidationFixtures.context());
        ResolvedWindowBinding.MetricWindow inclusiveWindow = onlyMetricWindow(inclusive);
        assertThat(inclusiveWindow.boundarySemantics()).isEqualTo(WindowBoundarySemantics.INCLUSIVE_BOTH);
        assertThat(inclusiveWindow.includeStart()).isTrue();
        assertThat(inclusiveWindow.includeEnd()).isTrue();
    }

    @Test
    void asOfTimezoneAndWatermarkAreExplicitAndHashSensitive() {
        SemanticQueryPlan base = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        TrustedExecutionContext shanghai = SegmentValidationFixtures.context();
        TrustedExecutionContext berlin = QueryPlanFixtures.context(
                "tenant-001", "program-001", "scope-008", "membership-1", "user-001",
                QueryPlanFixtures.AS_OF.plusSeconds(3600), QueryPlanFixtures.WATERMARK,
                ZoneId.of("Europe/Berlin"), "CNY", com.company.campaign.metadata.testsupport.MetadataFixtures.allPermissions());

        SemanticQueryPlan shifted = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(), berlin);
        SemanticQueryPlan same = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(), shanghai);

        assertThat(base.trustedContext().asOf()).isEqualTo(QueryPlanFixtures.AS_OF);
        assertThat(base.trustedContext().timezone()).isEqualTo("Asia/Shanghai");
        assertThat(base.trustedContext().dataWatermark()).isEqualTo(QueryPlanFixtures.WATERMARK);
        assertThat(shifted.planHash()).isNotEqualTo(base.planHash());
        assertThat(same.planHash()).isEqualTo(base.planHash());
    }

    @Test
    void theThreeCurrencyActionsAreDistinctAndTheTargetIsTrustedContextDerived() {
        SemanticQueryPlan convert = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        CurrencyBinding converted = binding(convert, "order.net_paid_amount");
        assertThat(converted.action())
                .isEqualTo(CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY);
        assertThat(converted.targetCurrency()).isEqualTo("CNY");
        assertThat(converted.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.DEFERRED);
        assertThat(binding(convert, "member.tier").action()).isEqualTo(CurrencyBinding.Action.NO_CONVERSION);

        SemanticQueryPlan none = QueryPlanFixtures.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD"));
        CurrencyBinding unchanged = binding(none, "member.tier");
        assertThat(unchanged.action()).isEqualTo(CurrencyBinding.Action.NO_CONVERSION);
        assertThat(unchanged.targetCurrency()).isNull();
        assertThat(unchanged.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.NOT_APPLICABLE);

        var snapshot = QueryPlanFixtures.singleCurrencySnapshot();
        var document = QueryPlanFixtures.document(snapshot, SegmentValidationFixtures.attribute(
                "n-money", "member.single_balance", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        SemanticQueryPlan single = QueryPlanFixtures.plan(document, snapshot, SegmentValidationFixtures.context());
        CurrencyBinding singleBinding = binding(single, "member.single_balance");
        assertThat(singleBinding.action()).isEqualTo(CurrencyBinding.Action.SINGLE_CURRENCY_ONLY);
        assertThat(singleBinding.targetCurrency()).isNull();
        assertThat(singleBinding.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.NOT_APPLICABLE);

        assertThat(List.of(converted.action(), unchanged.action(), singleBinding.action()))
                .containsExactly(
                        CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY,
                        CurrencyBinding.Action.NO_CONVERSION,
                        CurrencyBinding.Action.SINGLE_CURRENCY_ONLY);
    }

    @Test
    void theConversionTargetIsTheTrustedReportingCurrencyNotTheOperandCurrency() {
        SemanticQueryPlan cny = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        TrustedExecutionContext usdContext = QueryPlanFixtures.context(
                "tenant-001", "program-001", "scope-008", "membership-1", "user-001",
                QueryPlanFixtures.AS_OF, QueryPlanFixtures.WATERMARK, ZoneId.of("Asia/Shanghai"), "USD",
                com.company.campaign.metadata.testsupport.MetadataFixtures.allPermissions());

        SemanticQueryPlan usd = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(), usdContext);

        assertThat(binding(cny, "order.net_paid_amount").targetCurrency()).isEqualTo("CNY");
        assertThat(binding(usd, "order.net_paid_amount").targetCurrency()).isEqualTo("USD");
        assertThat(binding(usd, "order.net_paid_amount").rateResolution())
                .isEqualTo(CurrencyBinding.RateResolution.DEFERRED);
        assertThat(usd.planHash()).isNotEqualTo(cny.planHash());
    }
}