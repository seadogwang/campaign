package com.company.campaign.segment.dsl;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.value.ValueKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T05 representation rules for the closed {@code TypedValue} union. T07 owns semantic normalization and
 * element type compatibility; these tests cover only structure, lexical form and defensive bounds.
 */
class SegmentDslTypedValueTest {

    @Test
    void everyKindReportsItsClosedDiscriminator() {
        assertThat(new StringValue("SG").kind()).isEqualTo(ValueKind.STRING);
        assertThat(new IntegerValue(3L).kind()).isEqualTo(ValueKind.INTEGER);
        assertThat(new DecimalValue("1000.00").kind()).isEqualTo(ValueKind.DECIMAL);
        assertThat(new BooleanValue(true).kind()).isEqualTo(ValueKind.BOOLEAN);
        assertThat(new DateValue("2026-01-01").kind()).isEqualTo(ValueKind.DATE);
        assertThat(new DateTimeValue("2026-01-01T00:00:00+08:00").kind()).isEqualTo(ValueKind.DATETIME);
        assertThat(new EnumValue("GOLD").kind()).isEqualTo(ValueKind.ENUM);
        assertThat(new MoneyValue("1000.00", "CNY").kind()).isEqualTo(ValueKind.MONEY);
        assertThat(new ListValue(List.of(new StringValue("A"))).kind()).isEqualTo(ValueKind.LIST);
        assertThat(new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false).kind())
                .isEqualTo(ValueKind.RANGE);
        assertThat(ValueKind.values()).hasSize(10);
    }

    @Test
    void stringValuePreservesTextAndAllowsExplicitEmptyLexeme() {
        assertThat(new StringValue("SG").value()).isEqualTo("SG");
        // Empty is a deliberate, meaningful lexeme: it is distinct from the NULL operators' absent value.
        assertThat(new StringValue("").value()).isEmpty();
    }

    @Test
    void stringValueRejectsNullAndOversizedText() {
        assertFailsWithCode(() -> new StringValue(null), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> new StringValue("x".repeat(SegmentDslLimits.MAX_STRING_LENGTH + 1)),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
    }

    @Test
    void stringValueRejectsUnpairedSurrogatesAndPreservesSupplementaryCodePoints() {
        // An unpaired UTF-16 surrogate is not a valid Unicode scalar value; canonical UTF-8 encoding would
        // silently replace it, so it must be refused instead of stored.
        assertFailsWithCode(() -> new StringValue("\uD800"), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(() -> new StringValue("a\uD800b"), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(() -> new StringValue("\uDC00"), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(() -> new StringValue("\uD83D"), SegmentDslErrorCodes.TYPED_VALUE_INVALID);

        // A well-formed pair for a supplementary code point stays valid and round-trips byte-for-byte.
        String emoji = "😀";
        assertThat(new StringValue(emoji).value()).isEqualTo(emoji);
        assertThat(new ListValue(List.of(new StringValue(emoji))).items()).containsExactly(new StringValue(emoji));
    }

    @Test
    void integerValueAcceptsSignedInt64Boundaries() {
        assertThat(new IntegerValue(Long.MAX_VALUE).value()).isEqualTo(Long.MAX_VALUE);
        assertThat(new IntegerValue(Long.MIN_VALUE).value()).isEqualTo(Long.MIN_VALUE);
        assertThat(new IntegerValue(0L).value()).isZero();
    }

    @Test
    void decimalValuePreservesEditableLexicalScale() {
        // T07 owns decimal normalization, so the editable document keeps the exact declared scale.
        assertThat(new DecimalValue("1000.00").value()).isEqualTo("1000.00");
        assertThat(new DecimalValue("-0.50").value()).isEqualTo("-0.50");
        assertThat(new DecimalValue("1000").value()).isEqualTo("1000");
        assertThat(new DecimalValue("0").value()).isEqualTo("0");
    }

    @Test
    void decimalValueRejectsExponentNanInfinityLocaleAndMalformedForms() {
        for (String rejected : List.of(
                "1E3", "1e-3", "NaN", "Infinity", "-Infinity", "1,000.00", ".5", "5.", "+5",
                "--5", "007", "", " ", "1.2.3", "0x10", "١٢٣")) {
            assertFailsWithCode(() -> new DecimalValue(rejected), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void decimalValueRejectsOutOfBoundsMagnitude() {
        assertFailsWithCode(
                () -> new DecimalValue("1." + "0".repeat(SegmentDslLimits.MAX_DECIMAL_SCALE + 1)),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
        assertFailsWithCode(
                () -> new DecimalValue("1" + "0".repeat(SegmentDslLimits.MAX_DECIMAL_PRECISION)),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
        assertFailsWithCode(
                () -> new DecimalValue("1".repeat(SegmentDslLimits.MAX_DECIMAL_TEXT_LENGTH + 1)),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
    }

    @Test
    void booleanValueIsAJsonBooleanOnly() {
        assertThat(new BooleanValue(true).value()).isTrue();
        assertThat(new BooleanValue(false).value()).isFalse();
    }

    @Test
    void dateValueRequiresAnExactIsoLocalDate() {
        assertThat(new DateValue("2026-01-01").value()).isEqualTo("2026-01-01");
        assertThat(new DateValue("2024-02-29").value()).isEqualTo("2024-02-29");
        for (String rejected : List.of("2026-1-1", "20260101", "2026-13-01", "2026-02-30", "2026-01-01T00:00:00Z", "")) {
            assertFailsWithCode(() -> new DateValue(rejected), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void dateValueRejectsLexicalWidthsAndInvalidCalendarDays() {
        for (String rejected : List.of(
                "+2026-01-01", "12026-01-01", "2026-1-01", "2026-01-1", "2026-1-1",
                "2026-00-10", "2026-13-01", "2026-04-31", "2026-02-29",
                "2026-01-01 ", " 2026-01-01", "2026/01/01", "2026-01-011")) {
            assertFailsWithCode(() -> new DateValue(rejected), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void dateTimeValueRequiresAnExplicitNumericOffsetOrZ() {
        assertThat(new DateTimeValue("2026-01-01T00:00:00+08:00").value())
                .isEqualTo("2026-01-01T00:00:00+08:00");
        assertThat(new DateTimeValue("2026-01-01T00:00:00Z").value()).isEqualTo("2026-01-01T00:00:00Z");
        for (String rejected : List.of(
                "2026-01-01T00:00:00", "2026-01-01T00:00:00+0800", "2026-01-01",
                "2026-01-01T00:00:00+08:00[Asia/Shanghai]", "2026-01-01 00:00:00+08:00", "")) {
            assertFailsWithCode(() -> new DateTimeValue(rejected), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void dateTimeValueAcceptsExactZOffsetsAndFractionalSeconds() {
        assertThat(new DateTimeValue("2026-01-01T00:00:00.250Z").value())
                .isEqualTo("2026-01-01T00:00:00.250Z");
        assertThat(new DateTimeValue("2026-01-01T00:00:00.123456789+08:00").value())
                .isEqualTo("2026-01-01T00:00:00.123456789+08:00");
        assertThat(new DateTimeValue("2026-01-01T23:59:59-05:30").value())
                .isEqualTo("2026-01-01T23:59:59-05:30");
    }

    @Test
    void dateTimeValueRejectsEveryLexicalVariantOutsideTheSharedGrammar() {
        for (String rejected : List.of(
                "2026-01-01T00:00+08:00",              // optional seconds are not allowed
                "2026-01-01T00:00:00+0800",            // compact offset
                "2026-01-01T00:00:00",                 // offset-free local timestamp
                "2026-01-01T00:00:00+08:00[Asia/Shanghai]", // zone ID
                "2026-01-01t00:00:00z",                // lowercase t/z
                "2026-01-01T00:00:00Z ",               // trailing text
                "2026-01-01T00:00:00Zx",               // trailing text
                "2026-01-01T00:00:00.Z",               // empty fraction
                "2026-01-01T00:00:00.1234567890Z",     // fraction beyond the precision bound
                "2026-01-01T24:00:00Z",                // invalid hour
                "2026-01-01T00:00:60Z",                // invalid second
                "2026-02-30T00:00:00Z",                // invalid calendar day
                "2026-01-01T00:00:00+25:00",           // offset out of range
                "2026-01-01T00:00:00+18:30",           // offset out of range
                "12026-01-01T00:00:00Z",               // year width
                "+2026-01-01T00:00:00Z")) {            // year sign
            assertFailsWithCode(() -> new DateTimeValue(rejected), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void enumValueRequiresABoundedUppercaseMachineCode() {
        assertThat(new EnumValue("GOLD").value()).isEqualTo("GOLD");
        assertThat(new EnumValue("TIER_2").value()).isEqualTo("TIER_2");
        for (String rejected : List.of("gold", "Gold", "1GOLD", "", "GOLD-2", "x".repeat(65))) {
            assertFailsWithCode(() -> new EnumValue(rejected), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void moneyValueRequiresAPlainAmountAndTheIsoCurrencyGrammar() {
        MoneyValue money = new MoneyValue("1000.00", "CNY");
        assertThat(money.amount()).isEqualTo("1000.00");
        assertThat(money.currency()).isEqualTo("CNY");
        assertFailsWithCode(() -> new MoneyValue("1E3", "CNY"), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        for (String rejected : List.of("cny", "CN", "CNYY", "CN1", "")) {
            assertFailsWithCode(() -> new MoneyValue("10.00", rejected), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void listValueIsNonEmptyScalarOnlyAndDefensivelyCopied() {
        List<ScalarValue> source = new ArrayList<>(List.of(new StringValue("A"), new IntegerValue(1L)));
        ListValue list = new ListValue(source);
        source.add(new StringValue("B"));
        assertThat(list.items()).containsExactly(new StringValue("A"), new IntegerValue(1L));
        assertThatThrownBy(() -> list.items().add(new StringValue("C")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listValueRejectsEmptyNullElementAndOversizedItemCount() {
        assertFailsWithCode(() -> new ListValue(List.of()), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        List<ScalarValue> withNull = new ArrayList<>();
        withNull.add(new StringValue("A"));
        withNull.add(null);
        assertFailsWithCode(() -> new ListValue(withNull), SegmentDslErrorCodes.TYPED_VALUE_INVALID);

        List<ScalarValue> oversized = new ArrayList<>();
        for (int index = 0; index <= SegmentDslLimits.MAX_LIST_ITEMS; index++) {
            oversized.add(new IntegerValue(index));
        }
        assertFailsWithCode(() -> new ListValue(oversized), SegmentDslErrorCodes.LIMIT_EXCEEDED);
    }

    @Test
    void rangeValueRequiresScalarBoundsInclusionFlagsAndDefensiveCopy() {
        RangeValue range = new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false);
        assertThat(range.lower()).isEqualTo(new IntegerValue(1L));
        assertThat(range.upper()).isEqualTo(new IntegerValue(9L));
        assertThat(range.includeLower()).isTrue();
        assertThat(range.includeUpper()).isFalse();
        assertFailsWithCode(
                () -> new RangeValue(null, new IntegerValue(9L), true, false),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> new RangeValue(new IntegerValue(1L), null, true, false),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
    }

    @Test
    void rangeValueEnforcesRepresentationIntrinsicOrderingForComparableBounds() {
        // Same-kind numeric bounds have an intrinsic order, so an inverted or empty range is incoherent.
        assertFailsWithCode(
                () -> new RangeValue(new IntegerValue(9L), new IntegerValue(1L), true, true),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> new RangeValue(new IntegerValue(5L), new IntegerValue(5L), false, false),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertThat(new RangeValue(new IntegerValue(5L), new IntegerValue(5L), true, true).lower())
                .isEqualTo(new IntegerValue(5L));

        // Different kinds are a same-type question owned by T07, so T05 preserves the declared structure.
        assertThat(new RangeValue(new StringValue("A"), new IntegerValue(9L), true, true).upper())
                .isEqualTo(new IntegerValue(9L));
    }

    @Test
    void rangeValueComparesInstantsAndPlainDecimalsWhenKindsMatch() {
        assertFailsWithCode(
                () -> new RangeValue(
                        new DateTimeValue("2026-02-01T00:00:00Z"),
                        new DateTimeValue("2026-01-01T00:00:00Z"),
                        true, false),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> new RangeValue(new DecimalValue("9.0"), new DecimalValue("1.0"), true, false),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
    }

    @Test
    void rangeValueSkipsOrderingForNonIntrinsicKinds() {
        // STRING ordering depends on a collation chosen by Metadata; T05 must not guess it.
        assertThat(new RangeValue(new StringValue("Z"), new StringValue("A"), true, true).lower())
                .isEqualTo(new StringValue("Z"));
        // ENUM ordering depends on Metadata enum order.
        assertThat(new RangeValue(new EnumValue("GOLD"), new EnumValue("SILVER"), true, true).lower())
                .isEqualTo(new EnumValue("GOLD"));
        // MONEY cross-currency has no intrinsic order.
        assertThat(new RangeValue(new MoneyValue("9.00", "CNY"), new MoneyValue("1.00", "USD"), true, true).lower())
                .isEqualTo(new MoneyValue("9.00", "CNY"));
    }

    @Test
    void scalarValueUnionIsClosedToListsAndRanges() {
        // Compile-time proof that a scalar cannot be a LIST or RANGE: this assignment must be rejected
        // by the type system, so it is asserted here as documentation rather than executed.
        assertThat(ScalarValue.class.isAssignableFrom(ScalarValue.class)).isTrue();
        assertThat(List.of(ScalarValue.class).get(0).getPermittedSubclasses()).hasSize(8);
        assertThat(TypedValue.class.getPermittedSubclasses()).hasSize(3);
    }
}