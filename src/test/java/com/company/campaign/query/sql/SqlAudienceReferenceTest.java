package com.company.campaign.query.sql;

import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.plan.ReferenceExecutionCheck;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.ReferenceQueryFixtures;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18: an Existing Audience Reference lowers to one parameterized, allowlisted membership {@code EXISTS}.
 *
 * <p>The emitted statement must never carry the version pin, an audience identifier or a scope value as text:
 * every value is a counted bind parameter and every physical name comes from the server-owned catalog. There
 * is no mutable {@code current}/{@code latest} form to emit, a catalog without a membership binding fails
 * closed, and SQL generation cannot drop the execution-time re-check.
 */
class SqlAudienceReferenceTest {

    private static final AudienceVersionRef VERSION = ReferenceQueryFixtures.version();

    private static SemanticQueryPlan referencePlan(InMemoryAudienceVersionCatalog catalog) {
        return ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
    }

    private static ReferenceExecutionCheck check(InMemoryAudienceVersionCatalog catalog) {
        return ReferenceQueryFixtures.executionCheck(catalog, SegmentReferenceFixtures.allowAll());
    }

    private static SqlExpression.Exists membershipExists(CompiledSql compiled) {
        SqlExpression.And root = (SqlExpression.And) compiled.ast().where();
        // tenant, program, DataScope membership, user predicate.
        SqlExpression user = root.operands().get(3);
        return (SqlExpression.Exists) user;
    }

    // ---------------------------------------------------------------- lowering

    @Test
    void aReferenceLowersToACorrelatedParameterizedMembershipExists() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        CompiledSql compiled = SqlTestSupport.compileWithReferenceCheck(
                referencePlan(catalog), SqlTestSupport.catalogWithAudienceMembership(), check(catalog));

        SqlExpression.Exists exists = membershipExists(compiled);
        assertThat(exists.subquery().from())
                .isEqualTo(SqlTestSupport.table("public", "audience_members"));
        assertThat(exists.subquery().where()).isInstanceOf(SqlExpression.And.class);
        List<SqlExpression> predicates = ((SqlExpression.And) exists.subquery().where()).operands();
        // tenant, program, audience, version, content hash, row correlation.
        assertThat(predicates).hasSize(6);
        // Every value is a bind parameter, never a literal in the statement text.
        for (SqlExpression predicate : predicates) {
            SqlExpression.Comparison comparison = (SqlExpression.Comparison) predicate;
            assertThat(comparison.operator()).isEqualTo(SqlComparisonOperator.EQUAL);
            assertThat(comparison.left()).isInstanceOf(SqlExpression.Column.class);
            boolean correlated = comparison.right() instanceof SqlExpression.Column;
            assertThat(correlated || comparison.right() instanceof SqlExpression.BindParameter).isTrue();
        }
        // Exactly one predicate correlates to the enclosing row rather than to a bound value.
        assertThat(predicates.stream()
                .map(predicate -> ((SqlExpression.Comparison) predicate).right())
                .filter(SqlExpression.Column.class::isInstance)
                .count()).isEqualTo(1);
    }

    @Test
    void everyValueIsBoundAndNoPinEverAppearsInTheStatementText() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        CompiledSql compiled = SqlTestSupport.compileWithReferenceCheck(
                referencePlan(catalog), SqlTestSupport.catalogWithAudienceMembership(), check(catalog));

        String sql = compiled.sql().toLowerCase(Locale.ROOT);
        assertThat(sql)
                .doesNotContain(VERSION.audienceId())
                .doesNotContain(VERSION.versionId())
                .doesNotContain(VERSION.versionHash().value())
                .doesNotContain(SegmentReferenceFixtures.TENANT)
                .doesNotContain(SegmentReferenceFixtures.PROGRAM)
                .doesNotContain("current")
                .doesNotContain("latest");
        // Identifier allowlisting: only the catalog-declared names reach the statement text.
        assertThat(compiled.sql())
                .contains("\"public\".\"audience_members\"")
                .contains("\"audience_id\"")
                .contains("\"version_id\"")
                .contains("\"version_hash\"")
                .contains("\"member_id\"");
        assertThat(compiled.parameters())
                .extracting(SqlParameter::canonicalValue)
                .contains(VERSION.audienceId(), VERSION.versionId(), VERSION.versionHash().value(),
                        SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM);
        // One placeholder per bound parameter, in render order.
        assertThat(compiled.sql().chars().filter(character -> character == '?').count())
                .isEqualTo(compiled.parameters().size());
    }

    @Test
    void theExactImmutableVersionIdentityAndItsContentHashAreBothBound() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        CompiledSql compiled = SqlTestSupport.compileWithReferenceCheck(
                referencePlan(catalog), SqlTestSupport.catalogWithAudienceMembership(), check(catalog));

        assertThat(compiled.parameters())
                .anySatisfy(parameter -> {
                    assertThat(parameter.type()).isEqualTo(SqlParameterType.CONTEXT_ID);
                    assertThat(parameter.canonicalValue()).isEqualTo(VERSION.versionId());
                })
                .anySatisfy(parameter -> {
                    assertThat(parameter.type()).isEqualTo(SqlParameterType.TEXT);
                    assertThat(parameter.canonicalValue()).isEqualTo(VERSION.versionHash().value());
                });
    }

    @Test
    void booleanCompositionAroundAReferenceIsPreservedExactly() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan negation = ReferenceQueryFixtures.plan(
                SegmentValidationFixtures.not("n-not", ReferenceQueryFixtures.reference("n-ref", VERSION)), catalog);
        SemanticQueryPlan conjunction = ReferenceQueryFixtures.plan(
                SegmentValidationFixtures.all("n-root", List.of(
                        SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                        ReferenceQueryFixtures.reference("n-ref", VERSION))), catalog);
        StorageCatalog storage = SqlTestSupport.catalogWithAudienceMembership();

        CompiledSql negated = SqlTestSupport.compileWithReferenceCheck(negation, storage, check(catalog));
        CompiledSql combined = SqlTestSupport.compileWithReferenceCheck(conjunction, storage, check(catalog));

        SqlExpression.And negatedRoot = (SqlExpression.And) negated.ast().where();
        assertThat(negatedRoot.operands().get(3)).isInstanceOf(SqlExpression.Not.class);
        assertThat(((SqlExpression.Not) negatedRoot.operands().get(3)).operand())
                .isInstanceOf(SqlExpression.Exists.class);
        assertThat(negated.sql().toUpperCase(Locale.ROOT)).contains("NOT EXISTS");

        SqlExpression.And combinedRoot = (SqlExpression.And) combined.ast().where();
        assertThat(combinedRoot.operands().get(3)).isInstanceOf(SqlExpression.And.class);
        assertThat(((SqlExpression.And) combinedRoot.operands().get(3)).operands())
                .hasSize(2)
                .anySatisfy(operand -> assertThat(operand).isInstanceOf(SqlExpression.Exists.class));
    }

    @Test
    void aReferencePlanCompilesDeterministically() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        StorageCatalog storage = SqlTestSupport.catalogWithAudienceMembership();
        ReferenceExecutionCheck referenceExecutionCheck = check(catalog);

        CompiledSql first = SqlTestSupport.compileWithReferenceCheck(referencePlan(catalog), storage, referenceExecutionCheck);
        CompiledSql second = SqlTestSupport.compileWithReferenceCheck(referencePlan(catalog), storage, referenceExecutionCheck);

        assertThat(second.sql()).isEqualTo(first.sql());
        assertThat(second.parameters()).isEqualTo(first.parameters());
        assertThat(second.compiledHash()).isEqualTo(first.compiledHash());
    }

    // ---------------------------------------------------------------- fails closed

    @Test
    void compilingAReferenceWithoutTheExecutionCheckFailsClosed() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = referencePlan(catalog);
        StorageCatalog storage = SqlTestSupport.catalogWithAudienceMembership();

        ContractAssertions.assertFailsWithCode(
                () -> SqlTestSupport.compile(plan, storage, (FxRateBinding) null),
                SqlErrorCodes.SQL_REFERENCE_GUARD_REQUIRED);
        assertThat(plan.hasAudienceReferences()).isTrue();
    }

    @Test
    void aCatalogWithoutTheAudienceMembershipBindingFailsClosed() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);

        ContractAssertions.assertFailsWithCode(
                () -> SqlTestSupport.compileWithReferenceCheck(referencePlan(catalog), SqlTestSupport.standardCatalog(), check(catalog)),
                SqlErrorCodes.SQL_MAPPING_MISSING);
    }

    @Test
    void theGuardRunsBeforeAnyLoweringSoDriftProducesNoArtifact() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = referencePlan(catalog);
        catalog.updateAvailability(VERSION,
                com.company.campaign.segment.reference.AudienceVersionAvailability.DELETED);
        StorageCatalog storage = SqlTestSupport.catalogWithAudienceMembership();

        ContractAssertions.assertFailsWithCode(
                () -> SqlTestSupport.compileWithReferenceCheck(plan, storage, check(catalog)),
                QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED);
    }

    // ---------------------------------------------------------------- no-reference compatibility

    @Test
    void aReferenceFreePlanCompilesIdenticallyWithAndWithoutTheExecutionCheck() {
        SemanticQueryPlan plan = com.company.campaign.query.testsupport.QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        StorageCatalog storage = SqlTestSupport.standardCatalog();

        CompiledSql without = SqlTestSupport.compile(plan, storage, SqlTestSupport.cnyFx());
        CompiledSql with = SqlTestSupport.compileWithReferenceCheck(
                plan, storage, SqlTestSupport.cnyFx(), check(catalog));

        assertThat(with.sql()).isEqualTo(without.sql());
        assertThat(with.parameters()).isEqualTo(without.parameters());
        assertThat(with.compiledHash()).isEqualTo(without.compiledHash());
    }

    // ---------------------------------------------------------------- injection boundary

    @Test
    void aPinCarryingStatementTextIsRejectedBeforeItCanBecomeAReference() {
        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.version("audience-vip", "version-1; DROP TABLE audience_members"),
                com.company.campaign.segment.reference.ReferenceErrorCodes.INPUT_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.version("audience-vip' OR '1'='1", "version-1"),
                com.company.campaign.segment.reference.ReferenceErrorCodes.INPUT_INVALID);
    }
}