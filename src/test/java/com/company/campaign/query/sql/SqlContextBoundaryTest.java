package com.company.campaign.query.sql;

import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 11: tenant, program and DataScope predicates are mandatory, structurally separate from the user
 * predicate and sourced only from the trusted plan context, so no user input can supply, alter or remove one.
 */
class SqlContextBoundaryTest {

    private static final String TENANT = "tenant-001";
    private static final String PROGRAM = "program-001";
    private static final String SCOPE = "scope-008";

    @Test
    void theMandatoryPredicatesAlwaysPrecedeTheUserPredicate() {
        CompiledSql compiled = SqlTestSupport.compile(tierPlan());

        SqlExpression.And root = (SqlExpression.And) compiled.ast().where();
        assertThat(root.operands()).hasSize(4);
        assertThat(root.operands().get(0)).isInstanceOf(SqlExpression.Comparison.class);
        assertThat(root.operands().get(1)).isInstanceOf(SqlExpression.Comparison.class);
        assertThat(root.operands().get(2)).isInstanceOf(SqlExpression.Exists.class);
        assertThat(root.operands().get(3)).isInstanceOf(SqlExpression.Comparison.class);
    }

    @Test
    void everyMandatoryValueIsATrustedContextBindParameter() {
        CompiledSql compiled = SqlTestSupport.compile(tierPlan());

        assertThat(compiled.parameters()).containsExactly(
                SqlParameter.contextId(TENANT),
                SqlParameter.contextId(PROGRAM),
                SqlParameter.contextId(SCOPE),
                SqlParameter.contextId(TENANT),
                SqlParameter.contextId(PROGRAM),
                SqlParameter.enumCode("GOLD"));

        assertThat(compiled.sql())
                .contains(".\"tenant_id\" = ?")
                .contains(".\"program_id\" = ?")
                .contains(".\"scope_id\" = ?");
    }

    @Test
    void theDataScopeMembershipPredicateCorrelatesTheRootKeyToTheScopeTable() {
        CompiledSql compiled = SqlTestSupport.compile(tierPlan());

        SqlExpression.Exists scope = (SqlExpression.Exists) ((SqlExpression.And) compiled.ast().where()).operands().get(2);
        assertThat(scope.subquery().from().table().value()).isEqualTo("data_scope_members");
        SqlExpression.And predicates = (SqlExpression.And) scope.subquery().where();
        assertThat(predicates.operands()).hasSize(4);
        assertThat(predicates.operands().get(3)).isInstanceOf(SqlExpression.Comparison.class);
        SqlExpression.Comparison correlation = (SqlExpression.Comparison) predicates.operands().get(3);
        assertThat(correlation.right()).isInstanceOf(SqlExpression.Column.class);
        assertThat(((SqlExpression.Column) correlation.right()).column().value()).isEqualTo("member_id");
    }

    @Test
    void theMandatoryPredicatesAreIndependentOfTheUserPredicate() {
        SqlExpression.And first = (SqlExpression.And) SqlTestSupport.compile(tierPlan()).ast().where();
        SqlExpression.And second = (SqlExpression.And) SqlTestSupport.compile(flagPlan()).ast().where();

        assertThat(first.operands().subList(0, 3)).isEqualTo(second.operands().subList(0, 3));
        assertThat(first.operands().get(3)).isNotEqualTo(second.operands().get(3));
    }

    @Test
    void theDataScopeComesOnlyFromTheTrustedContext() {
        CompiledSql standard = SqlTestSupport.compile(tierPlan());
        CompiledSql other = SqlTestSupport.compile(SqlTestSupport.planWithContext(tierPredicate(), "scope-099"));

        // The SQL text is a pure function of the AST structure, so only the bound scope value changes.
        assertThat(other.sql()).isEqualTo(standard.sql());
        assertThat(other.parameters()).contains(SqlParameter.contextId("scope-099"));
        assertThat(other.parameters()).doesNotContain(SqlParameter.contextId(SCOPE));
        assertThat(other.compiledHash()).isNotEqualTo(standard.compiledHash());
    }

    @Test
    void theCompilerSurfaceHasNoFlagThatCouldDisableAMandatoryPredicate() {
        for (java.lang.reflect.Method method : SqlCompiler.class.getMethods()) {
            if (method.getDeclaringClass() != SqlCompiler.class) {
                continue;
            }
            assertThat(java.util.Arrays.asList(method.getParameterTypes()))
                    .as("compiler method %s must not accept a caller-controlled toggle", method.getName())
                    .noneMatch(type -> type == boolean.class || type == Boolean.class || type == String.class);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SemanticQueryPlan tierPlan() {
        return SqlTestSupport.plan(tierPredicate());
    }

    private static SemanticQueryPlan flagPlan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.attribute(
                "n-flag", "member.flag", com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.booleanValue(true))));
    }

    private static com.company.campaign.segment.dsl.predicate.Predicate tierPredicate() {
        return SegmentValidationFixtures.tierEquals("n-tier", "GOLD");
    }
}