package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.cnyFx;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.plan;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardScope;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3, 4 and 6: exact full member sets for every accepted Attribute operator and type, exact
 * three-valued NULL behavior, exact boolean grouping, and the {@code A AND (B OR C)} /
 * {@code (A AND B) OR C} distinction.
 */
class FixtureExecutorAttributeTest {

    @TempDir
    Path repositoryRoot;

    private static final MemberIdSet ALL = MemberIdSet.of(
            "m-001", "m-002", "m-003", "m-004", "m-005", "m-006");

    @Test
    void enumEqualityMatchesExactlyAndNullIsNotADefault() {
        assertThat(run(SegmentValidationFixtures.attribute(
                "n-tier", "member.tier", ComparisonOperator.EQUAL, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.enumValue("GOLD")))))
                .isEqualTo(MemberIdSet.of("m-001", "m-003", "m-006"));
    }

    @Test
    void notEqualAndNotInExcludeNull() {
        assertThat(run(SegmentValidationFixtures.attribute(
                "n-tier", "member.tier", ComparisonOperator.NOT_EQUAL, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.enumValue("GOLD")))))
                .isEqualTo(MemberIdSet.of("m-002", "m-004"));

        assertThat(run(SegmentValidationFixtures.attribute(
                "n-tier", "member.tier", ComparisonOperator.NOT_IN, SegmentValidationFixtures.list(
                        SegmentValidationFixtures.enumValue("GOLD"), SegmentValidationFixtures.enumValue("SILVER")))))
                .isEqualTo(MemberIdSet.of());
    }

    @Test
    void explicitNullInclusiveStructureIncludesTheNullMember() {
        Predicate predicate = SegmentValidationFixtures.any("n-any", List.of(
                SegmentValidationFixtures.attribute(
                        "n-tier", "member.tier", ComparisonOperator.NOT_EQUAL, SegmentValidationFixtures.value(
                                SegmentValidationFixtures.enumValue("GOLD"))),
                QueryPlanFixtures.attributeNoValue("n-null", "member.tier", ComparisonOperator.IS_NULL)));

        assertThat(run(predicate)).isEqualTo(MemberIdSet.of("m-002", "m-004", "m-005"));
    }

    @Test
    void notOfEqualityPreservesUnknownRatherThanIncludingNull() {
        Predicate predicate = SegmentValidationFixtures.not("n-not", SegmentValidationFixtures.attribute(
                "n-tier", "member.tier", ComparisonOperator.EQUAL, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.enumValue("GOLD"))));

        assertThat(run(predicate)).isEqualTo(MemberIdSet.of("m-002", "m-004"));
    }

    @Test
    void isNullAndIsNotNullAreExactComplements() {
        assertThat(run(QueryPlanFixtures.attributeNoValue("n-null", "member.tier", ComparisonOperator.IS_NULL)))
                .isEqualTo(MemberIdSet.of("m-005"));
        assertThat(run(QueryPlanFixtures.attributeNoValue("n-null", "member.tier", ComparisonOperator.IS_NOT_NULL)))
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-003", "m-004", "m-006"));
    }

    @Test
    void listMembershipMatchesExactly() {
        assertThat(run(SegmentValidationFixtures.attribute(
                "n-tier", "member.tier", ComparisonOperator.IN, SegmentValidationFixtures.list(
                        SegmentValidationFixtures.enumValue("GOLD"), SegmentValidationFixtures.enumValue("SILVER")))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-003", "m-004", "m-006"));
    }

    @Test
    void rangeBoundariesIncludeOrExcludeTheirEndsExactly() {
        assertThat(run(SegmentValidationFixtures.attribute(
                "n-age", "member.age", ComparisonOperator.BETWEEN, SegmentValidationFixtures.range(
                        SegmentValidationFixtures.integerValue(30), SegmentValidationFixtures.integerValue(40),
                        true, true))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-006"));

        assertThat(run(SegmentValidationFixtures.attribute(
                "n-age", "member.age", ComparisonOperator.BETWEEN, SegmentValidationFixtures.range(
                        SegmentValidationFixtures.integerValue(30), SegmentValidationFixtures.integerValue(40),
                        true, false))))
                .isEqualTo(MemberIdSet.of("m-001", "m-006"));
    }

    @Test
    void stringOperatorsApplyTheDefinitionOwnedCaseAndUnicodeRules() {
        assertThat(run(SegmentValidationFixtures.attribute(
                "n-name", "member.name", ComparisonOperator.CONTAINS, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.stringValue("A")))))
                .isEqualTo(MemberIdSet.of("m-001", "m-003", "m-004"));

        // The stored full-width "Ｅｒｉｃ" only matches after NFKC normalization and case folding.
        assertThat(run(SegmentValidationFixtures.attribute(
                "n-name", "member.name", ComparisonOperator.STARTS_WITH, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.stringValue("E")))))
                .isEqualTo(MemberIdSet.of("m-006"));

        assertThat(run(SegmentValidationFixtures.attribute(
                "n-name", "member.name", ComparisonOperator.ENDS_WITH, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.stringValue("E")))))
                .isEqualTo(MemberIdSet.of("m-001", "m-004"));

        assertThat(run(SegmentValidationFixtures.attribute(
                "n-name", "member.name", ComparisonOperator.NOT_CONTAINS, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.stringValue("A")))))
                .isEqualTo(MemberIdSet.of("m-002", "m-006"));
    }

    @Test
    void booleanDecimalDateAndTimestampComparisonsAreExact() {
        assertThat(run(SegmentValidationFixtures.attribute(
                "n-flag", "member.flag", ComparisonOperator.EQUAL, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.booleanValue(true)))))
                .isEqualTo(MemberIdSet.of("m-001", "m-004", "m-006"));

        assertThat(run(SegmentValidationFixtures.attribute(
                "n-score", "member.score", ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.decimalValue("3")))))
                .isEqualTo(MemberIdSet.of("m-003", "m-005", "m-006"));

        assertThat(run(SegmentValidationFixtures.attribute(
                "n-joined", "member.joined_on", ComparisonOperator.LESS_THAN, SegmentValidationFixtures.value(
                        SegmentValidationFixtures.dateValue("2026-03-01")))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002"));

        assertThat(run(SegmentValidationFixtures.attribute(
                "n-seen", "member.last_seen", ComparisonOperator.GREATER_OR_EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.dateTimeValue("2026-09-29T00:00:00Z")))))
                .isEqualTo(MemberIdSet.of("m-001", "m-002"));
    }

    @Test
    void aAndBOrCDiffersFromAAndBOrC() {
        Predicate a = SegmentValidationFixtures.attribute(
                "n-a", "member.tier", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD")));
        Predicate b = SegmentValidationFixtures.attribute(
                "n-b", "member.flag", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.booleanValue(true)));
        Predicate c = SegmentValidationFixtures.attribute(
                "n-c", "member.tier", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("SILVER")));

        MemberIdSet andGroup = run(SegmentValidationFixtures.all(
                "n-and", List.of(a, SegmentValidationFixtures.any("n-or", List.of(b, c)))));
        MemberIdSet orGroup = run(SegmentValidationFixtures.any(
                "n-or", List.of(SegmentValidationFixtures.all("n-and", List.of(a, b)), c)));

        assertThat(andGroup).isEqualTo(MemberIdSet.of("m-001", "m-006"));
        assertThat(orGroup).isEqualTo(MemberIdSet.of("m-001", "m-002", "m-004", "m-006"));
        assertThat(andGroup).isNotEqualTo(orGroup);
    }

    @Test
    void nestedNotIsNotRewritten() {
        Predicate predicate = SegmentValidationFixtures.not("n-not", SegmentValidationFixtures.any("n-any", List.of(
                SegmentValidationFixtures.attribute(
                        "n-tier", "member.tier", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD"))),
                SegmentValidationFixtures.attribute(
                        "n-flag", "member.flag", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.booleanValue(true))))));

        assertThat(run(predicate)).isEqualTo(MemberIdSet.of("m-002"));
    }

    @Test
    void aNullMonetaryValueComparesAsUnknownUnlessNullIsExplicitlyTested() {
        FixtureDataset dataset = load(repositoryRoot, com.company.campaign.query.fixture.testsupport
                .FixtureTestSupport.balanceFreePayload(),
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.BALANCE_FREE_UNIVERSE);
        SemanticQueryPlan plan = plan(SegmentValidationFixtures.attribute(
                "n-balance", "member.balance", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0.00", "CNY"))));

        FixtureExecutionResult result = EXECUTOR.execute(
                plan, dataset, standardMapping(), standardScope(), cnyFx("7.00"));

        // On the balance-free payload the single all-NULL column matches no value comparison, and IS_NULL
        // still finds that row explicitly.
        assertThat(result.memberIdSet()).isEqualTo(MemberIdSet.of());
        assertThat(EXECUTOR.execute(
                plan(QueryPlanFixtures.attributeNoValue("n-balance", "member.balance", ComparisonOperator.IS_NULL)),
                dataset, standardMapping(), standardScope(), cnyFx("7.00")).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001"));
        // On the standard payload only m-004 has a NULL balance; m-005 stores an explicit 500.00 CNY.
        assertThat(run(QueryPlanFixtures.attributeNoValue(
                "n-balance", "member.balance", ComparisonOperator.IS_NULL)))
                .isEqualTo(MemberIdSet.of("m-004"));
    }

    @Test
    void anUnmappedDefinitionFailsClosed() {
        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(
                        plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD")),
                        load(repositoryRoot, standardPayload()),
                        FixtureMapping.empty("fixture-mapping-empty"),
                        standardScope(),
                        null),
                FixtureErrorCodes.FIXTURE_MAPPING_MISSING);
    }

    @Test
    void everyMemberIsEvaluatedAgainstTheFullDataScopeByDefault() {
        Predicate predicate = SegmentValidationFixtures.any("n-any", List.of(
                QueryPlanFixtures.attributeNoValue("n-a", "member.tier", ComparisonOperator.IS_NOT_NULL),
                QueryPlanFixtures.attributeNoValue("n-b", "member.tier", ComparisonOperator.IS_NULL)));

        assertThat(run(predicate)).isEqualTo(ALL);
    }

    private MemberIdSet run(Predicate predicate) {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        FixtureExecutionResult result = EXECUTOR.execute(
                plan(predicate), dataset, standardMapping(), standardScope(), cnyFx("7.00"));
        return result.memberIdSet();
    }
}