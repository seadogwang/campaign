package com.company.campaign.segment.canonical;

import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * The one normalization boundary for the two canonical scalar forms the validator and the canonical model
 * must agree on: a stripped plain decimal and a UTC instant.
 *
 * <p>It exists so there is exactly one implementation of each rule. The canonicalizer normalizes an editable
 * operand through these methods, and the canonical value constructors prove a caller-supplied value is
 * already the fixed point of the same methods. Splitting the two would let the model accept a form the
 * canonicalizer never emits, or reject one it always does.
 *
 * <p>Every failure is a stable {@link CanonicalInvariantException} whose message and details never carry the
 * rejected text. Inputs are length- and magnitude-bounded before any unbounded expansion, so a forged value
 * cannot drive an allocation.
 */
public final class CanonicalForms {

    private CanonicalForms() {
    }

    /**
     * Normalizes a plain-decimal lexeme to its one semantic representation.
     *
     * <p>{@code 1}, {@code 1.0} and {@code 1.00} all become {@code 1}; every zero representation becomes
     * {@code 0}. No rounding, scaling or currency conversion is applied, and an exponent form is never
     * expanded beyond the declared decimal precision bound.
     */
    public static String decimal(String text) {
        if (text == null || text.isEmpty() || text.length() > SegmentDslLimits.MAX_DECIMAL_TEXT_LENGTH) {
            throw formFailure("decimal");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException exception) {
            // The parse failure carries the rejected text, so it is replaced by the stable bounded failure.
            throw formFailure("decimal");
        }
        // Bound the magnitude before stripTrailingZeros()/toPlainString() can expand a one-character exponent
        // into an unbounded digit string.
        if (Math.max(decimal.scale(), 0) > SegmentDslLimits.MAX_DECIMAL_SCALE
                || decimal.scale() < -SegmentDslLimits.MAX_DECIMAL_PRECISION
                || decimal.precision() > SegmentDslLimits.MAX_DECIMAL_PRECISION) {
            throw formFailure("decimal");
        }
        if (decimal.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    /** Normalizes an offset date-time to the one UTC instant representation for that instant. */
    public static String instant(String text) {
        if (text == null || text.length() > SegmentDslLimits.MAX_DATETIME_TEXT_LENGTH) {
            throw formFailure("instant");
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toString();
        } catch (DateTimeParseException exception) {
            // The parse failure carries the rejected text, so it is replaced by the stable bounded failure.
            throw formFailure("instant");
        }
    }

    private static CanonicalInvariantException formFailure(String invariant) {
        return new CanonicalInvariantException(
                CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID,
                invariant,
                "A canonical value is not in the exact normalized form its kind requires.");
    }
}