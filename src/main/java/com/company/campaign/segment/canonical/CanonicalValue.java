package com.company.campaign.segment.canonical;

import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

import java.util.List;

/**
 * Closed, immutable canonical semantic value.
 *
 * <p>It is deliberately not the editable T05 {@code TypedValue}: the editable document keeps the declared
 * lexical form so a user edit round-trips, while this value stores the one representation that two
 * semantically equal operands share. Decimal scale, timestamp offset, enum code casing, currency casing and
 * Metadata-owned string normalization are already applied, so {@code 1}, {@code 1.0} and {@code 1.00} are
 * the single instance {@link DecimalValue}{@code ("1")}.
 *
 * <p>Every public constructor proves the exact representation its name promises, so a caller cannot build a
 * scalar in a non-canonical lexical form, a LIST that is not the sorted duplicate-free set the canonicalizer
 * emits or a RANGE whose bounds are not mutually compatible. A rejection is a stable bounded
 * {@link CanonicalInvariantException} that echoes no payload value.
 *
 * <p>LIST items and RANGE bounds are scalars; a nested list or range is not representable. LIST order is
 * fixed here to the canonicalizer's own order (content hash, then full canonical bytes) rather than trusted
 * from the caller.
 */
public sealed interface CanonicalValue
        permits CanonicalValue.TextValue, CanonicalValue.IntegerValue, CanonicalValue.DecimalValue,
        CanonicalValue.BooleanValue, CanonicalValue.DateValue, CanonicalValue.InstantValue,
        CanonicalValue.EnumValue, CanonicalValue.MoneyValue, CanonicalValue.ListValue,
        CanonicalValue.RangeValue {

    /** The exact wire discriminator for this canonical value. */
    CanonicalValueKind kind();

    /** A Metadata-normalized string. Its normalization is owned by the definition, so it is not re-derived here. */
    record TextValue(String value) implements CanonicalValue {
        public TextValue {
            CanonicalInvariants.requirePresent(value, "text");
            CanonicalInvariants.requireWellFormedText(value);
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.STRING;
        }
    }

    /** A signed 64-bit integer. */
    record IntegerValue(long value) implements CanonicalValue {
        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.INTEGER;
        }
    }

    /** A plain-decimal text with trailing zeros stripped; {@code 0} for every zero representation. */
    record DecimalValue(String value) implements CanonicalValue {
        public DecimalValue {
            CanonicalInvariants.requirePresent(value, "decimal");
            CanonicalInvariants.requireCanonicalDecimal(value);
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.DECIMAL;
        }
    }

    /** A JSON boolean. */
    record BooleanValue(boolean value) implements CanonicalValue {
        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.BOOLEAN;
        }
    }

    /** An exact local calendar date, {@code YYYY-MM-DD}. It carries no timezone. */
    record DateValue(String value) implements CanonicalValue {
        public DateValue {
            CanonicalInvariants.requirePresent(value, "date");
            CanonicalInvariants.requireCanonicalDate(value);
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.DATE;
        }
    }

    /** An instant on the UTC timeline in ISO-8601 {@code Z} form, offset already removed. */
    record InstantValue(String instant) implements CanonicalValue {
        public InstantValue {
            CanonicalInvariants.requirePresent(instant, "instant");
            CanonicalInvariants.requireCanonicalInstant(instant);
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.DATETIME;
        }
    }

    /** An uppercase enum machine code already proven to be a member of the owning definition. */
    record EnumValue(String code) implements CanonicalValue {
        public EnumValue {
            CanonicalInvariants.requirePresent(code, "enumCode");
            CanonicalInvariants.requireMachineCode(code, "enumCode");
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.ENUM;
        }
    }

    /** A normalized monetary amount in an uppercase ISO-4217 currency. */
    record MoneyValue(String amount, String currency) implements CanonicalValue {
        public MoneyValue {
            CanonicalInvariants.requirePresent(amount, "amount");
            CanonicalInvariants.requirePresent(currency, "currency");
            CanonicalInvariants.requireCanonicalDecimal(amount);
            CanonicalInvariants.requireCurrency(currency);
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.MONEY;
        }
    }

    /** An ordered, de-duplicated list of homogeneous scalar canonical values. */
    record ListValue(List<CanonicalValue> items) implements CanonicalValue {
        public ListValue {
            if (items == null || items.isEmpty()) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL, "listItems",
                        "A canonical LIST value must be non-empty.");
            }
            if (items.size() > SegmentDslLimits.MAX_LIST_ITEMS) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL, "listItems",
                        "A canonical LIST exceeds the largest declarable item count.");
            }
            CanonicalValueKind itemKind = null;
            for (CanonicalValue item : items) {
                CanonicalInvariants.requirePresent(item, "listItems");
                if (!CanonicalInvariants.isScalar(item)) {
                    throw CanonicalInvariants.failure(
                            CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL, "listItems",
                            "A canonical LIST item must be a scalar value.");
                }
                if (itemKind == null) {
                    itemKind = item.kind();
                } else if (itemKind != item.kind()) {
                    throw CanonicalInvariants.failure(
                            CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL, "listItems",
                            "A canonical LIST must be homogeneous.");
                }
            }
            // The constructor owns the canonical order and the duplicate-free guarantee, so a caller cannot
            // pin an unsorted or repeated set that the canonicalizer would never emit.
            items = CanonicalInvariants.orderedValues(items);
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.LIST;
        }
    }

    /** A bounded range with scalar bounds and exact inclusivity flags. */
    record RangeValue(CanonicalValue lower, CanonicalValue upper, boolean includeLower, boolean includeUpper)
            implements CanonicalValue {
        public RangeValue {
            CanonicalInvariants.requirePresent(lower, "rangeLower");
            CanonicalInvariants.requirePresent(upper, "rangeUpper");
            if (!CanonicalInvariants.isScalar(lower) || !CanonicalInvariants.isScalar(upper)) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE, "rangeBounds",
                        "A canonical RANGE bound must be a scalar value.");
            }
            if (lower.kind() != upper.kind()) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE, "rangeBounds",
                        "A canonical RANGE must pair compatible bound kinds.");
            }
            if (lower instanceof MoneyValue lowerMoney && upper instanceof MoneyValue upperMoney
                    && !lowerMoney.currency().equals(upperMoney.currency())) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE, "rangeCurrency",
                        "A canonical MONEY RANGE must pair bounds in one currency.");
            }
            CanonicalInvariants.requireRangeOrder(lower, upper, includeLower, includeUpper);
        }

        @Override
        public CanonicalValueKind kind() {
            return CanonicalValueKind.RANGE;
        }
    }
}