package com.company.campaign.runtime;

import com.company.campaign.builder.capability.flow.FlowConditionPack;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The exact T19 Flow Condition typed-scalar vocabulary, shared by every Runtime condition value.
 *
 * <p>The operators, value kinds, canonical text forms and unknown-fact policies are the accepted T19
 * {@link FlowConditionPack} ones, and there is no second condition vocabulary anywhere in this layer: a Runtime
 * predicate, a host fact and a persisted fact snapshot all speak exactly this. The class is deliberately pure —
 * it reads no clock, no repository, no locale and no ambient state.
 *
 * <p>It lives in the Runtime package rather than being imported from the T20 simulation package, because the
 * accepted Runtime boundary forbids depending on the pure simulation layer; the semantics are the same, the
 * dependency is not.
 */
public final class RuntimeTypedValues {

    private static final DateTimeFormatter OFFSET_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** The canonical plain-decimal form: bounded integral digits and at most six fractional digits. */
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d{1,18}(\\.\\d{1,6})?$");

    /** A bounded dotted lowercase fact identifier of at most three segments. */
    private static final Pattern FACT_CODE =
            Pattern.compile("^[a-z][a-z0-9_]{0,30}(\\.[a-z][a-z0-9_]{0,30}){0,2}$");

    private RuntimeTypedValues() {
    }

    /** True when the text is the bounded dotted fact identifier the T19 contract declares. */
    public static boolean isFactCode(String factCode) {
        return factCode != null && FACT_CODE.matcher(factCode).matches();
    }

    /** True when the text is the one canonical form of the declared T19 value kind. */
    public static boolean isCanonical(String valueType, String value) {
        return canonical(valueType, value) != null;
    }

    /**
     * The typed comparable operand of the declared value kind, or {@code null} when the text is not its
     * canonical form.
     *
     * <p>The returned operand is {@link Comparable}, so every valid ordered comparison is deterministic and
     * total within one value kind.
     */
    @SuppressWarnings("unchecked")
    public static Comparable<Object> operand(String valueType, String value) {
        String canonical = canonical(valueType, value);
        if (canonical == null) {
            return null;
        }
        Object parsed = switch (valueType) {
            case FlowConditionPack.TYPE_STRING -> canonical;
            case FlowConditionPack.TYPE_INTEGER -> new BigInteger(canonical);
            case FlowConditionPack.TYPE_DECIMAL -> new BigDecimal(canonical);
            case FlowConditionPack.TYPE_BOOLEAN -> Boolean.valueOf(canonical);
            case FlowConditionPack.TYPE_DATE -> LocalDate.parse(canonical);
            case FlowConditionPack.TYPE_DATETIME -> Instant.parse(canonical);
            default -> null;
        };
        return parsed == null ? null : (Comparable<Object>) parsed;
    }

    /**
     * The one canonical text form of a typed scalar, or {@code null} when the text is not canonical.
     *
     * <p>A value kind outside the T19 closed set, an empty or over-long text, an exponential decimal, a
     * trailing-zero decimal, a non-UTC instant, an unbounded integer and an unrecognised truth text all yield
     * {@code null}, so an accepted value has exactly one spelling.
     */
    public static String canonical(String valueType, String value) {
        if (valueType == null || value == null || value.isEmpty()
                || !FlowConditionPack.VALUE_TYPES.contains(valueType)
                || value.length() > FlowConditionPack.MAX_VALUE_LENGTH) {
            return null;
        }
        return switch (valueType) {
            case FlowConditionPack.TYPE_STRING -> value;
            case FlowConditionPack.TYPE_INTEGER -> canonicalInteger(value);
            case FlowConditionPack.TYPE_DECIMAL -> canonicalDecimal(value);
            case FlowConditionPack.TYPE_BOOLEAN -> canonicalBoolean(value);
            case FlowConditionPack.TYPE_DATE -> canonicalDate(value);
            case FlowConditionPack.TYPE_DATETIME -> canonicalInstant(value);
            default -> null;
        };
    }

    private static String canonicalInteger(String value) {
        BigInteger integer;
        try {
            integer = new BigInteger(value);
        } catch (NumberFormatException rejected) {
            return null;
        }
        return integer.toString().equals(value) ? value : null;
    }

    private static String canonicalDecimal(String value) {
        if (value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
            // Exponential notation is an alternative spelling of one decimal, so it is refused rather than
            // normalized: one decimal keeps exactly one canonical text.
            return null;
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value);
        } catch (NumberFormatException rejected) {
            return null;
        }
        String canonical = decimal.stripTrailingZeros().toPlainString();
        if (!canonical.equals(value) || !DECIMAL.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    private static String canonicalBoolean(String value) {
        return "true".equals(value) || "false".equals(value) ? value : null;
    }

    private static String canonicalDate(String value) {
        try {
            return LocalDate.parse(value).toString().equals(value) ? value : null;
        } catch (DateTimeParseException rejected) {
            return null;
        }
    }

    private static String canonicalInstant(String value) {
        try {
            return OffsetDateTime.parse(value, OFFSET_DATE_TIME).toInstant().toString().equals(value)
                    ? value
                    : null;
        } catch (DateTimeParseException rejected) {
            return null;
        }
    }
}