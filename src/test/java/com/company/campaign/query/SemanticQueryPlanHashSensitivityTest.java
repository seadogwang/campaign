package com.company.campaign.query;

import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.SemanticQueryPlanCodec;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 6: the plan hash changes when any execution-relevant input changes, and identical inputs stay
 * identical. Nothing random, current-time, object-identity or display-only ever enters the material.
 */
class SemanticQueryPlanHashSensitivityTest {

    private static final SemanticQueryPlanCodec CODEC = new SemanticQueryPlanCodec();

    private static void assertDifferent(SemanticQueryPlan left, SemanticQueryPlan right) {
        assertThat(left.planHash()).as("plan hash must change").isNotEqualTo(right.planHash());
        assertThat(CODEC.write(left)).isNotEqualTo(CODEC.write(right));
    }

    @Test
    void operatorChangeChangesTheHash() {
        SemanticQueryPlan equal = QueryPlanFixtures.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD"));
        SemanticQueryPlan notEqual = QueryPlanFixtures.plan(SegmentValidationFixtures.attribute(
                "n-tier", "member.tier", com.company.campaign.metadata.domain.ComparisonOperator.NOT_EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD"))));
        assertDifferent(equal, notEqual);
    }

    @Test
    void operandChangeChangesTheHash() {
        SemanticQueryPlan alice = QueryPlanFixtures.plan(SegmentValidationFixtures.attribute(
                "n-name", "member.name", com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("alice"))));
        SemanticQueryPlan bob = QueryPlanFixtures.plan(SegmentValidationFixtures.attribute(
                "n-name", "member.name", com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("bob"))));
        assertDifferent(alice, bob);
    }

    @Test
    void nullSemanticsChangeChangesTheHash() {
        SemanticQueryPlan isNull = QueryPlanFixtures.plan(QueryPlanFixtures.attributeNoValue(
                "n-name", "member.name", com.company.campaign.metadata.domain.ComparisonOperator.IS_NULL));
        SemanticQueryPlan equals = QueryPlanFixtures.plan(SegmentValidationFixtures.attribute(
                "n-name", "member.name", com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("alice"))));
        assertDifferent(isNull, equals);
    }

    @Test
    void aggregateAndEmptySetBehaviorChangeTheHash() {
        SemanticQueryPlan sum = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        SemanticQueryPlan avg = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.avg_amount", SegmentValidationFixtures.lifetime(),
                com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        assertDifferent(sum, avg);
    }

    @Test
    void relationScopeAndTimeBoundaryChangeTheHash() {
        SemanticQueryPlan orders = QueryPlanFixtures.plan(SegmentValidationFixtures.exists(
                "n-e", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.attribute("n-c", "order.channel",
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")))));
        SemanticQueryPlan ordersNoTime = QueryPlanFixtures.plan(SegmentValidationFixtures.exists(
                "n-e", "member.orders_no_time", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.attribute("n-c", "order.channel",
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")))));
        assertDifferent(orders, ordersNoTime);

        SemanticQueryPlan thirtyDays = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        SemanticQueryPlan ninetyDays = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.rolling(90, WindowUnit.DAY),
                com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
        assertDifferent(thirtyDays, ninetyDays);
    }

    @Test
    void metadataContentPinChangeChangesTheHashWithoutChangingTheUserTree() {
        SemanticQueryPlan base = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());

        var snapshot = QueryPlanFixtures.snapshotWithExtraAttribute();
        var document = QueryPlanFixtures.rebind(SegmentValidationFixtures.validDocument(), snapshot);
        SemanticQueryPlan repinned = QueryPlanFixtures.plan(
                document, snapshot, SegmentValidationFixtures.context());

        assertThat(repinned.predicateRoot()).isEqualTo(base.predicateRoot());
        assertThat(repinned.metadataContentHash()).isNotEqualTo(base.metadataContentHash());
        assertDifferent(base, repinned);
    }

    @Test
    void reportingCurrencyAndTrustedScopeChangeTheHash() {
        SemanticQueryPlan cny = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        TrustedExecutionContext usd = QueryPlanFixtures.context(
                "tenant-001", "program-001", "scope-008", "membership-1", "user-001",
                QueryPlanFixtures.AS_OF, QueryPlanFixtures.WATERMARK, java.time.ZoneId.of("Asia/Shanghai"), "USD",
                MetadataFixtures.allPermissions());
        SemanticQueryPlan converted = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(), usd);
        assertDifferent(cny, converted);

        SemanticQueryPlan otherScope = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                QueryPlanFixtures.context("tenant-001", "program-001", "scope-009"));
        assertDifferent(cny, otherScope);
    }

    @Test
    void plannerAndCompilerVersionsArePartOfTheHashMaterial() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        String wire = CODEC.writeString(plan);
        assertThat(wire)
                .contains(plan.plannerVersion())
                .contains(plan.compilerVersion())
                .contains(plan.queryAstVersion())
                .contains(plan.planFormatVersion())
                .contains(plan.dslSchemaVersion().value())
                .contains(plan.canonicalizerVersion());
        // The T18 Existing Audience Reference slice moved every plan discriminator, because the closed AST
        // union, the planner, the canonical-to-AST projection and the plan document all gained a domain.
        assertThat(List.of(plan.plannerVersion(), plan.compilerVersion(), plan.queryAstVersion()))
                .containsExactly(
                        "segment-query-planner/1.1", "segment-query-compiler/1.1", "segment-query-ast/1.1");
        assertThat(plan.planFormatVersion()).isEqualTo("1.1");
    }
}