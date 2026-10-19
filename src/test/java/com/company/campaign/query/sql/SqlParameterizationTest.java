package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirements 4, 12 and 13: every value is a typed ordered parameter, adversarial operands and LIKE wildcards
 * never change the SQL text, the text is SELECT-only, and neither the AST nor the artifact offers a raw-SQL or
 * forging escape hatch.
 */
class SqlParameterizationTest {

    private static final String ADVERSARIAL = "'; DROP TABLE members; -- /* comment */ %_";
    private static final Pattern DML_OR_DDL =
            Pattern.compile("\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|CALL|COPY|MERGE)\\b");

    @Test
    void anAdversarialOperandNeverChangesTheSqlText() {
        CompiledSql adversarial = SqlTestSupport.compile(sqlTestPlan(
                SegmentValidationFixtures.stringValue(ADVERSARIAL)));
        CompiledSql plain = SqlTestSupport.compile(sqlTestPlan(SegmentValidationFixtures.stringValue("x")));

        assertThat(adversarial.sql()).isEqualTo(plain.sql());
        assertThat(adversarial.parameters()).isNotEqualTo(plain.parameters());
        assertThat(adversarial.parameters()).contains(SqlParameter.text(ADVERSARIAL));
        assertThat(adversarial.sql())
                .doesNotContain(ADVERSARIAL)
                .doesNotContain("DROP")
                .doesNotContain("--")
                .doesNotContain("/*");
    }

    @Test
    void likeWildcardsAreEscapedAsDataWithTheCompilerOwnedEscapeSyntax() {
        CompiledSql percent = SqlTestSupport.compile(sqlTestPlan(
                SegmentValidationFixtures.stringValue("100%"), ComparisonOperator.CONTAINS));
        CompiledSql plain = SqlTestSupport.compile(sqlTestPlan(
                SegmentValidationFixtures.stringValue("100X"), ComparisonOperator.CONTAINS));

        assertThat(percent.sql()).isEqualTo(plain.sql())
                .contains("LIKE ? ESCAPE '!'");
        assertThat(percent.parameters()).contains(SqlParameter.text("%100!%%"));
        assertThat(plain.parameters()).contains(SqlParameter.text("%100X%"));

        SqlExpression.Like like =
                (SqlExpression.Like) ((SqlExpression.And) percent.ast().where()).operands().get(3);
        assertThat(like.escape()).isEqualTo('!');
    }

    @Test
    void everyValueIsAMarkerSoTheMarkerCountMatchesTheParameterCount() {
        List<CompiledSql> artifacts = List.of(
                SqlTestSupport.compile(sqlTestPlan(SegmentValidationFixtures.stringValue(ADVERSARIAL))),
                SqlTestSupport.compile(
                        SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD"))),
                SqlTestSupport.compile(SqlTestSupport.plan(SegmentValidationFixtures.metric(
                        "n-sum", "order.net_paid_amount", SegmentValidationFixtures.rolling(90,
                                com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1.00", "CNY"))))));
        for (CompiledSql compiled : artifacts) {
            assertThat(countMarkers(compiled.sql())).isEqualTo(compiled.parameters().size());
            assertThat(compiled.parameters()).allSatisfy(parameter ->
                    assertThat(parameter.canonicalValue()).isNotNull());
        }
    }

    @Test
    void theRenderedTextIsOneSelectStatementWithNoCommentOrMutation() {
        CompiledSql compiled = SqlTestSupport.compile(
                SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));
        String sql = compiled.sql();

        assertThat(sql).startsWith("SELECT ");
        assertThat(sql).doesNotContain(";");
        assertThat(sql).doesNotContain("--");
        assertThat(sql).doesNotContain("/*");
        assertThat(DML_OR_DDL.matcher(sql).find()).as("no DML/DDL keyword may appear").isFalse();
        assertThat(sql).endsWith(")");
    }

    @Test
    void theAstHasNoRawTextComponent() {
        assertThat(SqlExpression.class.isSealed()).isTrue();
        for (Class<?> permitted : SqlExpression.class.getPermittedSubclasses()) {
            assertThat(permitted.isRecord())
                    .as("%s must be a closed record node", permitted.getSimpleName())
                    .isTrue();
            for (RecordComponent component : permitted.getRecordComponents()) {
                assertThat(isAllowed(component.getType()))
                        .as("%s.%s must not carry raw SQL text", permitted.getSimpleName(), component.getName())
                        .isTrue();
            }
        }
        for (RecordComponent component : SqlSelect.class.getRecordComponents()) {
            assertThat(isAllowed(component.getType())).isTrue();
        }
    }

    @Test
    void theCompiledArtifactCannotBeForgedFromOutsideOrMutated() {
        assertThat(Modifier.isFinal(CompiledSql.class.getModifiers())).isTrue();
        assertThat(CompiledSql.class.getConstructors()).isEmpty();
        for (Constructor<?> constructor : CompiledSql.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
        }
        List<String> methodNames = java.util.Arrays.stream(CompiledSql.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();
        assertThat(methodNames)
                .noneMatch(name -> name.startsWith("set")
                        || name.equals("builder")
                        || name.equals("of")
                        || name.equals("parse")
                        || name.equals("fromJson")
                        || name.equals("read"));

        CompiledSql compiled = SqlTestSupport.compile(
                SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));
        assertThatThrownBy(() -> compiled.parameters().add(SqlParameter.text("x")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(compiled.ast()).isInstanceOf(SqlSelect.class);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isAllowed(Class<?> type) {
        return type == SqlExpression.class
                || type == SqlExpression.BindParameter.class
                || type == SqlIdentifier.class
                || type == SqlParameter.class
                || type == SqlSelect.class
                || type == SqlSelect.OrderBy.class
                || type == TableRef.class
                || type == List.class
                || type == Integer.class
                || type == boolean.class
                || type == char.class
                || type.isEnum();
    }

    private static int countMarkers(String sql) {
        int count = 0;
        for (int index = 0; index < sql.length(); index++) {
            if (sql.charAt(index) == '?') {
                count++;
            }
        }
        return count;
    }

    private static SemanticQueryPlan sqlTestPlan(com.company.campaign.segment.dsl.value.ScalarValue value) {
        return sqlTestPlan(value, ComparisonOperator.EQUAL);
    }

    private static SemanticQueryPlan sqlTestPlan(
            com.company.campaign.segment.dsl.value.ScalarValue value, ComparisonOperator operator) {
        Predicate predicate = SegmentValidationFixtures.attribute(
                "n-code", "member.code", operator, SegmentValidationFixtures.value(value));
        return SqlTestSupport.plan(SqlTestSupport.identityStringSnapshot(), predicate);
    }
}
