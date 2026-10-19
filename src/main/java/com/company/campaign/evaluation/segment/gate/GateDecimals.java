package com.company.campaign.evaluation.segment.gate;

import java.math.BigDecimal;

/**
 * Exact decimal handling for Gate thresholds and observed metric values.
 *
 * <p>Threshold comparison is exact: no double, no float and no rounding step ever appears, so a value one
 * unit in the twelfth decimal place below a threshold is below it rather than rounded up to it. A value is
 * accepted only in one canonical plain-decimal form, so two representations of the same threshold cannot
 * compare differently or produce different decision bytes.
 */
final class GateDecimals {

    private GateDecimals() {
    }

    /** Parses one canonical plain-decimal string, rejecting exponents and non-canonical trailing zeros. */
    static BigDecimal canonical(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw GateException.of(GateErrorCodes.THRESHOLD_EVIDENCE_INVALID, field + " is missing");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value);
        } catch (NumberFormatException malformed) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID, field + " is not a decimal value");
        }
        if (!decimal.stripTrailingZeros().toPlainString().equals(value)) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID, field + " is not a canonical decimal value");
        }
        return decimal;
    }

    /** Renders a non-negative numerator over a positive denominator as a canonical exact decimal. */
    static String ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            throw GateException.of(
                    GateErrorCodes.THRESHOLD_EVIDENCE_INVALID, "A defined metric requires a positive denominator");
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 12, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}