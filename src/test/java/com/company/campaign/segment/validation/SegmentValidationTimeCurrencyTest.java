package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.segment.canonical.CanonicalNode;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirements 6, 7 and 8: time windows, currency/decimal semantics and aggregate empty-set behavior. */
class SegmentValidationTimeCurrencyTest {

    private static String hash(Predicate root) {
        SegmentValidationResult result = SegmentValidationFixtures.validate(root);
        assertThat(result.valid()).as("expected valid document, issues: %s",
                SegmentValidationFixtures.codes(result)).isTrue();
        return result.canonicalAstHash().value();
    }

    // ------------------------------------------------------------------ windows

    @Test
    void aWindowKindTheMetricDoesNotSupportIsRejected() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.metric(
                "n-m", "order.count", SegmentValidationFixtures.rolling(30, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.value(new IntegerValue(1))));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.WINDOW_UNSUPPORTED);
    }

    @Test
    void fixedWindowBoundariesRepresentingTheSameInstantsAreCanonicallyIdentical() {
        Predicate offsetForm = SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount",
                SegmentValidationFixtures.fixedWindow(
                        "2026-01-01T08:00:00+08:00", "2026-02-01T08:00:00+08:00"),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("1000.00", "CNY")));
        Predicate utcForm = SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount",
                SegmentValidationFixtures.fixedWindow("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("1000.00", "CNY")));

        assertThat(hash(offsetForm)).isEqualTo(hash(utcForm));
    }

    @Test
    void timestampOperandsRepresentingTheSameInstantAreCanonicallyIdentical() {
        Predicate offsetForm = SegmentValidationFixtures.attribute(
                "n", "member.last_seen", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(
                        SegmentValidationFixtures.dateTimeValue("2026-01-01T08:00:00+08:00")));
        Predicate utcForm = SegmentValidationFixtures.attribute(
                "n", "member.last_seen", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(
                        SegmentValidationFixtures.dateTimeValue("2026-01-01T00:00:00Z")));

        assertThat(hash(offsetForm)).isEqualTo(hash(utcForm));
    }

    private static Predicate rollingSpend(int amount, WindowUnit unit) {
        return SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.rolling(amount, unit),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("1000.00", "CNY")));
    }

    @Test
    void oneRollingWeekAndSevenRollingDaysAreTheSameCanonicalDuration() {
        // v1.3 defines WEEK as exactly 7 days, so the two declarations are one semantic duration.
        assertThat(hash(rollingSpend(1, WindowUnit.WEEK)))
                .isEqualTo(hash(rollingSpend(7, WindowUnit.DAY)));
    }

    @Test
    void equivalentRollingDurationsProduceByteIdenticalCanonicalOutput() {
        byte[] week = SegmentValidationFixtures.canonicalBytes(
                SegmentValidationFixtures.validate(rollingSpend(1, WindowUnit.WEEK)));
        byte[] days = SegmentValidationFixtures.canonicalBytes(
                SegmentValidationFixtures.validate(rollingSpend(7, WindowUnit.DAY)));

        assertThat(week).isEqualTo(days);
    }

    @Test
    void everyRollingWeekAmountEqualsItsSevenDayMultiple() {
        for (int amount : new int[] {2, 3, 10, 90, 14_285}) {
            assertThat(hash(rollingSpend(amount, WindowUnit.WEEK)))
                    .as("ROLLING %d WEEK must equal ROLLING %d DAY", amount, amount * 7)
                    .isEqualTo(hash(rollingSpend(amount * 7, WindowUnit.DAY)));
        }
    }

    @Test
    void semanticallyDifferentRollingDurationsRemainDifferent() {
        assertThat(hash(rollingSpend(1, WindowUnit.WEEK)))
                .isNotEqualTo(hash(rollingSpend(6, WindowUnit.DAY)))
                .isNotEqualTo(hash(rollingSpend(8, WindowUnit.DAY)));
        assertThat(hash(rollingSpend(2, WindowUnit.WEEK)))
                .isNotEqualTo(hash(rollingSpend(13, WindowUnit.DAY)));
        assertThat(hash(rollingSpend(2, WindowUnit.DAY)))
                .isNotEqualTo(hash(rollingSpend(3, WindowUnit.DAY)));
    }

    @Test
    void theMaximumRollingAmountIsCanonicalizedWithoutOverflow() {
        SegmentValidationResult result =
                SegmentValidationFixtures.validate(rollingSpend(
                        com.company.campaign.segment.dsl.contract.SegmentDslLimits.MAX_WINDOW_AMOUNT,
                        WindowUnit.WEEK));

        assertThat(result.valid()).as("issues: %s", SegmentValidationFixtures.codes(result)).isTrue();
        CanonicalNode.Metric metric = (CanonicalNode.Metric) result.canonicalSegment().predicate();
        assertThat(metric.window()).isEqualTo(
                new com.company.campaign.segment.canonical.CanonicalWindow.RollingWindow(700_000L));
    }

    @Test
    void aLifetimeWindowIsAcceptedAndCarriesNoBoundedInterval() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("1000.00", "CNY"))));

        assertThat(result.valid()).isTrue();
        CanonicalNode node = result.canonicalSegment().predicate();
        assertThat(((CanonicalNode.Metric) node).window())
                .isEqualTo(new com.company.campaign.segment.canonical.CanonicalWindow.LifetimeWindow());
    }

    // ------------------------------------------------------------------ currency and decimals

    @Test
    void moneyOperandsAreRejectedForANonMoneyDefinition() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.attribute(
                "n", "member.score", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new MoneyValue("1.00", "CNY"))));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE);
    }

    @Test
    void nonMoneyOperandsAreRejectedForAMoneyDefinition() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.attribute(
                "n", "member.balance", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("1.00"))));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.VALUE_TYPE_INCOMPATIBLE);
    }

    @Test
    void moneyValuesWithDifferentCurrenciesInsideOneListAreRejected() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.attribute(
                "n", "member.balance", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new MoneyValue("1.00", "CNY"), new MoneyValue("1.00", "USD"))));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.CURRENCY_MISMATCH);
    }

    @Test
    void moneyBoundsWithDifferentCurrenciesInsideOneRangeAreRejected() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.attribute(
                "n", "member.balance", ComparisonOperator.BETWEEN,
                SegmentValidationFixtures.range(new MoneyValue("1.00", "CNY"), new MoneyValue("9.00", "USD"),
                        true, true)));

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.CURRENCY_MISMATCH);
    }

    @Test
    void aCurrencyThatIsNotTheReportingCurrencyIsAcceptedBecauseNoConversionIsAssumed() {
        // The fixture context reports CNY. A USD operand is a valid MONEY operand; converting it is a plan
        // concern that T07 must not silently perform.
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.attribute(
                "n", "member.balance", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("10.00", "USD"))));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void decimalScaleDoesNotChangeTheCanonicalMeaning() {
        Predicate plain = SegmentValidationFixtures.attribute(
                "n", "member.balance", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new MoneyValue("1000", "CNY")));
        Predicate oneDecimal = SegmentValidationFixtures.attribute(
                "n", "member.balance", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new MoneyValue("1000.0", "CNY")));
        Predicate twoDecimals = SegmentValidationFixtures.attribute(
                "n", "member.balance", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new MoneyValue("1000.00", "CNY")));

        assertThat(hash(plain)).isEqualTo(hash(oneDecimal)).isEqualTo(hash(twoDecimals));
    }

    @Test
    void decimalScaleDoesNotChangeTheCanonicalMeaningForADecimalDefinition() {
        Predicate plain = SegmentValidationFixtures.attribute(
                "n", "member.score", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("12.50")));
        Predicate trimmed = SegmentValidationFixtures.attribute(
                "n", "member.score", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.decimalValue("12.5")));

        assertThat(hash(plain)).isEqualTo(hash(trimmed));
    }

    // ------------------------------------------------------------------ aggregate empty sets

    @Test
    void everyAggregateEmptySetDeclarationSurvivesCanonicalizationDistinctly() {
        List<Predicate> metrics = List.of(
                SegmentValidationFixtures.metric("n", "order.count", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN, SegmentValidationFixtures.value(new IntegerValue(0))),
                SegmentValidationFixtures.metric("n", "order.net_paid_amount", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(new MoneyValue("1.00", "CNY"))),
                SegmentValidationFixtures.metric("n", "order.avg_amount", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(new MoneyValue("1.00", "CNY"))),
                SegmentValidationFixtures.metric("n", "order.min_amount", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(new MoneyValue("1.00", "CNY"))),
                SegmentValidationFixtures.metric("n", "order.max_amount", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(new MoneyValue("1.00", "CNY"))));

        List<String> hashes = metrics.stream().map(SegmentValidationTimeCurrencyTest::hash).toList();
        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void theCanonicalMetricPinsAggregationAndEmptySetBehavior() {
        CanonicalNode count = SegmentValidationFixtures.validate(SegmentValidationFixtures.metric(
                "n", "order.count", SegmentValidationFixtures.lifetime(), ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new IntegerValue(0)))).canonicalSegment().predicate();
        CanonicalNode min = SegmentValidationFixtures.validate(SegmentValidationFixtures.metric(
                "n", "order.min_amount", SegmentValidationFixtures.lifetime(), ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(new MoneyValue("1.00", "CNY")))).canonicalSegment().predicate();

        assertThat(((CanonicalNode.Metric) count).definition().aggregation()).isEqualTo(AggregateFunction.COUNT);
        assertThat(((CanonicalNode.Metric) count).definition().emptySetBehavior()).isEqualTo(EmptySetBehavior.ZERO);
        assertThat(((CanonicalNode.Metric) min).definition().aggregation()).isEqualTo(AggregateFunction.MIN);
        assertThat(((CanonicalNode.Metric) min).definition().emptySetBehavior()).isEqualTo(EmptySetBehavior.NULL);
    }

    @Test
    void stringSemanticsNormalizeStringsBeforeHashing() {
        // member.name is NFKC + case-insensitive, so "GOLD" and "gold" share one canonical meaning.
        Predicate upper = SegmentValidationFixtures.attribute(
                "n", "member.name", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new StringValue("GOLD")));
        Predicate lower = SegmentValidationFixtures.attribute(
                "n", "member.name", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(new StringValue("gold")));

        assertThat(hash(upper)).isEqualTo(hash(lower));
    }

    @Test
    void anEnumCodeIsNeverMatchedThroughItsDisplayLabel() {
        // A display label is not a machine code, so T05 already refuses to build an ENUM value from it.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> SegmentValidationFixtures.tierEquals("n", "Gold"))
                .isInstanceOf(com.company.campaign.segment.dsl.contract.SegmentDslException.class);

        // An uppercase code that is not a declared member is refused by T07 against the bound definition.
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.tierEquals("n", "PLATINUM"));
        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.ENUM_CODE_UNKNOWN);
    }

    @Test
    void anEnumListIsOrderInsensitiveAndItsCodesAreChecked() {
        Predicate ordered = SegmentValidationFixtures.attribute("n", "member.tier", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new EnumValue("GOLD"), new EnumValue("SILVER")));
        Predicate reversed = SegmentValidationFixtures.attribute("n", "member.tier", ComparisonOperator.IN,
                SegmentValidationFixtures.list(new EnumValue("SILVER"), new EnumValue("GOLD")));

        assertThat(hash(ordered)).isEqualTo(hash(reversed));
    }
}