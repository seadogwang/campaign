package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 16: an unsupported currency, FX, string or storage semantic fails closed with a stable code
 * rather than being approximated, while an exactly representable single-currency comparison still compiles.
 */
class SqlFailClosedTest {

    @Test
    void aConversionWithoutAnFxStrategyFailsClosed() {
        assertUnsupported(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                balancePlan(), SqlTestSupport.catalogWithoutFx(), SqlTestSupport.cnyFx())));
    }

    @Test
    void aConversionWithoutABindingFailsClosed() {
        assertCode(SqlErrorCodes.SQL_FX_INVALID, SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                balancePlan(), SqlTestSupport.standardCatalog(), null)));
    }

    @Test
    void aBindingForAnotherTargetCurrencyFailsClosed() {
        FxRateBinding wrongTarget = FxRateBinding.publish(
                "fx-other", "1", "USD", SqlTestSupport.RATE_TIMESTAMP,
                Map.of("EUR", new BigDecimal("1.00")));
        assertCode(SqlErrorCodes.SQL_FX_INVALID, SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                balancePlan(), SqlTestSupport.standardCatalog(), wrongTarget)));
    }

    @Test
    void aBindingObservedAfterTheEvaluationInstantFailsClosed() {
        FxRateBinding late = FxRateBinding.publish(
                "fx-late", "1", "CNY", Instant.parse("2026-10-02T00:00:00Z"),
                Map.of("USD", new BigDecimal("7.20")));
        assertCode(SqlErrorCodes.SQL_FX_INVALID, SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                balancePlan(), SqlTestSupport.standardCatalog(), late)));
    }

    @Test
    void aBindingThatDoesNotCoverThePinnedCurrencyVocabularyFailsClosed() {
        FxRateBinding wrongCoverage = FxRateBinding.publish(
                "fx-eur", "1", "CNY", SqlTestSupport.RATE_TIMESTAMP,
                Map.of("EUR", new BigDecimal("7.80")));
        assertCode(SqlErrorCodes.SQL_FX_INVALID, SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(
                balancePlan(), SqlTestSupport.standardCatalog(), wrongCoverage)));
    }

    @Test
    void aCaseInsensitiveOrNormalizingStringFailsClosed() {
        SemanticQueryPlan plan = SqlTestSupport.plan(SegmentValidationFixtures.attribute(
                "n-name", "member.name", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("Alice"))));
        assertUnsupported(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(plan)));
    }

    @Test
    void anUnsupportedMonetaryOperatorFailsClosed() {
        SemanticQueryPlan plan = SqlTestSupport.plan(SegmentValidationFixtures.attribute(
                "n-balance", "member.balance", ComparisonOperator.IN,
                SegmentValidationFixtures.list(SegmentValidationFixtures.moneyValue("100.00", "CNY"))));
        assertUnsupported(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(plan)));
    }

    @Test
    void anUnsupportedMetricNullPredicateFailsClosed() {
        SemanticQueryPlan plan = SqlTestSupport.plan(new MetricPredicate(
                "n-avg-null", "order.avg_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.IS_NULL, ConditionValue.none()));
        assertUnsupported(SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(plan)));
    }

    @Test
    void anUnsupportedMetricAggregateInStorageFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.MetricStorage> metrics = new ArrayList<>();
        for (StorageCatalog.MetricStorage metric : base.metrics()) {
            metrics.add(metric.definitionId().equals("order.distinct_channel")
                    ? new StorageCatalog.MetricStorage(
                            metric.definitionId(), metric.subject(), metric.entitySchemaVersion(),
                            metric.resultType(), metric.relationId(), metric.inputField(), metric.inputColumn(),
                            metric.currencyField(), metric.currencyColumn(), metric.eventTimeField(),
                            metric.eventTimeColumn(), metric.filterColumns(),
                            List.of(AggregateFunction.SUM), metric.supportedEmptySetBehaviors(),
                            metric.refundSupported())
                    : metric);
        }
        StorageCatalog variant = StorageCatalog.publish(
                base.catalogVersion(), base.entities(), base.attributes(), metrics, base.relations(),
                base.dataScope(), base.fxStrategy(), base.refundStorage());

        SemanticQueryPlan plan = SqlTestSupport.plan(SqlTestSupport.enhancedSnapshot(),
                SegmentValidationFixtures.metric(
                        "n-distinct", "order.distinct_channel",
                        SegmentValidationFixtures.rolling(30,
                                com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1))));
        assertUnsupported(SqlTestSupport.sqlFailure(
                () -> SqlTestSupport.compile(plan, variant, SqlTestSupport.cnyFx())));
    }

    @Test
    void refundSubtractionWithoutRefundStorageFailsClosed() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        StorageCatalog variant = StorageCatalog.publish(
                base.catalogVersion(), base.entities(), base.attributes(), base.metrics(), base.relations(),
                base.dataScope(), base.fxStrategy(), null);

        SemanticQueryPlan plan = SqlTestSupport.plan(SqlTestSupport.enhancedSnapshot(),
                SegmentValidationFixtures.metric(
                        "n-refund", "order.refunded_amount",
                        SegmentValidationFixtures.rolling(30,
                                com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY"))));
        assertUnsupported(SqlTestSupport.sqlFailure(
                () -> SqlTestSupport.compile(plan, variant, SqlTestSupport.cnyFx())));
    }

    @Test
    void aSingleCurrencyComparisonCompilesWithAnExactSameCurrencyPredicate() {
        SemanticQueryPlan plan = SqlTestSupport.plan(QueryPlanFixtures.singleCurrencySnapshot(),
                SegmentValidationFixtures.attribute(
                        "n-single", "member.single_balance", ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY"))));
        CompiledSql compiled = SqlTestSupport.compile(plan, SqlTestSupport.standardCatalog(), null);

        assertThat(compiled.sql()).contains("\"single_balance_currency\" = ?");
        assertThat(compiled.parameters()).contains(SqlParameter.currency("CNY"));
    }

    @Test
    void aCompilePolicyLimitFailsClosed() {
        SqlCompilePolicy narrowParameters = SqlCompilePolicy.publish(1_000, 3, 32);
        assertCode(SqlErrorCodes.SQL_LIMIT_EXCEEDED, SqlTestSupport.sqlFailure(
                () -> new SqlCompiler().compile(
                        SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD")),
                        SqlTestSupport.standardCatalog(), narrowParameters, SqlTestSupport.cnyFx())));

        SqlCompilePolicy narrowList = SqlCompilePolicy.publish(1, 10_000, 32);
        SemanticQueryPlan inPlan = SqlTestSupport.plan(SegmentValidationFixtures.attribute(
                "n-tier", "member.tier", ComparisonOperator.IN,
                SegmentValidationFixtures.list(
                        SegmentValidationFixtures.enumValue("GOLD"),
                        SegmentValidationFixtures.enumValue("SILVER"))));
        assertCode(SqlErrorCodes.SQL_LIMIT_EXCEEDED, SqlTestSupport.sqlFailure(
                () -> new SqlCompiler().compile(
                        inPlan, SqlTestSupport.standardCatalog(), narrowList, SqlTestSupport.cnyFx())));
    }

    @Test
    void aLikePatternCrossingTheParameterLimitFailsClosedThroughTheCountedPath() {
        // Five compiler-owned predicates bind before the user predicate: the root tenant and program, then the
        // DataScope scope id, tenant and program. The LIKE pattern is therefore the sixth and is the parameter
        // that crosses a five-parameter policy through the common counted parameter(...) path.
        SemanticQueryPlan plan = SqlTestSupport.plan(SqlTestSupport.identityStringSnapshot(),
                SegmentValidationFixtures.attribute(
                        "n-code", "member.code", ComparisonOperator.CONTAINS,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("A"))));

        SqlException failure = SqlTestSupport.sqlFailure(() -> new SqlCompiler().compile(
                plan, SqlTestSupport.standardCatalog(), SqlCompilePolicy.publish(1_000, 5, 32),
                SqlTestSupport.cnyFx()));

        assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_LIMIT_EXCEEDED);
        assertThat(failure.details()).containsEntry("invariant", "parameters");

        // Exactly one more parameter is permitted, the LIKE pattern is the sixth and last of them, and only
        // then is an artifact published — so the failure above crossed on the LIKE parameter itself rather than
        // on an earlier predicate or on a parameter created outside the bounded path.
        CompiledSql permitted = new SqlCompiler().compile(
                plan, SqlTestSupport.standardCatalog(), SqlCompilePolicy.publish(1_000, 6, 32),
                SqlTestSupport.cnyFx());
        assertThat(permitted.parameters()).hasSize(6);
        assertThat(permitted.parameters().get(5)).isEqualTo(SqlParameter.text("%A%"));
        assertThat(permitted.sql()).contains("LIKE ? ESCAPE '!'");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertUnsupported(SqlException failure) {
        assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_UNSUPPORTED_SEMANTIC);
    }

    private static void assertCode(String code, SqlException failure) {
        assertThat(failure.code()).isEqualTo(code);
    }

    private static SemanticQueryPlan balancePlan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.attribute(
                "n-balance", "member.balance", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("100.00", "CNY"))));
    }
}