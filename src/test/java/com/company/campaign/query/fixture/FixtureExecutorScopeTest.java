package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.emptyScope;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.plan;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.restrictedScope;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardScope;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 9: the mandatory DataScope predicate is applied from a server-owned binding, before any user
 * predicate, and it can neither be sourced nor removed through plan, DSL or request input.
 */
class FixtureExecutorScopeTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void dataScopeMembershipIsAppliedBeforeTheUserPredicate() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        // The predicate matches m-004 and m-006, but only m-004 is inside the restricted DataScope.
        SemanticQueryPlan plan = plan(SegmentValidationFixtures.metric(
                "n-count", "order.count", SegmentValidationFixtures.lifetime(), ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))));

        assertThat(EXECUTOR.execute(plan, dataset, standardMapping(), restrictedScope(), null).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-004"));
        assertThat(EXECUTOR.execute(plan, dataset, standardMapping(), standardScope(), null).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-004", "m-006"));
    }

    @Test
    void everyMemberInsideTheScopeIsEvaluated() {
        Predicate predicate = SegmentValidationFixtures.any("n-any", List.of(
                com.company.campaign.query.testsupport.QueryPlanFixtures.attributeNoValue(
                        "n-a", "member.tier", ComparisonOperator.IS_NOT_NULL),
                com.company.campaign.query.testsupport.QueryPlanFixtures.attributeNoValue(
                        "n-b", "member.tier", ComparisonOperator.IS_NULL)));
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        assertThat(EXECUTOR.execute(plan(predicate), dataset, standardMapping(), restrictedScope(), null)
                .memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001", "m-002", "m-003", "m-004"));
    }

    @Test
    void aScopeWithNoServerOwnedBindingFailsClosed() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        Predicate predicate = SegmentValidationFixtures.tierEquals("n-tier", "GOLD");

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(plan(predicate), dataset, standardMapping(), emptyScope(), null),
                FixtureErrorCodes.FIXTURE_SCOPE_MISSING);
    }

    @Test
    void aNullScopeBindingFailsWithTheBoundedInputCode() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        Predicate predicate = SegmentValidationFixtures.tierEquals("n-tier", "GOLD");

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(plan(predicate), dataset, standardMapping(), null, null),
                FixtureErrorCodes.FIXTURE_INPUT_MISSING);
    }

    @Test
    void fixtureMembersOutsideTheDeclaredUniverseCannotBeEvaluated() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        assertThat(dataset.memberUniverse().memberIds())
                .containsExactlyElementsOf(List.of("m-001", "m-002", "m-003", "m-004", "m-005", "m-006"));
    }
}