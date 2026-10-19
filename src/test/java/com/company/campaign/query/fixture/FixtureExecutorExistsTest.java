package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXISTS_UNIVERSE;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.cnyFx;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.eventsMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.eventsPayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.plan;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardScope;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 5: one {@code EXISTS} evaluates every child predicate against the same related row, while two
 * separate {@code EXISTS} nodes may be satisfied by different rows — and exact resolved window boundaries.
 */
class FixtureExecutorExistsTest {

    @TempDir
    Path repositoryRoot;

    private static final MemberIdSet DATASET_UNIVERSE = EXISTS_UNIVERSE;

    @Test
    void oneExistsKeepsEveryPredicateOnTheSameRelatedRow() {
        Predicate predicate = SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.all("n-all", List.of(
                        amountGreaterThan("n-amount", "40"),
                        channelEquals("n-channel", "ONLINE"))));

        assertThat(executeOrders(predicate)).isEqualTo(MemberIdSet.of("m-002"));
    }

    @Test
    void twoSeparateExistsNodesMayBeSatisfiedByDifferentRows() {
        Predicate predicate = SegmentValidationFixtures.all("n-all", List.of(
                SegmentValidationFixtures.exists(
                        "n-exists-amount", "member.orders", SegmentValidationFixtures.lifetime(),
                        amountGreaterThan("n-amount", "40")),
                SegmentValidationFixtures.exists(
                        "n-exists-channel", "member.orders", SegmentValidationFixtures.lifetime(),
                        channelEquals("n-channel", "ONLINE"))));

        MemberIdSet split = executeOrders(predicate);

        assertThat(split).isEqualTo(MemberIdSet.of("m-002", "m-003"));
        assertThat(split).isNotEqualTo(MemberIdSet.of("m-002"));
    }

    @Test
    void aRollingWindowUsesItsResolvedStartInclusiveAndEndExclusiveBoundaries() {
        Predicate predicate = SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.rolling(1, WindowUnit.DAY),
                amountGreaterThan("n-amount", "40"));

        // o-102 sits exactly on the resolved start instant and is included; o-104 sits exactly on the resolved
        // end instant and is excluded, so neither the wall clock nor a guessed boundary can explain the result.
        assertThat(executeOrders(predicate)).isEqualTo(MemberIdSet.of("m-002", "m-003"));
    }

    @Test
    void aLifetimeWindowIsBoundedByTheDataWatermark() {
        Predicate predicate = SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                amountGreaterThan("n-amount", "40"));

        assertThat(executeOrders(predicate)).isEqualTo(MemberIdSet.of("m-002", "m-003"));
    }

    @Test
    void aFixedWindowUsesItsExactDeclaredInstants() {
        Predicate predicate = SegmentValidationFixtures.exists(
                "n-exists", "member.orders",
                SegmentValidationFixtures.fixedWindow("2026-09-30T00:00:00Z", "2026-10-01T00:00:00Z"),
                amountGreaterThan("n-amount", "40"));

        assertThat(executeOrders(predicate)).isEqualTo(MemberIdSet.of("m-002", "m-003"));
    }

    @Test
    void anEventsRelationIsEvaluatedWithItsOwnJoinAndEventTime() {
        FixtureDataset dataset = load(repositoryRoot, eventsPayload(), DATASET_UNIVERSE,
                com.company.campaign.evaluation.segment.contract.FixtureRole.PRIMARY,
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.FIXTURE_ID);
        com.company.campaign.metadata.domain.MetadataSnapshot snapshot =
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.eventsSnapshot();

        SemanticQueryPlan loginPlan = plan(snapshot, SegmentValidationFixtures.exists(
                "n-exists", "member.events", SegmentValidationFixtures.lifetime(),
                eventTypeEquals("n-type", "LOGIN")));
        assertThat(EXECUTOR.execute(loginPlan, dataset, eventsMapping(), standardScope(), null).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001", "m-002"));

        SemanticQueryPlan rollingPlan = plan(snapshot, SegmentValidationFixtures.exists(
                "n-exists", "member.events", SegmentValidationFixtures.rolling(1, WindowUnit.DAY),
                eventTypeEquals("n-type", "PURCHASE")));
        assertThat(EXECUTOR.execute(rollingPlan, dataset, eventsMapping(), standardScope(), null).memberIdSet())
                .isEqualTo(MemberIdSet.of("m-001"));
    }

    private MemberIdSet executeOrders(Predicate predicate) {
        FixtureDataset dataset = load(repositoryRoot,
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.existsPayload(),
                DATASET_UNIVERSE, com.company.campaign.evaluation.segment.contract.FixtureRole.PRIMARY,
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.FIXTURE_ID);
        return EXECUTOR.execute(
                plan(predicate), dataset, standardMapping(), standardScope(), cnyFx("7.00")).memberIdSet();
    }

    private static Predicate amountGreaterThan(String nodeId, String threshold) {
        return SegmentValidationFixtures.attribute(
                nodeId, "order.amount", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue(threshold, "CNY")));
    }

    private static Predicate channelEquals(String nodeId, String code) {
        return SegmentValidationFixtures.attribute(
                nodeId, "order.channel", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue(code)));
    }

    private static Predicate eventTypeEquals(String nodeId, String code) {
        return SegmentValidationFixtures.attribute(
                nodeId, "event.type", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue(code)));
    }
}