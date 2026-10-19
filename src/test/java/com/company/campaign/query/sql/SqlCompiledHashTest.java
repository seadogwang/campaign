package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 14 and 15: the compiled hash changes when any covering fact changes and compiling identical
 * inputs one hundred times produces byte-identical artifacts.
 */
class SqlCompiledHashTest {

    private static final int REPETITIONS = 100;

    @Test
    void compilingIdenticalInputsOneHundredTimesIsByteIdentical() {
        SemanticQueryPlan plan = SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-sum", "order.net_paid_amount",
                SegmentValidationFixtures.rolling(90, com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1000.00", "CNY"))));
        StorageCatalog catalog = SqlTestSupport.standardCatalog();
        FxRateBinding fx = SqlTestSupport.cnyFx();

        CompiledSql first = SqlTestSupport.compile(plan, catalog, fx);
        for (int iteration = 0; iteration < REPETITIONS; iteration++) {
            CompiledSql repeated = SqlTestSupport.compile(plan, catalog, fx);
            assertThat(repeated.sql()).isEqualTo(first.sql());
            assertThat(repeated.parameters()).isEqualTo(first.parameters());
            assertThat(repeated.ast()).isEqualTo(first.ast());
            assertThat(repeated.compiledHash()).isEqualTo(first.compiledHash());
            assertThat(repeated.catalogHash()).isEqualTo(first.catalogHash());
            assertThat(repeated.planHash()).isEqualTo(first.planHash());
        }
    }

    @Test
    void changingThePlanChangesTheCompiledHash() {
        CompiledSql first = SqlTestSupport.compile(
                SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));
        CompiledSql second = SqlTestSupport.compile(
                SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "SILVER")));
        assertThat(second.planHash()).isNotEqualTo(first.planHash());
        assertThat(second.compiledHash()).isNotEqualTo(first.compiledHash());
    }

    @Test
    void changingTheCatalogChangesTheCompiledHash() {
        SemanticQueryPlan plan = SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD"));
        StorageCatalog base = SqlTestSupport.standardCatalog();

        List<StorageCatalog.AttributeStorage> renamed = new java.util.ArrayList<>();
        for (StorageCatalog.AttributeStorage attribute : base.attributes()) {
            renamed.add(attribute.definitionId().equals("member.tier")
                    ? new StorageCatalog.AttributeStorage(
                            attribute.definitionId(), attribute.subject(), attribute.entitySchemaVersion(),
                            attribute.dataType(), SqlIdentifier.of("tier_code"), attribute.currencyColumn())
                    : attribute);
        }
        StorageCatalog variant = StorageCatalog.publish(
                base.catalogVersion(), base.entities(), renamed, base.metrics(), base.relations(),
                base.dataScope(), base.fxStrategy(), base.refundStorage());

        CompiledSql first = SqlTestSupport.compile(plan, base, SqlTestSupport.cnyFx());
        CompiledSql second = SqlTestSupport.compile(plan, variant, SqlTestSupport.cnyFx());
        assertThat(second.catalogHash()).isNotEqualTo(first.catalogHash());
        assertThat(second.compiledHash()).isNotEqualTo(first.compiledHash());
        assertThat(second.sql()).isNotEqualTo(first.sql());
    }

    @Test
    void changingTheExternalBindingChangesTheCompiledHash() {
        SemanticQueryPlan plan = SqlTestSupport.plan(SegmentValidationFixtures.metric(
                "n-sum", "order.net_paid_amount",
                SegmentValidationFixtures.rolling(30, com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0", "CNY"))));
        CompiledSql cheap = SqlTestSupport.compile(plan, SqlTestSupport.standardCatalog(), SqlTestSupport.cnyFx("7.20"));
        CompiledSql dear = SqlTestSupport.compile(plan, SqlTestSupport.standardCatalog(), SqlTestSupport.cnyFx("8.10"));

        assertThat(dear.externalBindingHash()).isNotEqualTo(cheap.externalBindingHash());
        assertThat(dear.compiledHash()).isNotEqualTo(cheap.compiledHash());
        assertThat(dear.sql()).isEqualTo(cheap.sql());
    }

    @Test
    void changingTheAstParameterTypeOrValueChangesTheCompiledHash() {
        ContentHash text = hashOf(SqlParameter.text("gold"));
        ContentHash otherText = hashOf(SqlParameter.text("silver"));
        ContentHash enumValue = hashOf(SqlParameter.enumCode("GOLD"));

        assertThat(otherText).isNotEqualTo(text);
        assertThat(enumValue).isNotEqualTo(text);
        assertThat(enumValue).isNotEqualTo(otherText);
    }

    @Test
    void changingACompilerOrFormatVersionChangesTheCompiledHash() {
        ContentHash base = hashWith("segment-sql-compiler/1.0", "segment-sql-ast/1.0", "postgresql-select/1.0");
        assertThat(hashWith("segment-sql-compiler/9.9", "segment-sql-ast/1.0", "postgresql-select/1.0"))
                .isNotEqualTo(base);
        assertThat(hashWith("segment-sql-compiler/1.0", "segment-sql-ast/9.9", "postgresql-select/1.0"))
                .isNotEqualTo(base);
        assertThat(hashWith("segment-sql-compiler/1.0", "segment-sql-ast/1.0", "postgresql-select/9.9"))
                .isNotEqualTo(base);
    }

    // ------------------------------------------------------------------ helpers

    private static ContentHash hashOf(SqlParameter parameter) {
        SqlIdentifier alias = SqlIdentifier.of("m0");
        SqlSelect select = new SqlSelect(
                new SqlExpression.Column(alias, SqlIdentifier.of("member_id")),
                new TableRef(SqlIdentifier.of("public"), SqlIdentifier.of("members")),
                alias,
                new SqlExpression.Comparison(
                        new SqlExpression.Column(alias, SqlIdentifier.of("tier")),
                        SqlComparisonOperator.EQUAL,
                        new SqlExpression.BindParameter(parameter)));
        SqlRenderer.Rendered rendered = SqlRenderer.render(select);
        return CompiledSql.computeHash(
                select, rendered.sql(), rendered.parameters(),
                ContentHash.sha256("plan".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "catalog", ContentHash.sha256("catalog".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                SqlVersions.SQL_COMPILER_VERSION, SqlVersions.SQL_AST_VERSION,
                SqlVersions.SQL_RENDER_FORMAT_VERSION, null);
    }

    private static ContentHash hashWith(String compiler, String ast, String render) {
        SqlParameter parameter = SqlParameter.decimal(new BigDecimal("1.00"));
        SqlIdentifier alias = SqlIdentifier.of("m0");
        SqlSelect select = new SqlSelect(
                new SqlExpression.Column(alias, SqlIdentifier.of("member_id")),
                new TableRef(SqlIdentifier.of("public"), SqlIdentifier.of("members")),
                alias,
                new SqlExpression.Comparison(
                        new SqlExpression.Column(alias, SqlIdentifier.of("tier")),
                        SqlComparisonOperator.EQUAL,
                        new SqlExpression.BindParameter(parameter)));
        SqlRenderer.Rendered rendered = SqlRenderer.render(select);
        return CompiledSql.computeHash(
                select, rendered.sql(), rendered.parameters(),
                ContentHash.sha256("plan".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "catalog", ContentHash.sha256("catalog".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                compiler, ast, render, null);
    }
}