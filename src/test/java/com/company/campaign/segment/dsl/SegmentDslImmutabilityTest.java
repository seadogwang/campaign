package com.company.campaign.segment.dsl;

import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.TypedValueCondition;
import com.company.campaign.segment.dsl.testsupport.SegmentDslFixtures;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every reachable collection in a published or parsed document is an immutable defensive copy. */
class SegmentDslImmutabilityTest {

    private final SegmentDslCodec codec = new SegmentDslCodec();

    @Test
    void everyReachableCollectionInTheDocumentIsUnmodifiable() {
        Predicate root = SegmentDslFixtures.document().predicate();
        assertUnmodifiableCollections(root);
        assertUnmodifiableCollections(codec.readPredicate(codec.writePredicateString(root)));
    }

    @Test
    void sourceListMutationCannotReachAPublishedDocument() {
        List<Predicate> children = new ArrayList<>(List.of(
                SegmentDslFixtures.tierEqualsGold("n-a"), SegmentDslFixtures.tierEqualsGold("n-b")));
        GroupPredicate group = new GroupPredicate(
                "n-root", com.company.campaign.segment.dsl.predicate.PredicateType.ALL, children);
        var document = SegmentDslFixtures.publish(group);

        children.clear();

        assertThat(((GroupPredicate) document.predicate()).children())
                .containsExactly(SegmentDslFixtures.tierEqualsGold("n-a"), SegmentDslFixtures.tierEqualsGold("n-b"));
    }

    @Test
    void sourceListValueMutationCannotReachAPublishedDocument() {
        List<ScalarValue> items = new ArrayList<>(List.of(new EnumValue("GOLD"), new EnumValue("SILVER")));
        ListValue list = new ListValue(items);
        var predicate = new com.company.campaign.segment.dsl.predicate.AttributePredicate(
                "n-a", "member.tier", com.company.campaign.metadata.domain.ComparisonOperator.IN,
                ConditionValue.of(list));
        var document = SegmentDslFixtures.publish(predicate);

        items.add(new EnumValue("BRONZE"));

        ListValue published = (ListValue) ((TypedValueCondition) predicate.condition()).value();
        assertThat(published.items()).containsExactly(new EnumValue("GOLD"), new EnumValue("SILVER"));
        assertThat(document.predicate()).isEqualTo(predicate);
    }

    private static void assertUnmodifiableCollections(Predicate node) {
        switch (node) {
            case GroupPredicate group -> {
                assertThatThrownBy(() -> group.children().add(SegmentDslFixtures.tierEqualsGold("n-x")))
                        .isInstanceOf(UnsupportedOperationException.class);
                group.children().forEach(SegmentDslImmutabilityTest::assertUnmodifiableCollections);
            }
            case NotPredicate not -> assertUnmodifiableCollections(not.child());
            case ExistsPredicate exists -> assertUnmodifiableCollections(exists.predicate());
            case com.company.campaign.segment.dsl.predicate.AttributePredicate attribute ->
                    assertCondition(attribute.condition());
            case com.company.campaign.segment.dsl.predicate.MetricPredicate metric ->
                    assertCondition(metric.condition());
            case com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate calculation -> {
                assertMetricExpression(calculation.expression());
                assertCondition(calculation.condition());
            }
            // A reference is an immutable leaf over one pin; it holds no collection that could be mutated.
            case com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate ignored -> {
            }
        }
    }

    private static void assertMetricExpression(
            com.company.campaign.segment.dsl.metric.MetricExpression expression) {
        switch (expression) {
            case com.company.campaign.segment.dsl.metric.MetricExpression.MetricRef ignored -> {
            }
            case com.company.campaign.segment.dsl.metric.MetricExpression.Add add -> {
                assertThatThrownBy(() -> add.operands().add(
                        new com.company.campaign.segment.dsl.metric.MetricExpression.MetricRef("order.count")))
                        .isInstanceOf(UnsupportedOperationException.class);
                add.operands().forEach(SegmentDslImmutabilityTest::assertMetricExpression);
            }
            case com.company.campaign.segment.dsl.metric.MetricExpression.Subtract subtract -> {
                assertMetricExpression(subtract.left());
                assertMetricExpression(subtract.right());
            }
            case com.company.campaign.segment.dsl.metric.MetricExpression.Multiply multiply -> {
                assertMetricExpression(multiply.left());
                assertMetricExpression(multiply.right());
            }
            case com.company.campaign.segment.dsl.metric.MetricExpression.Divide divide -> {
                assertMetricExpression(divide.left());
                assertMetricExpression(divide.right());
            }
        }
    }

    private static void assertCondition(ConditionValue condition) {
        if (condition instanceof TypedValueCondition present
                && present.value() instanceof ListValue list) {
            assertThatThrownBy(() -> list.items().add(new StringValue("X")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
