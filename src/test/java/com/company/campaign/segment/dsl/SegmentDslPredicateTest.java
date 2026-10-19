package com.company.campaign.segment.dsl;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateNodeIds;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T05 structural rules for the closed predicate union: node identity, arity, defensive copies and the
 * operator/value shape matrix. Metadata compatibility and semantic validation are T07 obligations.
 */
class SegmentDslPredicateTest {

    private static AttributePredicate tierEqualsGold(String nodeId) {
        return new AttributePredicate(
                nodeId, "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD")));
    }

    @Test
    void everyPredicateReportsItsClosedDiscriminator() {
        assertThat(new GroupPredicate("n-all", PredicateType.ALL, List.of(tierEqualsGold("n-a"))).type())
                .isEqualTo(PredicateType.ALL);
        assertThat(new GroupPredicate("n-any", PredicateType.ANY, List.of(tierEqualsGold("n-a"))).type())
                .isEqualTo(PredicateType.ANY);
        assertThat(new NotPredicate("n-not", tierEqualsGold("n-a")).type()).isEqualTo(PredicateType.NOT);
        assertThat(tierEqualsGold("n-a").type()).isEqualTo(PredicateType.ATTRIBUTE);
        assertThat(new MetricPredicate(
                "n-metric", "order.net_paid_amount", new RollingWindow(30, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new MoneyValue("1000.00", "CNY"))).type())
                .isEqualTo(PredicateType.METRIC);
        assertThat(new MetricExpressionPredicate(
                "n-expression", new MetricExpression.MetricRef("order.count"), new LifetimeWindow(),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new IntegerValue(0))).type())
                .isEqualTo(PredicateType.METRIC_EXPRESSION);
        assertThat(new ExistsPredicate(
                "n-exists", "member.orders", new RollingWindow(30, WindowUnit.DAY), tierEqualsGold("n-a")).type())
                .isEqualTo(PredicateType.EXISTS);
        assertThat(new AudienceReferencePredicate(
                "n-ref", "audience-vip", "version-7",
                ContentHash.sha256("version-7".getBytes(java.nio.charset.StandardCharsets.UTF_8))).type())
                .isEqualTo(PredicateType.AUDIENCE_REFERENCE);
        assertThat(PredicateType.values()).hasSize(8);
    }

    @Test
    void groupIsNonEmptyAndDefensivelyCopiedInDeclaredOrder() {
        List<Predicate> source = new ArrayList<>(List.of(tierEqualsGold("n-a"), tierEqualsGold("n-b")));
        GroupPredicate group = new GroupPredicate("n-all", PredicateType.ALL, source);
        source.add(tierEqualsGold("n-c"));
        assertThat(group.children()).containsExactly(tierEqualsGold("n-a"), tierEqualsGold("n-b"));
        assertThatThrownBy(() -> group.children().add(tierEqualsGold("n-d")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void groupRejectsEmptyNullAndNullElementChildren() {
        assertFailsWithCode(
                () -> new GroupPredicate("n-all", PredicateType.ALL, List.of()),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> new GroupPredicate("n-all", PredicateType.ALL, null),
                SegmentDslErrorCodes.SHAPE_INVALID);
        List<Predicate> withNull = new ArrayList<>();
        withNull.add(tierEqualsGold("n-a"));
        withNull.add(null);
        assertFailsWithCode(
                () -> new GroupPredicate("n-all", PredicateType.ALL, withNull),
                SegmentDslErrorCodes.SHAPE_INVALID);
    }

    @Test
    void groupDiscriminatorIsRestrictedToAllAndAny() {
        for (PredicateType rejected : List.of(PredicateType.NOT, PredicateType.ATTRIBUTE, PredicateType.METRIC,
                PredicateType.METRIC_EXPRESSION, PredicateType.EXISTS)) {
            assertFailsWithCode(
                    () -> new GroupPredicate("n-all", rejected, List.of(tierEqualsGold("n-a"))),
                    SegmentDslErrorCodes.SHAPE_INVALID);
        }
    }

    @Test
    void nodeIdsUseTheBoundedReferenceGrammar() {
        for (String rejected : List.of("", " ", "n root", "-nroot", "n#root", "n".repeat(129))) {
            assertFailsWithCode(
                    () -> new NotPredicate(rejected, tierEqualsGold("n-a")),
                    SegmentDslErrorCodes.NODE_ID_INVALID);
        }
        assertFailsWithCode(
                () -> new NotPredicate(null, tierEqualsGold("n-a")),
                SegmentDslErrorCodes.NODE_ID_INVALID);
        assertThat(new AttributePredicate(
                "n-root_1.x:y", "member.tier", ComparisonOperator.EQUAL,
                ConditionValue.of(new EnumValue("GOLD"))).nodeId()).isEqualTo("n-root_1.x:y");
    }

    @Test
    void definitionReferencesUseTheBoundedReferenceGrammar() {
        assertFailsWithCode(
                () -> new AttributePredicate(
                        "n-a", "member tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD"))),
                SegmentDslErrorCodes.REFERENCE_ID_INVALID);
        assertFailsWithCode(
                () -> new AttributePredicate(
                        "n-a", null, ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD"))),
                SegmentDslErrorCodes.REFERENCE_ID_INVALID);
        assertFailsWithCode(
                () -> new MetricPredicate(
                        "n-a", "order net", new LifetimeWindow(), ComparisonOperator.EQUAL,
                        ConditionValue.of(new IntegerValue(1L))),
                SegmentDslErrorCodes.REFERENCE_ID_INVALID);
        assertFailsWithCode(
                () -> new ExistsPredicate("n-a", "member orders", new LifetimeWindow(), tierEqualsGold("n-b")),
                SegmentDslErrorCodes.REFERENCE_ID_INVALID);
    }

    @Test
    void notRequiresExactlyOneChild() {
        assertThat(new NotPredicate("n-not", tierEqualsGold("n-a")).child()).isEqualTo(tierEqualsGold("n-a"));
        assertFailsWithCode(
                () -> new NotPredicate("n-not", null),
                SegmentDslErrorCodes.SHAPE_INVALID);
    }

    @Test
    void metricAndExistsRequireAWindowAndExistsRequiresANestedPredicate() {
        assertFailsWithCode(
                () -> new MetricPredicate(
                        "n-m", "order.net_paid_amount", null, ComparisonOperator.EQUAL,
                        ConditionValue.of(new IntegerValue(1L))),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> new ExistsPredicate("n-e", "member.orders", null, tierEqualsGold("n-a")),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> new ExistsPredicate(
                        "n-e", "member.orders", new LifetimeWindow(), null),
                SegmentDslErrorCodes.SHAPE_INVALID);
    }

    @Test
    void duplicateNodeIdsFailAcrossBranchesAndInsideExists() {
        // Duplicate across two sibling branches of an ALL group.
        Predicate acrossBranches = new GroupPredicate(
                "n-root", PredicateType.ALL,
                List.of(tierEqualsGold("dup"), tierEqualsGold("dup")));
        assertFailsWithCode(() -> PredicateNodeIds.requireUnique(acrossBranches), SegmentDslErrorCodes.DUPLICATE_NODE_ID);

        // The same identity rule holds for ANY.
        Predicate acrossAny = new GroupPredicate(
                "n-root", PredicateType.ANY,
                List.of(tierEqualsGold("dup"), tierEqualsGold("dup")));
        assertFailsWithCode(() -> PredicateNodeIds.requireUnique(acrossAny), SegmentDslErrorCodes.DUPLICATE_NODE_ID);

        // A duplicate shared between a sibling metric and an attribute branch also fails.
        Predicate acrossKinds = new GroupPredicate(
                "n-root", PredicateType.ALL,
                List.of(
                        tierEqualsGold("dup"),
                        new MetricPredicate(
                                "dup", "order.net_paid_amount", new RollingWindow(30, WindowUnit.DAY),
                                ComparisonOperator.GREATER_THAN, ConditionValue.of(new MoneyValue("1.00", "CNY")))));
        assertFailsWithCode(() -> PredicateNodeIds.requireUnique(acrossKinds), SegmentDslErrorCodes.DUPLICATE_NODE_ID);

        // Duplicate between the EXISTS node and a node nested inside it.
        Predicate insideExists = new ExistsPredicate(
                "dup", "member.orders", new LifetimeWindow(),
                new GroupPredicate("n-nested", PredicateType.ALL, List.of(tierEqualsGold("dup"))));
        assertFailsWithCode(() -> PredicateNodeIds.requireUnique(insideExists), SegmentDslErrorCodes.DUPLICATE_NODE_ID);

        // A NOT wrapping a node that repeats the NOT's own ID.
        assertFailsWithCode(
                () -> PredicateNodeIds.requireUnique(new NotPredicate("dup", tierEqualsGold("dup"))),
                SegmentDslErrorCodes.DUPLICATE_NODE_ID);

        // Unique IDs are accepted and never renumbered.
        Predicate unique = new GroupPredicate(
                "n-root", PredicateType.ALL, List.of(tierEqualsGold("n-a"), tierEqualsGold("n-b")));
        PredicateNodeIds.requireUnique(unique);
        assertThat(((GroupPredicate) unique).children())
                .containsExactly(tierEqualsGold("n-a"), tierEqualsGold("n-b"));
    }

    @Test
    void operatorValueMatrixEnforcesNullListRangeAndScalarShapes() {
        // NULL operators carry no value and reject every supplied value.
        assertThat(new AttributePredicate(
                "n-a", "member.tier", ComparisonOperator.IS_NULL, ConditionValue.none()).condition())
                .isEqualTo(ConditionValue.none());
        assertFailsWithCode(
                () -> new AttributePredicate(
                        "n-a", "member.tier", ComparisonOperator.IS_NULL,
                        ConditionValue.of(new EnumValue("GOLD"))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> new AttributePredicate(
                        "n-a", "member.tier", ComparisonOperator.IS_NOT_NULL, ConditionValue.of(new IntegerValue(1L))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);

        // Scalar operators require a scalar and reject a missing value or a LIST/RANGE.
        assertFailsWithCode(
                () -> new AttributePredicate("n-a", "member.tier", ComparisonOperator.EQUAL, ConditionValue.none()),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> new AttributePredicate(
                        "n-a", "member.tier", ComparisonOperator.EQUAL,
                        ConditionValue.of(new ListValue(List.of(new EnumValue("GOLD"))))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> new AttributePredicate(
                        "n-a", "member.tier", ComparisonOperator.EQUAL,
                        ConditionValue.of(new RangeValue(new IntegerValue(1L), new IntegerValue(2L), true, true))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertThat(new AttributePredicate(
                "n-a", "member.tier", ComparisonOperator.STARTS_WITH,
                ConditionValue.of(new StringValue("GO"))).type()).isEqualTo(PredicateType.ATTRIBUTE);
    }

    @Test
    void listAndRangeOperatorsRequireTheirExactShapes() {
        ListValue list = new ListValue(List.of(new EnumValue("GOLD"), new EnumValue("SILVER")));
        for (ComparisonOperator operator : List.of(ComparisonOperator.IN, ComparisonOperator.NOT_IN)) {
            assertThat(new AttributePredicate("n-a", "member.tier", operator, ConditionValue.of(list)).condition())
                    .isEqualTo(ConditionValue.of(list));
            assertFailsWithCode(
                    () -> new AttributePredicate(
                            "n-a", "member.tier", operator, ConditionValue.of(new EnumValue("GOLD"))),
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
            assertFailsWithCode(
                    () -> new AttributePredicate("n-a", "member.tier", operator, ConditionValue.none()),
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        }

        RangeValue range = new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false);
        assertThat(new AttributePredicate(
                "n-a", "member.tier", ComparisonOperator.BETWEEN, ConditionValue.of(range)).condition())
                .isEqualTo(ConditionValue.of(range));
        assertFailsWithCode(
                () -> new AttributePredicate(
                        "n-a", "member.tier", ComparisonOperator.BETWEEN, ConditionValue.of(new IntegerValue(5L))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> new AttributePredicate("n-a", "member.tier", ComparisonOperator.BETWEEN, ConditionValue.none()),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
    }

    @Test
    void existsPreservesNestedSameRowStructureAndSeparateNodesStayOrdered() {
        AttributePredicate amount = new AttributePredicate(
                "n-amount", "order.amount", ComparisonOperator.GREATER_THAN,
                ConditionValue.of(new MoneyValue("100.00", "CNY")));
        AttributePredicate channel = new AttributePredicate(
                "n-channel", "order.channel", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("ONLINE")));
        ExistsPredicate first = new ExistsPredicate(
                "n-order-1", "member.orders", new RollingWindow(30, WindowUnit.DAY),
                new GroupPredicate("n-order-all", PredicateType.ALL, List.of(amount, channel)));
        ExistsPredicate second = new ExistsPredicate(
                "n-order-2", "member.orders", new RollingWindow(7, WindowUnit.DAY), tierEqualsGold("n-tier"));

        Predicate root = new GroupPredicate("n-root", PredicateType.ANY, List.of(first, second));
        PredicateNodeIds.requireUnique(root);

        // The two EXISTS nodes remain separate and in the declared order; nothing is flattened or combined.
        assertThat(((GroupPredicate) root).children()).containsExactly(first, second);
        assertThat(((GroupPredicate) first.predicate()).children()).containsExactly(amount, channel);
    }

    @Test
    void conditionValueDistinguishesAbsentFromPresentWithoutARawEscape() {
        assertThat(ConditionValue.none()).isInstanceOf(ConditionValue.class);
        assertThat(ConditionValue.of(new IntegerValue(1L))).isInstanceOf(ConditionValue.class);
        assertThat(ConditionValue.class.getPermittedSubclasses()).hasSize(2);
        ScalarValue scalar = new IntegerValue(1L);
        assertThat(ConditionValue.of(scalar)).isNotEqualTo(ConditionValue.none());
    }
}
