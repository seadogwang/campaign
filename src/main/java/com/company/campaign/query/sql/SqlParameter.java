package com.company.campaign.query.sql;

import com.company.campaign.contracts.ContractPatterns;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * One immutable, typed, canonically-valued bind parameter.
 *
 * <p>A parameter is the only place a value may live. Its {@code canonicalValue} is the one exact textual form
 * for its type — a stripped plain decimal, an ISO local date, an ISO instant, an uppercase machine code, an
 * ISO-4217 currency or a bounded reference identifier — so two parameters that mean the same value are always
 * equal and hash alike. Text is deliberately unconstrained beyond being present and bounded in length, because
 * adversarial input is expected: a value may contain a quote, a backslash, a percent sign, an underscore, a
 * comment marker, a semicolon or a SQL keyword and still be carried as data, because the renderer emits a
 * marker and never the value.
 *
 * <p>The record is a value type with a stable {@code equals}/{@code hashCode}, so the compiled hash covers
 * parameter types and canonical values exactly.
 */
public record SqlParameter(SqlParameterType type, String canonicalValue) {

    private static final Pattern INTEGER_TEXT = Pattern.compile("^-?(0|[1-9][0-9]*)$");
    private static final Pattern DECIMAL_TEXT = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /** The largest textual parameter this compiler will carry; a longer one is a policy violation. */
    private static final int MAX_TEXT_LENGTH = 65_536;

    public SqlParameter {
        if (type == null || canonicalValue == null) {
            throw invalid("sqlParameter");
        }
        validate(type, canonicalValue);
    }

    /** An exact boolean. */
    public static SqlParameter booleanValue(boolean value) {
        return new SqlParameter(SqlParameterType.BOOLEAN, value ? "true" : "false");
    }

    /** A signed 64-bit integer in canonical plain form. */
    public static SqlParameter integer(long value) {
        return new SqlParameter(SqlParameterType.INTEGER, Long.toString(value));
    }

    /** An exact decimal, stripped of trailing zeros and never in scientific notation. */
    public static SqlParameter decimal(BigDecimal value) {
        if (value == null) {
            throw invalid("sqlDecimalParameter");
        }
        return new SqlParameter(SqlParameterType.DECIMAL, canonicalDecimal(value));
    }

    /** Arbitrary textual data, carried verbatim as a value and never as SQL text. */
    public static SqlParameter text(String value) {
        return new SqlParameter(SqlParameterType.TEXT, value);
    }

    /** An uppercase enum machine code. */
    public static SqlParameter enumCode(String code) {
        return new SqlParameter(SqlParameterType.ENUM, code);
    }

    /** An exact local calendar date, {@code YYYY-MM-DD}. */
    public static SqlParameter date(String value) {
        if (value == null) {
            throw invalid("sqlDateParameter");
        }
        return new SqlParameter(SqlParameterType.DATE, canonicalDateText(value));
    }

    /** An instant on the UTC timeline. */
    public static SqlParameter timestamp(Instant value) {
        if (value == null) {
            throw invalid("sqlTimestampParameter");
        }
        return new SqlParameter(SqlParameterType.TIMESTAMP, value.toString());
    }

    /** An ISO-4217 uppercase currency code. */
    public static SqlParameter currency(String code) {
        return new SqlParameter(SqlParameterType.CURRENCY, code);
    }

    /** A bounded tenant/program/DataScope/snapshot reference identifier. */
    public static SqlParameter contextId(String id) {
        return new SqlParameter(SqlParameterType.CONTEXT_ID, id);
    }

    private static void validate(SqlParameterType type, String value) {
        switch (type) {
            case BOOLEAN -> require("true".equals(value) || "false".equals(value), "sqlBooleanParameter");
            case INTEGER -> require(INTEGER_TEXT.matcher(value).matches(), "sqlIntegerParameter");
            case DECIMAL -> require(DECIMAL_TEXT.matcher(value).matches() && canonicalDecimalText(value).equals(value),
                    "sqlDecimalParameter");
            case TEXT -> require(value.length() <= MAX_TEXT_LENGTH, "sqlTextParameter");
            case ENUM -> require(MACHINE_CODE.matcher(value).matches(), "sqlEnumParameter");
            case DATE -> require(canonicalDateText(value).equals(value), "sqlDateParameter");
            case TIMESTAMP -> require(canonicalInstantText(value).equals(value), "sqlTimestampParameter");
            case CURRENCY -> require(CURRENCY.matcher(value).matches(), "sqlCurrencyParameter");
            case CONTEXT_ID -> require(REFERENCE_ID.matcher(value).matches(), "sqlContextIdParameter");
        }
    }

    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    private static String canonicalDecimalText(String value) {
        try {
            return canonicalDecimal(new BigDecimal(value));
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("sqlDecimalParameter");
        }
    }

    private static String canonicalDateText(String value) {
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException exception) {
            throw invalid("sqlDateParameter");
        }
    }

    private static String canonicalInstantText(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException exception) {
            throw invalid("sqlTimestampParameter");
        }
    }

    private static void require(boolean condition, String invariant) {
        if (!condition) {
            throw invalid(invariant);
        }
    }

    private static SqlException invalid(String invariant) {
        return new SqlException(SqlErrorCodes.SQL_AST_INVALID, invariant,
                "A SQL bind parameter is not a canonical value of its declared type.");
    }

    @Override
    public String toString() {
        return "SqlParameter[" + type + "]";
    }
}