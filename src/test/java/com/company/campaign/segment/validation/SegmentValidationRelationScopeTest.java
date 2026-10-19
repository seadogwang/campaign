package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirements 9 and 10: EXISTS scope preservation, subject rules, unsupported nesting and depth limits. */
class SegmentValidationRelationScopeTest {

    private static Predicate amountGreaterThan(String nodeId, String amount) {
        return SegmentValidationFixtures.attribute(nodeId, "order.amount", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue(amount, "CNY")));
    }

    private static Predicate channelEquals(String nodeId, String channel) {
        return SegmentValidationFixtures.attribute(nodeId, "order.channel", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new EnumValue(channel)));
    }

    private static String hash(Predicate root) {
        SegmentValidationResult result = SegmentValidationFixtures.validate(root);
        assertThat(result.valid()).as("expected valid document, issues: %s",
                SegmentValidationFixtures.codes(result)).isTrue();
        return result.canonicalAstHash().value();
    }

    @Test
    void oneExistsWithTwoPredicatesDiffersFromTwoExistsNodes() {
        Predicate oneExists = SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                SegmentValidationFixtures.all("n-all", List.of(
                        amountGreaterThan("n-amount", "100"),
                        channelEquals("n-channel", "ONLINE"))));

        Predicate twoExists = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.exists("n-exists-1", "member.orders",
                        SegmentValidationFixtures.rolling(30, WindowUnit.DAY), amountGreaterThan("n-amount", "100")),
                SegmentValidationFixtures.exists("n-exists-2", "member.orders",
                        SegmentValidationFixtures.rolling(30, WindowUnit.DAY), channelEquals("n-channel", "ONLINE"))));

        assertThat(hash(oneExists)).isNotEqualTo(hash(twoExists));
    }

    @Test
    void sameRowScopeIsNeverFlattenedIntoIndependentExistsNodes() {
        Predicate sameRow = SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.all("n-all", List.of(
                        amountGreaterThan("n-amount", "100"),
                        channelEquals("n-channel", "ONLINE"))));

        Predicate flattened = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.exists("n-exists-1", "member.orders", SegmentValidationFixtures.lifetime(),
                        amountGreaterThan("n-amount", "100")),
                SegmentValidationFixtures.exists("n-exists-2", "member.orders", SegmentValidationFixtures.lifetime(),
                        channelEquals("n-channel", "ONLINE"))));

        assertThat(hash(sameRow)).isNotEqualTo(hash(flattened));
    }

    @Test
    void theCanonicalExistsKeepsItsChildBeneathItsOwnBoundary() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                channelEquals("n-channel", "ONLINE")));

        assertThat(result.canonicalSegment().predicate())
                .isInstanceOf(com.company.campaign.segment.canonical.CanonicalNode.Exists.class);
    }

    @Test
    void aRelationWhoseSourceDoesNotMatchTheRootSubjectIsRejected() {
        Predicate exists = SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                channelEquals("n-channel", "ONLINE"));
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(SubjectKind.ORDER, exists));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.SUBJECT_MISMATCH);
    }

    @Test
    void aNestedAttributeMustMatchTheRelationTargetSubject() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.attribute("n-tier", "member.tier", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(new EnumValue("GOLD")))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.SUBJECT_MISMATCH);
    }

    @Test
    void aNestedAttributeMatchingTheRelationTargetSubjectIsAccepted() {
        assertThat(hash(SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                channelEquals("n-channel", "ONLINE")))).isNotBlank();
    }

    @Test
    void aMetricNestedInsideAnExistsIsRejectedRatherThanGuessed() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.metric("n-metric", "order.count", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.value(new IntegerValue(1)))));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.NESTED_METRIC_UNSUPPORTED);
    }

    @Test
    void aBoundedRelationWindowRequiresARelationEventTimeCapability() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.exists(
                "n-exists", "member.orders_no_time", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                channelEquals("n-channel", "ONLINE")));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.RELATION_EVENT_TIME_REQUIRED);
    }

    @Test
    void aLifetimeRelationWindowNeedsNoEventTimeCapability() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.exists(
                "n-exists", "member.orders_no_time", SegmentValidationFixtures.lifetime(),
                channelEquals("n-channel", "ONLINE")));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void relationNestingBeyondThePolicyIsRejected() {
        Predicate nested = SegmentValidationFixtures.exists(
                "n-outer", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.exists("n-inner", "order.lines",
                        SegmentValidationFixtures.lifetime(), channelEquals("n-channel", "ONLINE")));

        SegmentValidationPolicy shallow = new SegmentValidationPolicy(100, 32, 64, 256, 1);
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(nested), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(), SegmentValidationFixtures.allowAll(), shallow);

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.COMPLEXITY_RELATION_DEPTH_EXCEEDED);
    }

    @Test
    void aTwoLevelRelationNestingIsAcceptedByAStandardPolicy() {
        assertThat(hash(SegmentValidationFixtures.exists(
                "n-outer", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.exists("n-inner", "order.lines",
                        SegmentValidationFixtures.lifetime(), channelEquals("n-channel", "ONLINE")))))
                .isNotBlank();
    }
}