package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirements 13, 14, 15 and 16: canonical equivalence, canonical difference and no business rewrites. */
class SegmentCanonicalizationEquivalenceTest {

    private static SegmentValidationResult validate(Predicate root) {
        SegmentValidationResult result = SegmentValidationFixtures.validate(root);
        assertThat(result.valid()).as("expected valid document, issues: %s",
                SegmentValidationFixtures.codes(result)).isTrue();
        return result;
    }

    private static String hash(Predicate root) {
        return validate(root).canonicalAstHash().value();
    }

    private static byte[] bytes(Predicate root) {
        return new CanonicalSegmentCodec().write(validate(root).canonicalSegment());
    }

    private static Predicate tier(String nodeId, String code) {
        return SegmentValidationFixtures.tierEquals(nodeId, code);
    }

    // ------------------------------------------------------------------ equivalences

    @Test
    void commutativeChildOrderProducesByteIdenticalCanonicalOutput() {
        Predicate forward = SegmentValidationFixtures.all("n-root", List.of(
                tier("n-a", "GOLD"),
                SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30))),
                SegmentValidationFixtures.attribute("n-c", "member.score", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("12.5")))));
        Predicate reversed = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.attribute("n-c", "member.score", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("12.5"))),
                SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30))),
                tier("n-a", "GOLD")));

        assertThat(hash(forward)).isEqualTo(hash(reversed));
        assertThat(Arrays.equals(bytes(forward), bytes(reversed))).isTrue();
    }

    @Test
    void nodeIdChangesDoNotChangeTheCanonicalBytesOrHash() {
        Predicate first = SegmentValidationFixtures.all("n-root", List.of(
                tier("n-a", "GOLD"),
                SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30)))));
        Predicate renamed = SegmentValidationFixtures.all("n-other-root", List.of(
                tier("n-renamed-a", "GOLD"),
                SegmentValidationFixtures.attribute("n-renamed-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30)))));

        assertThat(hash(first)).isEqualTo(hash(renamed));
        assertThat(Arrays.equals(bytes(first), bytes(renamed))).isTrue();
    }

    @Test
    void listOrderAndDecimalScaleDoNotChangeTheCanonicalBytesOrHash() {
        Predicate first = SegmentValidationFixtures.attribute("n", "member.balance", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new MoneyValue("1.0", "CNY"), new MoneyValue("2.00", "CNY")));
        Predicate second = SegmentValidationFixtures.attribute("n", "member.balance", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new MoneyValue("2", "CNY"), new MoneyValue("1.00", "CNY")));

        assertThat(hash(first)).isEqualTo(hash(second));
    }

    // ------------------------------------------------------------------ semantic duplicates

    @Test
    void semanticallyDuplicateSiblingsWithDifferentNodeIdsAreRejected() {
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                tier("n-a", "GOLD"),
                tier("n-b", "GOLD")));

        SegmentValidationResult result = SegmentValidationFixtures.validate(root);

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.SEMANTIC_DUPLICATE_SIBLING);
    }

    @Test
    void semanticallyDuplicateListItemsAreRejected() {
        Predicate root = SegmentValidationFixtures.attribute("n", "member.tier", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new EnumValue("GOLD"), new EnumValue("GOLD")));

        assertThat(SegmentValidationFixtures.codes(SegmentValidationFixtures.validate(root)))
                .contains(SegmentValidationErrorCodes.SEMANTIC_DUPLICATE_LIST_ITEM);
    }

    @Test
    void duplicateNodeIdsRemainAT05FailureBeforeT07Runs() {
        assertThatThrownBy(() -> SegmentValidationFixtures.publish(SegmentValidationFixtures.all("n-root", List.of(
                tier("n-same", "GOLD"),
                tier("n-same", "SILVER")))))
                .isInstanceOf(SegmentDslException.class)
                .hasFieldOrPropertyWithValue("code",
                        com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes.DUPLICATE_NODE_ID);
    }

    // ------------------------------------------------------------------ differences

    @Test
    void groupingChangesTheCanonicalHash() {
        Predicate flat = SegmentValidationFixtures.all("n-root", List.of(
                tier("n-a", "GOLD"),
                SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30))),
                SegmentValidationFixtures.attribute("n-c", "member.score", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("12.5")))));
        Predicate grouped = SegmentValidationFixtures.all("n-root", List.of(
                tier("n-a", "GOLD"),
                SegmentValidationFixtures.all("n-inner", List.of(
                        SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30))),
                        SegmentValidationFixtures.attribute("n-c", "member.score", ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("12.5")))))));

        assertThat(hash(flat)).isNotEqualTo(hash(grouped));
    }

    @Test
    void nullInclusionChangesTheCanonicalHash() {
        Predicate withoutNull = tier("n", "GOLD");
        Predicate withNull = SegmentValidationFixtures.any("n-root", List.of(
                tier("n", "GOLD"),
                SegmentValidationFixtures.attribute("n-null", "member.tier", ComparisonOperator.IS_NULL,
                        ConditionValue.none())));

        assertThat(hash(withoutNull)).isNotEqualTo(hash(withNull));
    }

    @Test
    void rangeInclusivityChangesTheCanonicalHash() {
        Predicate inclusive = SegmentValidationFixtures.attribute("n", "member.age", ComparisonOperator.BETWEEN,
                SegmentValidationFixtures.range(SegmentValidationFixtures.integerValue(18),
                        SegmentValidationFixtures.integerValue(65), true, true));
        Predicate exclusiveUpper = SegmentValidationFixtures.attribute("n", "member.age", ComparisonOperator.BETWEEN,
                SegmentValidationFixtures.range(SegmentValidationFixtures.integerValue(18),
                        SegmentValidationFixtures.integerValue(65), true, false));

        assertThat(hash(inclusive)).isNotEqualTo(hash(exclusiveUpper));
    }

    @Test
    void rangeBoundRolesArePreservedExactly() {
        SegmentValidationResult result = validate(SegmentValidationFixtures.attribute(
                "n", "member.age", ComparisonOperator.BETWEEN,
                SegmentValidationFixtures.range(SegmentValidationFixtures.integerValue(18),
                        SegmentValidationFixtures.integerValue(65), true, false)));

        com.company.campaign.segment.canonical.CanonicalNode node = result.canonicalSegment().predicate();
        com.company.campaign.segment.canonical.CanonicalValue.RangeValue range =
                (com.company.campaign.segment.canonical.CanonicalValue.RangeValue)
                        ((com.company.campaign.segment.canonical.CanonicalNode.Attribute) node).value();
        assertThat(range.lower()).isEqualTo(
                new com.company.campaign.segment.canonical.CanonicalValue.IntegerValue(18));
        assertThat(range.upper()).isEqualTo(
                new com.company.campaign.segment.canonical.CanonicalValue.IntegerValue(65));
        assertThat(range.includeLower()).isTrue();
        assertThat(range.includeUpper()).isFalse();
    }

    @Test
    void currencyChangesTheCanonicalHash() {
        Predicate cny = SegmentValidationFixtures.attribute("n", "member.balance", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new MoneyValue("10", "CNY")));
        Predicate usd = SegmentValidationFixtures.attribute("n", "member.balance", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new MoneyValue("10", "USD")));

        assertThat(hash(cny)).isNotEqualTo(hash(usd));
    }

    @Test
    void windowChangesTheCanonicalHash() {
        Predicate rolling = SegmentValidationFixtures.metric("n", "order.net_paid_amount",
                SegmentValidationFixtures.rolling(30, WindowUnit.DAY), ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("10", "CNY")));
        Predicate lifetime = SegmentValidationFixtures.metric("n", "order.net_paid_amount",
                SegmentValidationFixtures.lifetime(), ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("10", "CNY")));

        assertThat(hash(rolling)).isNotEqualTo(hash(lifetime));
    }

    @Test
    void existsScopeChangesTheCanonicalHash() {
        Predicate orders = SegmentValidationFixtures.exists("n", "member.orders",
                SegmentValidationFixtures.lifetime(), SegmentValidationFixtures.attribute(
                        "n-channel", "order.channel", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(new EnumValue("ONLINE"))));
        Predicate lines = SegmentValidationFixtures.exists("n", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.exists("n-inner", "order.lines", SegmentValidationFixtures.lifetime(),
                        SegmentValidationFixtures.attribute("n-channel", "order.channel", ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(new EnumValue("ONLINE")))));

        assertThat(hash(orders)).isNotEqualTo(hash(lines));
    }

    // ------------------------------------------------------------------ no business-changing rewrites

    @Test
    void noDeMorganRewriteIsApplied() {
        Predicate negatedAny = SegmentValidationFixtures.not("n-not", SegmentValidationFixtures.any("n-any", List.of(
                tier("n-a", "GOLD"),
                SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30))))));
        Predicate distributedAll = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.not("n-not-a", tier("n-a", "GOLD")),
                SegmentValidationFixtures.not("n-not-b", SegmentValidationFixtures.attribute(
                        "n-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30))))));

        assertThat(hash(negatedAny)).isNotEqualTo(hash(distributedAll));
    }

    @Test
    void noGroupFlatteningOccurs() {
        Predicate nested = SegmentValidationFixtures.all("n-root", List.of(
                tier("n-a", "GOLD"),
                SegmentValidationFixtures.all("n-inner", List.of(
                        SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30)))))));
        Predicate flat = SegmentValidationFixtures.all("n-root", List.of(
                tier("n-a", "GOLD"),
                SegmentValidationFixtures.attribute("n-b", "member.age", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(30)))));

        assertThat(hash(nested)).isNotEqualTo(hash(flat));
    }

    @Test
    void separateExistsNodesAreNeverMerged() {
        Predicate twoExists = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.exists("n-one", "member.orders", SegmentValidationFixtures.lifetime(),
                        SegmentValidationFixtures.attribute("n-channel-1", "order.channel",
                                ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(new EnumValue("ONLINE")))),
                SegmentValidationFixtures.exists("n-two", "member.orders", SegmentValidationFixtures.lifetime(),
                        SegmentValidationFixtures.attribute("n-channel-2", "order.channel",
                                ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(new EnumValue("STORE"))))));
        Predicate oneExists = SegmentValidationFixtures.exists("n-one", "member.orders",
                SegmentValidationFixtures.lifetime(), SegmentValidationFixtures.all("n-all", List.of(
                        SegmentValidationFixtures.attribute("n-channel-1", "order.channel",
                                ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(new EnumValue("ONLINE"))),
                        SegmentValidationFixtures.attribute("n-channel-1b", "order.channel",
                                ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(new EnumValue("STORE"))))));

        assertThat(hash(twoExists)).isNotEqualTo(hash(oneExists));
    }

    @Test
    void aCanonicalRangeNeverAppearsAsAListAndViceVersa() {
        SegmentValidationResult listResult = validate(SegmentValidationFixtures.attribute(
                "n", "member.age", ComparisonOperator.IN,
                SegmentValidationFixtures.list(SegmentValidationFixtures.integerValue(1),
                        SegmentValidationFixtures.integerValue(2))));
        SegmentValidationResult rangeResult = validate(SegmentValidationFixtures.attribute(
                "n", "member.age", ComparisonOperator.BETWEEN,
                SegmentValidationFixtures.range(SegmentValidationFixtures.integerValue(1),
                        SegmentValidationFixtures.integerValue(2), true, true)));

        assertThat(((com.company.campaign.segment.canonical.CanonicalNode.Attribute)
                listResult.canonicalSegment().predicate()).value())
                .isInstanceOf(com.company.campaign.segment.canonical.CanonicalValue.ListValue.class);
        assertThat(((com.company.campaign.segment.canonical.CanonicalNode.Attribute)
                rangeResult.canonicalSegment().predicate()).value())
                .isInstanceOf(com.company.campaign.segment.canonical.CanonicalValue.RangeValue.class);
        assertThat(new RangeValue(SegmentValidationFixtures.integerValue(1),
                SegmentValidationFixtures.integerValue(2), true, true)).isNotNull();
    }
}