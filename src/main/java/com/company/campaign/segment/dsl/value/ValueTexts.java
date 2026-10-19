package com.company.campaign.segment.dsl.value;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.contract.TemporalGrammar;
import com.company.campaign.segment.dsl.contract.UnicodeTexts;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Lexical validation for the typed-value wire forms.
 *
 * <p>Every rule here is purely lexical and timezone-independent: it neither normalizes a value (that is a
 * T07 semantic decision) nor consults the default locale, timezone or calendar. Focused on structure, it
 * preserves the exact editable lexeme so a round trip cannot silently change declared scale or offset.
 */
final class ValueTexts {

    /** A plain decimal: optional sign, no leading zeros, optional fraction. No exponent, no separators. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");

    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    private ValueTexts() {
    }

    /** Validates a STRING lexeme: non-null, well-formed Unicode and within the defensive size bound. */
    static String requireText(String value, String field) {
        if (value == null) {
            throw invalid("Invalid " + field + " STRING typed value");
        }
        if (value.length() > SegmentDslLimits.MAX_STRING_LENGTH) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED,
                    "String typed value exceeds the individual size bound");
        }
        // An unpaired surrogate would be replaced on UTF-8 encoding, so it must be refused, not stored.
        if (!UnicodeTexts.isWellFormed(value)) {
            throw invalid("Invalid " + field + " STRING typed value");
        }
        return value;
    }

    /** Validates and preserves a plain-decimal lexeme, including its declared scale. */
    static String requireDecimal(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw invalid("Invalid " + field + " DECIMAL typed value");
        }
        if (value.length() > SegmentDslLimits.MAX_DECIMAL_TEXT_LENGTH) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED,
                    "Decimal typed value exceeds the individual size bound");
        }
        if (!PLAIN_DECIMAL.matcher(value).matches()) {
            throw invalid("Invalid " + field + " DECIMAL typed value");
        }
        BigDecimal decimal = new BigDecimal(value);
        int scale = Math.max(decimal.scale(), 0);
        if (scale > SegmentDslLimits.MAX_DECIMAL_SCALE) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED,
                    "Decimal typed value exceeds the supported scale");
        }
        if (decimal.precision() > SegmentDslLimits.MAX_DECIMAL_PRECISION) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED,
                    "Decimal typed value exceeds the supported precision");
        }
        return value;
    }

    /** Validates an exact {@code YYYY-MM-DD} local date and preserves its lexeme. */
    static String requireLocalDate(String value, String field) {
        if (!TemporalGrammar.isExactLocalDate(value)) {
            throw invalid("Invalid " + field + " DATE typed value");
        }
        return value;
    }

    /**
     * Validates the exact offset date-time grammar shared with FIXED window boundaries.
     *
     * <p>An offset-free local timestamp, a missing seconds component and a compact offset are rejected; nothing
     * here consults the JVM default timezone, so the accepted representation is unambiguous on any host.
     */
    static String requireOffsetDateTime(String value, String field) {
        if (!TemporalGrammar.isExactOffsetDateTime(value)) {
            throw invalid("Invalid " + field + " DATETIME typed value");
        }
        return value;
    }

    /** Validates a bounded uppercase machine code, used by ENUM values. */
    static String requireMachineCode(String value, String field) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw invalid("Invalid " + field + " ENUM typed value");
        }
        return value;
    }

    /** Validates the three-letter uppercase ISO currency grammar. */
    static String requireCurrency(String value, String field) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw invalid("Invalid " + field + " MONEY typed value");
        }
        return value;
    }

    static SegmentDslException invalid(String message) {
        return new SegmentDslException(SegmentDslErrorCodes.TYPED_VALUE_INVALID, message);
    }

    static SegmentDslException limitExceeded(String message) {
        return new SegmentDslException(SegmentDslErrorCodes.LIMIT_EXCEEDED, message);
    }
}