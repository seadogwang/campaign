package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 5–10: exact grouping, EXISTS boundaries, NULL structure, COUNT-over-empty, aggregates,
 * empty-set behavior, filters, refunds and resolved window boundaries.
 */
class SqlCompileSemanticsTest {

    private static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");

    // ------------------------------------------------------------------ 5. grouping

    @Test
    void aConjunctionOfADisjunctionStaysDistinctFromADisjunctionOfAConjunction() {
        CompiledSql left = SqlTestSupport.compile(SqlTestSupport.plan(and(any(tierGold(), ageAboveTen(), flagTrue()))));
        CompiledSql right = SqlTestSupport.compile(SqlTestSupport.plan(or(and(tierGold(), ageAboveTen()), flagTrue())));

        assertThat(userPredicate(left)).isInstanceOf(SqlExpression.And.class);
        assertThat(((SqlExpression.And) userPredicate(left)).operands())
                .anyMatch(operand -> operand instanceof SqlExpression.Or);
        assertThat(userPredicate(right)).isInstanceOf(SqlExpression.Or.class);
        assertThat(((SqlExpression.Or) userPredicate(right)).operands())
                .anyMatch(operand -> operand instanceof SqlExpression.And);

        assertThat(left.sql()).isNotEqualTo(right.sql());
        assertThat(left.ast()).isNotEqualTo(right.ast());
        assertThat(left.compiledHash()).isNotEqualTo(right.compiledHash());
    }

    // ------------------------------------------------------------------ 6. EXISTS boundaries

    @Test
    void oneExistsWithTwoPredicatesStaysOneSubqueryAndTwoExistsStayIndependent() {
        Predicate sameRow = and(channelIsOnline(), orderAmountAbove());
        SemanticQueryPlan one = SqlTestSupport.plan(
                SegmentValidationFixtures.exists("n-one", "member.orders", SegmentValidationFixtures.lifetime(),
                        sameRow));
        SemanticQueryPlan two = SqlTestSupport.plan(and(
                SegmentValidationFixtures.exists("n-a", "member.orders", SegmentValidationFixtures.lifetime(),
                        channelIsOnline()),
                SegmentValidationFixtures.exists("n-b", "member.orders", SegmentValidationFixtures.lifetime(),
                        orderAmountAbove())));

        CompiledSql oneCompiled = SqlTestSupport.compile(one);
        CompiledSql twoCompiled = SqlTestSupport.compile(two);

        assertThat(countExists(userPredicate(oneCompiled))).isEqualTo(1);
        assertThat(countExists(userPredicate(twoCompiled))).isEqualTo(2);

        SqlExpression.Exists exists = (SqlExpression.Exists) userPredicate(oneCompiled);
        assertThat(exists.subquery().where()).isInstanceOf(SqlExpression.And.class);
        List<SqlExpression> predicates = ((SqlExpression.And) exists.subquery().where()).operands();
        assertThat(predicates).anyMatch(operand -> operand instanceof SqlExpression.And);
        assertThat(predicates).noneMatch(operand -> operand instanceof SqlExpression.Exists);
        assertThat(oneCompiled.sql()).isNotEqualTo(twoCompiled.sql());
        assertThat(oneCompiled.compiledHash()).isNotEqualTo(twoCompiled.compiledHash());
    }

    // ------------------------------------------------------------------ 7. NULL structure

    @Test
    void notNotEqualNotInIsNullAndNullInclusiveStructureStayDistinct() {
        CompiledSql notEqual = SqlTestSupport.compile(SqlTestSupport.plan(
                attribute("member.tier", ComparisonOperator.NOT_EQUAL, SegmentValidationFixtures.enumValue("GOLD"))));
        CompiledSql notIn = SqlTestSupport.compile(SqlTestSupport.plan(attributeCondition(
                "member.tier", ComparisonOperator.NOT_IN,
                SegmentValidationFixtures.list(
                        SegmentValidationFixtures.enumValue("GOLD"),
                        SegmentValidationFixtures.enumValue("SILVER")))));
        CompiledSql isNull = SqlTestSupport.compile(
                SqlTestSupport.plan(QueryPlanFixtures.attributeNoValue("n-null", "member.tier",
                        ComparisonOperator.IS_NULL)));
        CompiledSql negated = SqlTestSupport.compile(SqlTestSupport.plan(
                SegmentValidationFixtures.not("n-not", tierGold())));
        CompiledSql explicitNullBranch = SqlTestSupport.compile(SqlTestSupport.plan(or(
                attribute("member.tier", ComparisonOperator.NOT_EQUAL, SegmentValidationFixtures.enumValue("GOLD")),
                QueryPlanFixtures.attributeNoValue("n-null", "member.tier", ComparisonOperator.IS_NULL))));

        assertThat(userPredicate(notEqual)).isInstanceOf(SqlExpression.Comparison.class);
        assertThat(((SqlExpression.Comparison) userPredicate(notEqual)).operator())
                .isEqualTo(SqlComparisonOperator.NOT_EQUAL);
        assertThat(userPredicate(notIn)).isInstanceOf(SqlExpression.InList.class);
        assertThat(((SqlExpression.InList) userPredicate(notIn)).negated()).isTrue();
        assertThat(userPredicate(isNull)).isInstanceOf(SqlExpression.IsNull.class);
        assertThat(userPredicate(negated)).isInstanceOf(SqlExpression.Not.class);
        assertThat(userPredicate(explicitNullBranch)).isInstanceOf(SqlExpression.Or.class);

        assertThat(List.of(
                        notEqual.compiledHash(), notIn.compiledHash(), isNull.compiledHash(),
                        negated.compiledHash(), explicitNullBranch.compiledHash()))
                .doesNotHaveDuplicates();

        assertThat(notEqual.sql()).contains("<>");
        assertThat(notIn.sql()).contains("NOT IN (");
        assertThat(isNull.sql()).contains(" IS NULL)");
        assertThat(negated.sql()).contains("(NOT (");
        assertThat(explicitNullBranch.sql()).contains(" OR ");
    }

    // ------------------------------------------------------------------ 8. COUNT over empty

    @Test
    void nonExistenceCountThresholdUsesACorrelatedScalarAggregateAndNeverAnInnerJoin() {
        CompiledSql compiled = SqlTestSupport.compile(countPlan());

        assertThat(compiled.sql()).contains("(SELECT COUNT(*) FROM \"public\".\"orders\"");
        assertThat(compiled.sql()).doesNotContain("JOIN").doesNotContain("GROUP BY");

        SqlExpression user = userPredicate(compiled);
        assertThat(user).isInstanceOf(SqlExpression.Comparison.class);
        SqlExpression left = ((SqlExpression.Comparison) user).left();
        assertThat(left).isInstanceOf(SqlExpression.ScalarSubquery.class);
        SqlExpression projected = ((SqlExpression.ScalarSubquery) left).subquery().projection();
        assertThat(projected).isInstanceOf(SqlExpression.Aggregate.class);
        assertThat(((SqlExpression.Aggregate) projected).function())
                .isEqualTo(com.company.campaign.metadata.domain.AggregateFunction.COUNT);
    }

    @Test
    void existenceEquivalentCountsUseExistsAndNotExists() {
        CompiledSql present = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-present", "order.count", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0)))));
        CompiledSql absent = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-absent", "order.count", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0)))));

        assertThat(userPredicate(present)).isInstanceOf(SqlExpression.Exists.class);
        assertThat(present.sql()).contains("EXISTS (SELECT").doesNotContain("COUNT(*)");
        assertThat(userPredicate(absent)).isInstanceOf(SqlExpression.Not.class);
        assertThat(((SqlExpression.Not) userPredicate(absent)).operand())
                .isInstanceOf(SqlExpression.Exists.class);
        assertThat(absent.sql()).contains("NOT EXISTS (SELECT").doesNotContain("COUNT(*)");
        assertThat(present.parameters()).doesNotContain(SqlParameter.integer(0));
        assertThat(absent.parameters()).doesNotContain(SqlParameter.integer(0));
    }

    // ------------------------------------------------------------------ 9. aggregates, filters, refunds, empty set

    @Test
    void sumAndCountDistinctCompileExactly() {
        CompiledSql sum = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-sum", "order.net_paid_amount", SegmentValidationFixtures.rolling(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1000.00", "CNY")))));
        // The accepted R4 semantics lower every exact immutable rate into a compiler-owned typed CASE and must
        // never read an unpinned rate table, so the assertion proves the CASE structure exists, the table does
        // not, and the currencies and rates live only in typed bind parameters.
        assertThat(sum.sql())
                .contains("SUM(")
                .contains("COALESCE(")
                .contains("(CASE ")
                .contains(" WHEN ")
                .contains(" END)")
                .doesNotContain("fx_rates")
                .doesNotContain("USD")
                .doesNotContain("7.2");
        assertThat(sum.parameters()).contains(
                SqlParameter.currency("USD"),
                SqlParameter.decimal(new BigDecimal("7.20")),
                SqlParameter.currency("CNY"),
                SqlParameter.decimal(BigDecimal.ONE));

        SemanticQueryPlan distinctPlan = SqlTestSupport.plan(SqlTestSupport.enhancedSnapshot(),
                SegmentValidationFixtures.metric(
                        "n-distinct", "order.distinct_channel", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1))));
        CompiledSql distinct = SqlTestSupport.compile(distinctPlan);
        assertThat(distinct.sql()).contains("COUNT(DISTINCT ");
    }

    @Test
    void nullEmptySetAggregatesStayBareAndZeroEmptySetAggregatesAreCoalesced() {
        CompiledSql average = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-avg", "order.avg_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY")))));
        assertThat(average.sql()).contains("AVG(").doesNotContain("COALESCE(");

        CompiledSql minimum = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-min", "order.min_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY")))));
        assertThat(minimum.sql()).contains("MIN(").doesNotContain("COALESCE(");

        CompiledSql maximum = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-max", "order.max_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY")))));
        assertThat(maximum.sql()).contains("MAX(").doesNotContain("COALESCE(");

        CompiledSql count = SqlTestSupport.compile(countPlan());
        assertThat(count.sql()).doesNotContain("COALESCE(");
    }

    @Test
    void refundSubtractionAndImmutableFiltersCompileExactly() {
        SemanticQueryPlan refundPlan = SqlTestSupport.plan(SqlTestSupport.enhancedSnapshot(),
                SegmentValidationFixtures.metric(
                        "n-refund", "order.refunded_amount", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY"))));
        CompiledSql refund = SqlTestSupport.compile(refundPlan);
        assertThat(refund.sql()).contains(" - COALESCE((SELECT SUM(").contains("\"public\".\"refunds\"");

        SemanticQueryPlan filteredPlan = SqlTestSupport.plan(SqlTestSupport.enhancedSnapshot(),
                SegmentValidationFixtures.metric(
                        "n-filtered", "order.filtered_amount", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY"))));
        CompiledSql filtered = SqlTestSupport.compile(filteredPlan);
        assertThat(filtered.sql()).contains("\"status\" IN (?)");
        assertThat(filtered.parameters()).contains(SqlParameter.text("PAID"));
    }

    @Test
    void aStringResultMetricFailsClosed() {
        SemanticQueryPlan plan = SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-latest", "order.latest_channel", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("ONLINE"))));
        SqlException failure = SqlTestSupport.sqlFailure(() -> SqlTestSupport.compile(plan));
        assertThat(failure.code()).isEqualTo(SqlErrorCodes.SQL_UNSUPPORTED_SEMANTIC);
    }

    // ------------------------------------------------------------------ 10. windows

    @Test
    void rollingWindowBoundariesAreExactParameters() {
        CompiledSql compiled = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-roll", "order.net_paid_amount", SegmentValidationFixtures.rolling(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY")))));

        assertThat(compiled.sql()).contains(">= ?").contains("< ?");
        assertThat(compiled.parameters()).contains(
                SqlParameter.timestamp(Instant.parse("2026-07-03T02:00:00Z")),
                SqlParameter.timestamp(AS_OF));
        assertThat(compiled.sql()).doesNotContain("2026-");
    }

    @Test
    void inclusiveBothBoundariesUseAnInclusiveEnd() {
        SemanticQueryPlan plan = SqlTestSupport.plan(QueryPlanFixtures.inclusiveBothSnapshot(),
                SegmentValidationFixtures.metric(
                        "n-inclusive", "order.inclusive_amount",
                        SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY"))));
        CompiledSql compiled = SqlTestSupport.compile(plan);
        assertThat(compiled.sql()).contains(">= ?").contains("<= ?");
        assertThat(compiled.parameters()).contains(SqlParameter.timestamp(AS_OF));
    }

    @Test
    void lifetimeWindowUsesTheDataWatermarkAndThePinnedEventTimeField() {
        CompiledSql compiled = SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-life", "order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY")))));

        assertThat(compiled.sql()).contains("\"paid_at\" < ?").doesNotContain("\"paid_at\" <= ?");
        assertThat(compiled.parameters()).contains(SqlParameter.timestamp(Instant.parse("2026-10-01T01:55:00Z")));
    }

    // ------------------------------------------------------------------ helpers

    private static SqlExpression userPredicate(CompiledSql compiled) {
        SqlExpression.And root = (SqlExpression.And) compiled.ast().where();
        // tenant, program, DataScope membership, user predicate.
        return root.operands().get(3);
    }

    private static int countExists(SqlExpression node) {
        return switch (node) {
            case SqlExpression.Exists ignored -> 1;
            case SqlExpression.And and -> and.operands().stream().mapToInt(SqlCompileSemanticsTest::countExists).sum();
            case SqlExpression.Or or -> or.operands().stream().mapToInt(SqlCompileSemanticsTest::countExists).sum();
            case SqlExpression.Not not -> countExists(not.operand());
            case SqlExpression.IsNull isNull -> countExists(isNull.operand());
            case SqlExpression.IsNotNull isNotNull -> countExists(isNotNull.operand());
            case SqlExpression.Comparison comparison ->
                    countExists(comparison.left()) + countExists(comparison.right());
            case SqlExpression.InList inList ->
                    countExists(inList.value())
                            + inList.options().stream().mapToInt(SqlCompileSemanticsTest::countExists).sum();
            case SqlExpression.Between between ->
                    countExists(between.value()) + countExists(between.lower()) + countExists(between.upper());
            case SqlExpression.Like like -> countExists(like.value());
            case SqlExpression.Coalesce coalesce ->
                    coalesce.operands().stream().mapToInt(SqlCompileSemanticsTest::countExists).sum();
            case SqlExpression.Case caseExpression ->
                    countExists(caseExpression.operand())
                            + caseExpression.whens().stream().mapToInt(SqlCompileSemanticsTest::countExists).sum()
                            + caseExpression.thens().stream().mapToInt(SqlCompileSemanticsTest::countExists).sum()
                            + (caseExpression.fallback() == null ? 0 : countExists(caseExpression.fallback()));
            case SqlExpression.Multiply multiply ->
                    countExists(multiply.left()) + countExists(multiply.right());
            case SqlExpression.Add add ->
                    add.operands().stream().mapToInt(SqlCompileSemanticsTest::countExists).sum();
            case SqlExpression.Subtract subtract ->
                    countExists(subtract.left()) + countExists(subtract.right());
            case SqlExpression.Divide divide ->
                    countExists(divide.left()) + countExists(divide.right()) + countExists(divide.zero());
            case SqlExpression.CastDecimal cast -> countExists(cast.operand());
            case SqlExpression.AddDays addDays ->
                    countExists(addDays.temporal()) + countExists(addDays.days());
            case SqlExpression.Aggregate aggregate ->
                    aggregate.argument() == null ? 0 : countExists(aggregate.argument());
            case SqlExpression.ScalarSubquery ignored -> 0;
            case SqlExpression.BindParameter ignored -> 0;
            case SqlExpression.Column ignored -> 0;
        };
    }

    private static Predicate attribute(String attributeId, ComparisonOperator operator, ScalarValue value) {
        return SegmentValidationFixtures.attribute(
                "n-" + attributeId, attributeId, operator, SegmentValidationFixtures.value(value));
    }

    private static Predicate attributeCondition(
            String attributeId,
            ComparisonOperator operator,
            com.company.campaign.segment.dsl.predicate.ConditionValue condition) {
        return SegmentValidationFixtures.attribute("n-" + attributeId, attributeId, operator, condition);
    }

    private static Predicate and(Predicate... children) {
        return SegmentValidationFixtures.all("n-all-" + children.length, java.util.Arrays.asList(children));
    }

    private static Predicate or(Predicate... children) {
        return SegmentValidationFixtures.any("n-any-" + children.length, java.util.Arrays.asList(children));
    }

    private static Predicate any(Predicate... children) {
        return SegmentValidationFixtures.any("n-any-" + children.length, java.util.Arrays.asList(children));
    }

    private static Predicate tierGold() {
        return SegmentValidationFixtures.tierEquals("n-tier", "GOLD");
    }

    private static Predicate ageAboveTen() {
        return attribute("member.age", ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.integerValue(10));
    }

    private static Predicate flagTrue() {
        return attribute("member.flag", ComparisonOperator.EQUAL, SegmentValidationFixtures.booleanValue(true));
    }

    private static Predicate channelIsOnline() {
        return attribute(
                "order.channel", ComparisonOperator.EQUAL, SegmentValidationFixtures.enumValue("ONLINE"));
    }

    private static Predicate orderAmountAbove() {
        return attribute(
                "order.amount", ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.moneyValue("10.00", "CNY"));
    }

    private static SemanticQueryPlan countPlan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-count", "order.count", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1))));
    }
}
