package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 1 and 2: every tenant-owned alias a compiled plan can access — the root table, each related
 * entity scalar subquery, each relation {@code EXISTS} (including a nested one) and each refund subquery — is
 * constrained by its own compiler-owned tenant and program predicates, sourced only from the trusted plan
 * context, before any correlation, window, filter or user predicate.
 *
 * <p>The fixture binds distinct context column names to the root, related entity and refund mappings, so a
 * lowering that reused one shared or global context mapping would be detectable rather than accidentally
 * correct.
 */
class SqlCrossContextSubqueryTest {

    private static final String TENANT = "tenant-001";
    private static final String PROGRAM = "program-001";
    private static final String MEMBER_TENANT_COLUMN = "member_tenant";
    private static final String MEMBER_PROGRAM_COLUMN = "member_program";
    private static final String ORDER_TENANT_COLUMN = "order_tenant";
    private static final String ORDER_PROGRAM_COLUMN = "order_program";
    private static final String REFUND_TENANT_COLUMN = "refund_tenant";
    private static final String REFUND_PROGRAM_COLUMN = "refund_program";

    // ------------------------------------------------------------------ 1. metric scalar subquery

    @Test
    void aRelatedEntityMetricSubqueryCarriesItsOwnContextPredicatesBeforeTheCorrelation() {
        CompiledSql compiled = compile(metricPlan());

        assertThat(compiled.sql())
                .contains("\"order_tenant\" = ?")
                .contains("\"order_program\" = ?");

        SqlSelect metric = selectFor(compiled, "orders").get(0);
        assertContextBeforeCorrelation(metric, ORDER_TENANT_COLUMN, ORDER_PROGRAM_COLUMN);
    }

    // ------------------------------------------------------------------ 2. relation EXISTS

    @Test
    void aRelationExistsSubqueryCarriesItsOwnContextPredicatesBeforeTheCorrelation() {
        CompiledSql compiled = compile(existsPlan());

        assertThat(compiled.sql())
                .contains("\"member_tenant\" = ?")
                .contains("\"order_tenant\" = ?")
                .contains("\"order_program\" = ?");

        assertContextBeforeCorrelation(selectFor(compiled, "orders").get(0),
                ORDER_TENANT_COLUMN, ORDER_PROGRAM_COLUMN);
    }

    @Test
    void aNestedRelationExistsCarriesContextPredicatesAtEveryLevel() {
        CompiledSql compiled = compile(nestedExistsPlan());

        // Two ORDER targets are accessed — the outer EXISTS and the nested one — and each owns its own pair.
        List<SqlSelect> orders = selectFor(compiled, "orders");
        assertThat(orders).hasSize(2);
        for (SqlSelect select : orders) {
            assertContextBeforeCorrelation(select, ORDER_TENANT_COLUMN, ORDER_PROGRAM_COLUMN);
        }
    }

    // ------------------------------------------------------------------ 3. refund subquery

    @Test
    void aRefundSubqueryCarriesItsOwnContextPredicatesBeforeTheOrderCorrelation() {
        CompiledSql compiled = compile(refundPlan());

        assertThat(compiled.sql())
                .contains("\"refund_tenant\" = ?")
                .contains("\"refund_program\" = ?");

        assertContextBeforeCorrelation(selectFor(compiled, "refunds").get(0),
                REFUND_TENANT_COLUMN, REFUND_PROGRAM_COLUMN);
    }

    // ------------------------------------------------------------------ every alias, every value

    @Test
    void everyAccessedTenantOwnedAliasBindsTrustedTenantAndProgramValuesAsParameters() {
        for (CompiledSql compiled : List.of(
                compile(metricPlan()), compile(existsPlan()), compile(nestedExistsPlan()), compile(refundPlan()))) {
            List<String> constrained = new ArrayList<>();
            for (SqlSelect select : allSelects(compiled)) {
                String table = select.from().table().value();
                String[] expected = expectedContext(table);
                if (expected == null) {
                    continue;
                }
                assertContextBeforeCorrelation(select, expected[0], expected[1]);
                constrained.add(select.alias().value());
            }

            // The DataScope membership table is likewise constrained from the trusted context: its scope id,
            // tenant and program are all typed context bind parameters, never user-supplied values.
            SqlSelect scope = selectFor(compiled, "data_scope_members").get(0);
            List<SqlExpression> scopePredicates = ((SqlExpression.And) scope.where()).operands();
            assertThat(columnName(comparison(scopePredicates.get(1)).left())).isEqualTo("tenant_id");
            assertThat(boundContextId(comparison(scopePredicates.get(1)).right()))
                    .isEqualTo(SqlParameter.contextId(TENANT));
            assertThat(columnName(comparison(scopePredicates.get(2)).left())).isEqualTo("program_id");
            assertThat(boundContextId(comparison(scopePredicates.get(2)).right()))
                    .isEqualTo(SqlParameter.contextId(PROGRAM));

            assertThat(constrained).doesNotHaveDuplicates();
        }
    }

    @Test
    void theRootSelectUsesTheExactRootEntityContextMapping() {
        CompiledSql compiled = compile(metricPlan());

        SqlSelect root = compiled.ast();
        assertThat(root.from().table().value()).isEqualTo("members");
        assertContextBeforeCorrelation(root, MEMBER_TENANT_COLUMN, MEMBER_PROGRAM_COLUMN);
        assertThat(compiled.sql()).contains("\"member_tenant\" = ?").contains("\"member_program\" = ?");
    }

    // ------------------------------------------------------------------ detection of a shared mapping

    @Test
    void aMistakenSharedOrGlobalContextMappingIsDetectable() {
        SemanticQueryPlan plan = metricPlan();
        CompiledSql scoped = SqlTestSupport.compile(plan, scopedCatalog(), SqlTestSupport.cnyFx());
        CompiledSql shared = SqlTestSupport.compile(plan, SqlTestSupport.standardCatalog(), SqlTestSupport.cnyFx());

        // Same plan and same bind values, but a lowering that assumed one shared mapping would emit the wrong
        // columns: the scoped catalog's SQL names the order table's own columns and cannot equal the catalog
        // that reuses the root columns.
        assertThat(shared.sql()).contains("\"tenant_id\" = ?").contains("\"program_id\" = ?");
        assertThat(scoped.sql()).contains("\"order_tenant\" = ?").contains("\"order_program\" = ?");
        assertThat(scoped.sql()).isNotEqualTo(shared.sql());
        assertThat(scoped.catalogHash()).isNotEqualTo(shared.catalogHash());
        assertThat(scoped.compiledHash()).isNotEqualTo(shared.compiledHash());

        // The scoped order predicate is qualified by the order alias, never the root alias.
        SqlSelect metric = selectFor(scoped, "orders").get(0);
        assertContextBeforeCorrelation(metric, ORDER_TENANT_COLUMN, ORDER_PROGRAM_COLUMN);
        assertThat(metric.alias()).isNotEqualTo(scoped.ast().alias());
    }

    // ------------------------------------------------------------------ helpers

    private static void assertContextBeforeCorrelation(
            SqlSelect select, String tenantColumn, String programColumn) {
        assertThat(select.where()).isInstanceOf(SqlExpression.And.class);
        List<SqlExpression> predicates = ((SqlExpression.And) select.where()).operands();

        SqlExpression.Comparison tenant = comparison(predicates.get(0));
        assertThat(columnName(tenant.left())).isEqualTo(tenantColumn);
        assertThat(boundContextId(tenant.right())).isEqualTo(SqlParameter.contextId(TENANT));

        SqlExpression.Comparison program = comparison(predicates.get(1));
        assertThat(columnName(program.left())).isEqualTo(programColumn);
        assertThat(boundContextId(program.right())).isEqualTo(SqlParameter.contextId(PROGRAM));

        // Every context predicate precedes the correlation, window, filter and user predicates that follow, so
        // no business key can correlate a row from another tenant or program.
        assertThat(predicates.size()).isGreaterThanOrEqualTo(3);
    }

    private static SqlExpression.Comparison comparison(SqlExpression expression) {
        assertThat(expression).isInstanceOf(SqlExpression.Comparison.class);
        return (SqlExpression.Comparison) expression;
    }

    private static String columnName(SqlExpression expression) {
        assertThat(expression).isInstanceOf(SqlExpression.Column.class);
        return ((SqlExpression.Column) expression).column().value();
    }

    private static SqlParameter boundContextId(SqlExpression expression) {
        assertThat(expression).isInstanceOf(SqlExpression.BindParameter.class);
        SqlParameter parameter = ((SqlExpression.BindParameter) expression).parameter();
        assertThat(parameter.type()).isEqualTo(SqlParameterType.CONTEXT_ID);
        return parameter;
    }

    private static String[] expectedContext(String table) {
        return switch (table) {
            case "members" -> new String[] {MEMBER_TENANT_COLUMN, MEMBER_PROGRAM_COLUMN};
            case "orders" -> new String[] {ORDER_TENANT_COLUMN, ORDER_PROGRAM_COLUMN};
            case "refunds" -> new String[] {REFUND_TENANT_COLUMN, REFUND_PROGRAM_COLUMN};
            default -> null;
        };
    }

    /** Every {@link SqlSelect} in one compiled artifact, outermost first, in deterministic walk order. */
    private static List<SqlSelect> allSelects(CompiledSql compiled) {
        List<SqlSelect> selects = new ArrayList<>();
        collectSelect(compiled.ast(), selects);
        return List.copyOf(selects);
    }

    private static List<SqlSelect> selectFor(CompiledSql compiled, String table) {
        return allSelects(compiled).stream()
                .filter(select -> select.from().table().value().equals(table))
                .toList();
    }

    private static void collectSelect(SqlSelect select, List<SqlSelect> selects) {
        selects.add(select);
        collect(select.projection(), selects);
        collect(select.where(), selects);
    }

    private static void collect(SqlExpression node, List<SqlSelect> selects) {
        switch (node) {
            case SqlExpression.Exists exists -> collectSelect(exists.subquery(), selects);
            case SqlExpression.ScalarSubquery subquery -> collectSelect(subquery.subquery(), selects);
            case SqlExpression.And and -> and.operands().forEach(operand -> collect(operand, selects));
            case SqlExpression.Or or -> or.operands().forEach(operand -> collect(operand, selects));
            case SqlExpression.Not not -> collect(not.operand(), selects);
            case SqlExpression.IsNull isNull -> collect(isNull.operand(), selects);
            case SqlExpression.IsNotNull isNotNull -> collect(isNotNull.operand(), selects);
            case SqlExpression.Comparison comparison -> {
                collect(comparison.left(), selects);
                collect(comparison.right(), selects);
            }
            case SqlExpression.InList inList -> {
                collect(inList.value(), selects);
                inList.options().forEach(option -> collect(option, selects));
            }
            case SqlExpression.Between between -> {
                collect(between.value(), selects);
                collect(between.lower(), selects);
                collect(between.upper(), selects);
            }
            case SqlExpression.Like like -> collect(like.value(), selects);
            case SqlExpression.Coalesce coalesce -> coalesce.operands().forEach(operand -> collect(operand, selects));
            case SqlExpression.Case caseExpression -> {
                collect(caseExpression.operand(), selects);
                caseExpression.whens().forEach(when -> collect(when, selects));
                caseExpression.thens().forEach(then -> collect(then, selects));
                if (caseExpression.fallback() != null) {
                    collect(caseExpression.fallback(), selects);
                }
            }
            case SqlExpression.Multiply multiply -> {
                collect(multiply.left(), selects);
                collect(multiply.right(), selects);
            }
            case SqlExpression.Add add -> add.operands().forEach(operand -> collect(operand, selects));
            case SqlExpression.Subtract subtract -> {
                collect(subtract.left(), selects);
                collect(subtract.right(), selects);
            }
            case SqlExpression.Divide divide -> {
                collect(divide.left(), selects);
                collect(divide.right(), selects);
                collect(divide.zero(), selects);
            }
            case SqlExpression.CastDecimal cast -> collect(cast.operand(), selects);
            case SqlExpression.AddDays addDays -> {
                collect(addDays.temporal(), selects);
                collect(addDays.days(), selects);
            }
            case SqlExpression.Aggregate aggregate -> {
                if (aggregate.argument() != null) {
                    collect(aggregate.argument(), selects);
                }
            }
            case SqlExpression.BindParameter ignored -> {
            }
            case SqlExpression.Column ignored -> {
            }
        }
    }

    private static CompiledSql compile(SemanticQueryPlan plan) {
        return SqlTestSupport.compile(plan, scopedCatalog(), SqlTestSupport.cnyFx());
    }

    // ------------------------------------------------------------------ plans

    private static SemanticQueryPlan metricPlan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-sum", "order.net_paid_amount", SegmentValidationFixtures.rolling(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1000.00", "CNY"))));
    }

    private static SemanticQueryPlan existsPlan() {
        Predicate child = SegmentValidationFixtures.attribute(
                "n-channel", "order.channel", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")));
        return SqlTestSupport.plan(SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(), child));
    }

    private static SemanticQueryPlan nestedExistsPlan() {
        Predicate inner = SegmentValidationFixtures.attribute(
                "n-channel", "order.channel", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")));
        Predicate outer = SegmentValidationFixtures.exists(
                "n-outer", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.exists(
                        "n-inner", "order.lines", SegmentValidationFixtures.lifetime(), inner));
        return SqlTestSupport.plan(outer);
    }

    private static SemanticQueryPlan refundPlan() {
        return SqlTestSupport.plan(SqlTestSupport.enhancedSnapshot(),
                SegmentValidationFixtures.metric(
                        "n-refund", "order.refunded_amount", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY"))));
    }

    // ------------------------------------------------------------------ catalog

    /**
     * The standard catalog with a distinct, explicit context mapping per tenant-owned table shape: the root
     * member table, the related order table and the refund table each declare their own tenant/program columns.
     */
    private static StorageCatalog scopedCatalog() {
        StorageCatalog base = SqlTestSupport.standardCatalog();
        List<StorageCatalog.EntityStorage> entities = new ArrayList<>();
        for (StorageCatalog.EntityStorage entity : base.entities()) {
            StorageCatalog.ContextStorage context = switch (entity.entityVersion().entity()) {
                case MEMBER -> context(MEMBER_TENANT_COLUMN, MEMBER_PROGRAM_COLUMN);
                case ORDER -> context(ORDER_TENANT_COLUMN, ORDER_PROGRAM_COLUMN);
                case EVENT -> context("event_tenant", "event_program");
                case ACCOUNT -> context("account_tenant", "account_program");
            };
            entities.add(new StorageCatalog.EntityStorage(
                    entity.entityVersion(), entity.table(), entity.primaryKeyColumn(), context));
        }
        StorageCatalog.RefundStorage refund = new StorageCatalog.RefundStorage(
                base.refundStorage().table(), base.refundStorage().orderIdColumn(),
                base.refundStorage().amountColumn(), context(REFUND_TENANT_COLUMN, REFUND_PROGRAM_COLUMN));
        return StorageCatalog.publish(base.catalogVersion(), entities, base.attributes(), base.metrics(),
                base.relations(), base.dataScope(), base.fxStrategy(), refund);
    }

    private static StorageCatalog.ContextStorage context(String tenantColumn, String programColumn) {
        return new StorageCatalog.ContextStorage(
                SqlIdentifier.of(tenantColumn), SqlIdentifier.of(programColumn));
    }
}
