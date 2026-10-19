package com.company.campaign.segment.intent;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Closed immutable union of the raw value shapes an Intent clause may carry.
 *
 * <p>This is deliberately <em>not</em> the T05 DSL value model and not a Metadata-bound value: a clause is
 * model output awaiting resolution, so a value stays a validated <em>raw shape</em> with a declared kind.
 * The union is closed and hand-parsed, so a raw map, an open {@code any}, a nested container inside a LIST
 * or a RANGE bound, and an unknown {@code kind} are all rejected with stable codes.
 *
 * <p>Numeric literals are canonicalized (plain notation, no leading zeros, no trailing zeros) so two texts
 * that mean the same number hash identically; text is bounded but otherwise preserved.
 */
public sealed interface IntentValue
        permits IntentValue.Scalar, IntentValue.ListValue, IntentValue.RangeValue {

    /** The raw shape this value claims. */
    IntentValueKind kind();

    /** A single scalar value. Closed: only the scalar kinds below implement it. */
    sealed interface Scalar extends IntentValue
            permits StringValue, BooleanValue, IntegerValue, DecimalValue,
                    DateValue, TimestampValue, EnumValue, MoneyValue {
    }

    /** A bounded free string; empty is admissible, null and over-long text are not. */
    record StringValue(String raw) implements Scalar {

        public StringValue {
            raw = IntentTexts.requireText(raw, "value.raw");
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.STRING;
        }
    }

    /** A JSON boolean literal. */
    record BooleanValue(boolean raw) implements Scalar {

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.BOOLEAN;
        }
    }

    /** A canonical plain integer literal. */
    record IntegerValue(String raw) implements Scalar {

        public IntegerValue {
            raw = canonicalInteger(raw);
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.INTEGER;
        }
    }

    /** A canonical plain decimal literal. */
    record DecimalValue(String raw) implements Scalar {

        public DecimalValue {
            raw = canonicalDecimal(raw);
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.DECIMAL;
        }
    }

    /** An ISO-8601 calendar date. */
    record DateValue(String raw) implements Scalar {

        public DateValue {
            raw = canonicalDate(raw);
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.DATE;
        }
    }

    /** An ISO-8601 instant. */
    record TimestampValue(String raw) implements Scalar {

        public TimestampValue {
            raw = canonicalInstant(raw);
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.TIMESTAMP;
        }
    }

    /** A raw enum label as written by the user; the Metadata code is decided during resolution. */
    record EnumValue(String raw) implements Scalar {

        public EnumValue {
            raw = IntentTexts.requireText(raw, "value.raw");
            if (raw.isEmpty()) {
                throw IntentException.invalid(
                        IntentErrorCodes.VALUE_INVALID, "An enum value must not be blank");
            }
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.ENUM;
        }
    }

    /** A canonical plain money amount; the currency lives in its own dedicated slot. */
    record MoneyValue(String raw) implements Scalar {

        public MoneyValue {
            raw = canonicalDecimal(raw);
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.MONEY;
        }
    }

    /** A bounded, non-empty list of scalar values. */
    record ListValue(List<Scalar> items) implements IntentValue {

        public ListValue {
            if (items == null || items.isEmpty()) {
                throw IntentException.invalid(
                        IntentErrorCodes.VALUE_INVALID, "A list value requires at least one item");
            }
            if (items.size() > IntentLimits.MAX_LIST_ITEMS) {
                throw new IntentException(
                        IntentErrorCodes.LIMIT_EXCEEDED, "A list value exceeds the item limit");
            }
            List<Scalar> copy = new ArrayList<>(items.size());
            for (Scalar item : items) {
                if (item == null) {
                    throw IntentException.invalid(
                            IntentErrorCodes.VALUE_INVALID, "A list value item must not be null");
                }
                copy.add(item);
            }
            items = List.copyOf(copy);
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.LIST;
        }
    }

    /** A scalar range with explicit inclusivity, used by BETWEEN-style operators. */
    record RangeValue(Scalar lower, Scalar upper, boolean includeLower, boolean includeUpper)
            implements IntentValue {

        public RangeValue {
            if (lower == null || upper == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.VALUE_INVALID, "A range value requires both bounds");
            }
        }

        @Override
        public IntentValueKind kind() {
            return IntentValueKind.RANGE;
        }
    }

    private static String canonicalInteger(String raw) {
        String text = IntentTexts.requireText(raw, "value.raw");
        if (!text.matches("^[+-]?[0-9]+$")) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "An integer value is not a well-formed integer literal");
        }
        try {
            return new BigInteger(text).toString();
        } catch (NumberFormatException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "An integer value is not a well-formed integer literal");
        }
    }

    private static String canonicalDecimal(String raw) {
        String text = IntentTexts.requireText(raw, "value.raw");
        if (!text.matches("^[+-]?[0-9]+(\\.[0-9]+)?$")) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "A decimal value is not a well-formed decimal literal");
        }
        try {
            BigDecimal decimal = new BigDecimal(text).stripTrailingZeros();
            if (decimal.scale() < 0) {
                decimal = decimal.setScale(0);
            }
            return decimal.toPlainString();
        } catch (NumberFormatException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "A decimal value is not a well-formed decimal literal");
        }
    }

    private static String canonicalDate(String raw) {
        String text = IntentTexts.requireText(raw, "value.raw");
        try {
            return LocalDate.parse(text).toString();
        } catch (DateTimeParseException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "A date value is not a well-formed ISO-8601 date");
        }
    }

    private static String canonicalInstant(String raw) {
        String text = IntentTexts.requireText(raw, "value.raw");
        try {
            return Instant.parse(text).toString();
        } catch (DateTimeParseException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.VALUE_INVALID, "A timestamp value is not a well-formed ISO-8601 instant");
        }
    }
}