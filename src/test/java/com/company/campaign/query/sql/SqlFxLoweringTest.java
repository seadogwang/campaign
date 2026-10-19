package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3: an exact currency conversion is lowered to a compiler-owned typed {@code CASE} over the source
 * currency whose branches are the immutable binding's sorted rates plus the reporting-currency identity arm.
 *
 * <p>Every currency and rate is a typed bind parameter and never SQL text, no unpinned rate table is read,
 * changing any execution-relevant rate or pin changes the artifact hashes, and any incomplete, extra, stale,
 * future or contradictory binding fails closed with {@code SQL_FX_INVALID}.
 */
class SqlFxLoweringTest {

    private static final Instant RATE_TIMESTAMP = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void theRateIsLoweredToACompilerOwnedCaseAndNeverReadsARateTable() {
        CompiledSql compiled = compile(metricPlan(), SqlTestSupport.standardCatalog(), binding("7.20"));

        assertThat(compiled.sql())
                .contains("(CASE ")
                .contains(" WHEN ")
                .contains(" THEN ")
                .contains(" END)")
                .doesNotContain("fx_rates")
                .doesNotContain("snapshot_id")
                .doesNotContain("USD")
                .doesNotContain("7.2");
        assertThat(countCaseNodes(compiled.ast().where())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void everySourceAndTargetCurrencyAndRateIsATypedOrderedBindParameter() {
        CompiledSql compiled = compile(metricPlan(), catalogWithCurrencies("EUR", "USD"),
                FxRateBinding.publish("fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP,
                        Map.of("EUR", new BigDecimal("7.80"), "USD", new BigDecimal("7.20"))));

        List<SqlParameter> parameters = compiled.parameters();
        assertThat(parameters).contains(
                SqlParameter.currency("EUR"),
                SqlParameter.decimal(new BigDecimal("7.80")),
                SqlParameter.currency("USD"),
                SqlParameter.decimal(new BigDecimal("7.20")),
                SqlParameter.currency("CNY"),
                SqlParameter.decimal(BigDecimal.ONE));

        // The CASE arms are ordered: each sorted source currency then its exact rate, and finally the exact
        // reporting-currency identity arm. The types are exact — currency and decimal, never text.
        assertThat(parameters.indexOf(SqlParameter.currency("EUR")))
                .isLessThan(parameters.indexOf(SqlParameter.decimal(new BigDecimal("7.80"))));
        assertThat(parameters.indexOf(SqlParameter.decimal(new BigDecimal("7.80"))))
                .isLessThan(parameters.indexOf(SqlParameter.currency("USD")));
        assertThat(parameters.indexOf(SqlParameter.currency("USD")))
                .isLessThan(parameters.indexOf(SqlParameter.decimal(new BigDecimal("7.20"))));
        assertThat(parameters.indexOf(SqlParameter.decimal(new BigDecimal("7.20"))))
                .isLessThan(parameters.indexOf(SqlParameter.currency("CNY")));
        assertThat(parameters.indexOf(SqlParameter.currency("CNY")))
                .isLessThan(parameters.indexOf(SqlParameter.decimal(BigDecimal.ONE)));

        assertThat(compiled.sql())
                .doesNotContain("EUR")
                .doesNotContain("USD")
                .doesNotContain("CNY")
                .doesNotContain("7.8")
                .doesNotContain("7.2");
    }

    @Test
    void changingARateChangesParametersAndHashesWhileTheSqlStructureStaysDeterministic() {
        CompiledSql cheap = compile(metricPlan(), SqlTestSupport.standardCatalog(),
                SqlTestSupport.cnyFx("7.20"));
        CompiledSql dear = compile(metricPlan(), SqlTestSupport.standardCatalog(),
                SqlTestSupport.cnyFx("8.10"));

        assertThat(dear.sql()).isEqualTo(cheap.sql());
        assertThat(dear.parameters()).isNotEqualTo(cheap.parameters());
        assertThat(dear.parameters()).contains(SqlParameter.decimal(new BigDecimal("8.10")));
        assertThat(cheap.parameters()).contains(SqlParameter.decimal(new BigDecimal("7.20")));
        assertThat(dear.externalBindingHash()).isNotEqualTo(cheap.externalBindingHash());
        assertThat(dear.compiledHash()).isNotEqualTo(cheap.compiledHash());
    }

    @Test
    void incompleteOrExtraRateCoverageFailsClosed() {
        // The catalog declares EUR and USD but the binding covers only USD: incomplete coverage.
        assertFxInvalid(() -> compile(metricPlan(), catalogWithCurrencies("EUR", "USD"), binding("7.20")));

        // The catalog declares only USD but the binding also covers EUR: extra coverage.
        assertFxInvalid(() -> compile(metricPlan(), SqlTestSupport.standardCatalog(),
                FxRateBinding.publish("fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP,
                        Map.of("EUR", new BigDecimal("7.80"), "USD", new BigDecimal("7.20")))));
    }

    @Test
    void anExplicitTargetCurrencyRateFailsClosed() {
        // The reporting currency has exactly one exact rate — the identity arm — so an explicit CNY rate is a
        // contradictory binding rather than an ambiguous second arm.
        assertFxInvalid(() -> compile(metricPlan(), catalogWithCurrencies("CNY", "USD"),
                FxRateBinding.publish("fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP,
                        Map.of("USD", new BigDecimal("7.20"), "CNY", BigDecimal.ONE))));
    }

    @Test
    void aTargetCurrencyMismatchFailsClosed() {
        assertFxInvalid(() -> compile(metricPlan(), SqlTestSupport.standardCatalog(),
                FxRateBinding.publish("fx-snapshot-1", "1", "USD", RATE_TIMESTAMP,
                        Map.of("EUR", new BigDecimal("1.00")))));
    }

    @Test
    void aFutureRateTimestampFailsClosed() {
        assertFxInvalid(() -> compile(metricPlan(), SqlTestSupport.standardCatalog(),
                FxRateBinding.publish("fx-snapshot-1", "1", "CNY", Instant.parse("2026-10-02T00:00:00Z"),
                        Map.of("USD", new BigDecimal("7.20")))));
    }

    @Test
    void snapshotVersionTimeAndRatesAreAllCoveredByTheExternalBindingHash() {
        SemanticQueryPlan plan = metricPlan();
        StorageCatalog catalog = SqlTestSupport.standardCatalog();

        CompiledSql baseline = compile(plan, catalog, FxRateBinding.publish(
                "fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP, Map.of("USD", new BigDecimal("7.20"))));
        CompiledSql otherSnapshot = compile(plan, catalog, FxRateBinding.publish(
                "fx-snapshot-2", "1", "CNY", RATE_TIMESTAMP, Map.of("USD", new BigDecimal("7.20"))));
        CompiledSql otherVersion = compile(plan, catalog, FxRateBinding.publish(
                "fx-snapshot-1", "2", "CNY", RATE_TIMESTAMP, Map.of("USD", new BigDecimal("7.20"))));
        CompiledSql otherTime = compile(plan, catalog, FxRateBinding.publish(
                "fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP.minusSeconds(3_600),
                Map.of("USD", new BigDecimal("7.20"))));
        CompiledSql otherRate = compile(plan, catalog, FxRateBinding.publish(
                "fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP, Map.of("USD", new BigDecimal("8.10"))));

        for (CompiledSql variant : List.of(otherSnapshot, otherVersion, otherTime, otherRate)) {
            assertThat(variant.externalBindingHash()).isNotEqualTo(baseline.externalBindingHash());
            assertThat(variant.compiledHash()).isNotEqualTo(baseline.compiledHash());
            // Only the hash-covered pin changes; the deterministic SQL structure stays byte-identical.
            assertThat(variant.sql()).isEqualTo(baseline.sql());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assertFxInvalid(org.junit.jupiter.api.function.Executable action) {
        SqlException failure = SqlTestSupport.sqlFailure(action);
        assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_FX_INVALID);
        assertThat(failure.details()).containsOnlyKeys("invariant");
    }

    private static CompiledSql compile(SemanticQueryPlan plan, StorageCatalog catalog, FxRateBinding fx) {
        return new SqlCompiler().compile(plan, catalog, SqlCompilePolicy.standard(), fx);
    }

    private static FxRateBinding binding(String usdRate) {
        return FxRateBinding.publish("fx-snapshot-1", "1", "CNY", RATE_TIMESTAMP,
                Map.of("USD", new BigDecimal(usdRate)));
    }

    private static StorageCatalog catalogWithCurrencies(String... currencies) {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        StorageCatalog.FxStrategy scoped = new StorageCatalog.FxStrategy(
                base.fxStrategy().rateTable(), base.fxStrategy().currencyColumn(),
                base.fxStrategy().valueColumn(), base.fxStrategy().snapshotColumn(), List.of(currencies));
        return StorageCatalog.publish(base.catalogVersion(), base.entities(), base.attributes(), base.metrics(),
                base.relations(), base.dataScope(), scoped, base.refundStorage());
    }

    private static int countCaseNodes(SqlExpression node) {
        return switch (node) {
            case SqlExpression.Case caseExpression ->
                    1 + countCaseNodes(caseExpression.operand())
                            + caseExpression.whens().stream().mapToInt(SqlFxLoweringTest::countCaseNodes).sum()
                            + caseExpression.thens().stream().mapToInt(SqlFxLoweringTest::countCaseNodes).sum()
                            + (caseExpression.fallback() == null ? 0 : countCaseNodes(caseExpression.fallback()));
            case SqlExpression.And and -> and.operands().stream().mapToInt(SqlFxLoweringTest::countCaseNodes).sum();
            case SqlExpression.Or or -> or.operands().stream().mapToInt(SqlFxLoweringTest::countCaseNodes).sum();
            case SqlExpression.Not not -> countCaseNodes(not.operand());
            case SqlExpression.IsNull isNull -> countCaseNodes(isNull.operand());
            case SqlExpression.IsNotNull isNotNull -> countCaseNodes(isNotNull.operand());
            case SqlExpression.Comparison comparison ->
                    countCaseNodes(comparison.left()) + countCaseNodes(comparison.right());
            case SqlExpression.InList inList ->
                    countCaseNodes(inList.value())
                            + inList.options().stream().mapToInt(SqlFxLoweringTest::countCaseNodes).sum();
            case SqlExpression.Between between ->
                    countCaseNodes(between.value()) + countCaseNodes(between.lower()) + countCaseNodes(between.upper());
            case SqlExpression.Like like -> countCaseNodes(like.value()) + countCaseNodes(like.pattern());
            case SqlExpression.Coalesce coalesce ->
                    coalesce.operands().stream().mapToInt(SqlFxLoweringTest::countCaseNodes).sum();
            case SqlExpression.Multiply multiply ->
                    countCaseNodes(multiply.left()) + countCaseNodes(multiply.right());
            case SqlExpression.Add add ->
                    add.operands().stream().mapToInt(SqlFxLoweringTest::countCaseNodes).sum();
            case SqlExpression.Subtract subtract ->
                    countCaseNodes(subtract.left()) + countCaseNodes(subtract.right());
            case SqlExpression.Divide divide ->
                    countCaseNodes(divide.left()) + countCaseNodes(divide.right())
                            + countCaseNodes(divide.zero());
            case SqlExpression.CastDecimal cast -> countCaseNodes(cast.operand());
            case SqlExpression.AddDays addDays ->
                    countCaseNodes(addDays.temporal()) + countCaseNodes(addDays.days());
            case SqlExpression.Aggregate aggregate ->
                    aggregate.argument() == null ? 0 : countCaseNodes(aggregate.argument());
            case SqlExpression.ScalarSubquery ignored -> 0;
            case SqlExpression.Exists ignored -> 0;
            case SqlExpression.BindParameter ignored -> 0;
            case SqlExpression.Column ignored -> 0;
        };
    }

    private static SemanticQueryPlan metricPlan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-sum", "order.net_paid_amount", SegmentValidationFixtures.rolling(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1000.00", "CNY"))));
    }
}
