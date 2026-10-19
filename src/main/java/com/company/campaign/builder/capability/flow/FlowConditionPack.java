package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The concrete, production Flow Condition capability pack.
 *
 * <p>A Condition node carries one small, closed, typed fact predicate of its own: an explicit fact code, one
 * scalar comparison operator, one typed scalar value and the policy to apply when the fact is unknown
 * ({@code UNKNOWN} or {@code ERROR}). This is a Flow-specific contract, not a general rule model: it declares
 * no action, no nested predicate tree, no executable content and no storage construct, and it shares no rule
 * vocabulary with any other capability.
 *
 * <p>The fact code is a bounded dotted identifier of at most three lowercase segments, a grammar that cannot
 * express a whitespace-, quote-, parenthesis- or statement-bearing construct at all; a segment naming a
 * storage or executable keyword is refused as well. The typed value is carried as the canonical text of the
 * type the predicate declares, so the payload has exactly one representation per value: an integer normalizes
 * to its plain form, a decimal to its trailing-zero-free plain form, a date and an instant to their canonical
 * ISO-8601 text, and a boolean to {@code true} or {@code false}. An ordered comparison on a boolean is
 * refused, because a boolean has no meaningful order here.
 *
 * <p>The pack interprets no fact and evaluates no predicate: it validates and canonicalizes the predicate's
 * configuration only. Resolving a fact against data is a later Runtime concern.
 */
public final class FlowConditionPack extends FlowPackSupport {

    /** The canonical capability code of this pack. */
    public static final String CAPABILITY_CODE = FlowPackVersions.CONDITION;

    /** The declared fact-code field. */
    public static final String FACT_CODE_FIELD = "factCode";

    /** The declared comparison-operator field. */
    public static final String OPERATOR_FIELD = "operator";

    /** The declared value-type field. */
    public static final String VALUE_TYPE_FIELD = "valueType";

    /** The declared typed-value field. */
    public static final String VALUE_FIELD = "value";

    /** The declared unknown-fact policy field. */
    public static final String ON_UNKNOWN_FIELD = "onUnknown";

    /** The policy that an unknown fact evaluates to the unknown verdict. */
    public static final String ON_UNKNOWN = "UNKNOWN";

    /** The policy that an unknown fact fails the predicate closed. */
    public static final String ON_UNKNOWN_ERROR = "ERROR";

    /** The two closed unknown-fact policies, in their declared order. */
    public static final List<String> ON_UNKNOWN_POLICIES = List.of(ON_UNKNOWN, ON_UNKNOWN_ERROR);

    /** The equality comparison operator. */
    public static final String OPERATOR_EQUALS = "EQ";

    /** The inequality comparison operator. */
    public static final String OPERATOR_NOT_EQUALS = "NEQ";

    /** The strict lower-than comparison operator. */
    public static final String OPERATOR_LESS_THAN = "LT";

    /** The lower-than-or-equal comparison operator. */
    public static final String OPERATOR_LESS_OR_EQUAL = "LTE";

    /** The strict greater-than comparison operator. */
    public static final String OPERATOR_GREATER_THAN = "GT";

    /** The greater-than-or-equal comparison operator. */
    public static final String OPERATOR_GREATER_OR_EQUAL = "GTE";

    /** The closed scalar comparison operators, in their declared order. */
    public static final List<String> OPERATORS = List.of(
            OPERATOR_EQUALS, OPERATOR_NOT_EQUALS, OPERATOR_LESS_THAN,
            OPERATOR_LESS_OR_EQUAL, OPERATOR_GREATER_THAN, OPERATOR_GREATER_OR_EQUAL);

    /** The closed scalar value kinds a typed value may declare. */
    public static final String TYPE_STRING = "STRING";

    /** The whole-number value kind. */
    public static final String TYPE_INTEGER = "INTEGER";

    /** The exact-decimal value kind. */
    public static final String TYPE_DECIMAL = "DECIMAL";

    /** The truth-value kind. */
    public static final String TYPE_BOOLEAN = "BOOLEAN";

    /** The calendar-date value kind. */
    public static final String TYPE_DATE = "DATE";

    /** The instant value kind. */
    public static final String TYPE_DATETIME = "DATETIME";

    /** The closed scalar value kinds, in their declared order. */
    public static final List<String> VALUE_TYPES = List.of(
            TYPE_STRING, TYPE_INTEGER, TYPE_DECIMAL, TYPE_BOOLEAN, TYPE_DATE, TYPE_DATETIME);

    /** The largest permitted fact-code length. */
    public static final int MAX_FACT_CODE_LENGTH = 64;

    /** The largest permitted typed-value text length. */
    public static final int MAX_VALUE_LENGTH = 256;

    /** The largest permitted number of dotted fact-code segments. */
    public static final int MAX_FACT_SEGMENTS = 3;

    /** The bounded summary code this node projects when it resolves. */
    static final List<String> SUMMARY_CODES = List.of("FLOW_CONDITION");

    /** The bounded risk codes this node projects. */
    static final List<String> RISK_CODES = List.of(
            "CONDITION_FACT_UNRESOLVED", "JOURNEY_SEMANTICS_DEFERRED", "RUNTIME_PREVIEW_ONLY");

    /** A bounded dotted lowercase fact identifier of at most {@link #MAX_FACT_SEGMENTS} segments. */
    private static final Pattern FACT_CODE =
            Pattern.compile("^[a-z][a-z0-9_]{0,30}(\\.[a-z][a-z0-9_]{0,30}){0,2}$");

    /** The canonical plain-decimal form: bounded integral digits and at most six fractional digits. */
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d{1,18}(\\.\\d{1,6})?$");

    /** Fact-code segments that name a storage or executable keyword and are therefore refused. */
    private static final Set<String> RESERVED_FACT_SEGMENTS = Set.of(
            "select", "insert", "update", "delete", "drop", "union",
            "from", "where", "join", "exec", "eval", "script", "sql");

    /** The closed payload contract: five required fields, no optional field. */
    private static final CapabilityPayloadContract CONTRACT = new CapabilityPayloadContract(
            List.of(
                    new CapabilityFieldSchema(
                            FACT_CODE_FIELD, new CapabilityFieldConstraint.Text(MAX_FACT_CODE_LENGTH)),
                    new CapabilityFieldSchema(
                            ON_UNKNOWN_FIELD, new CapabilityFieldConstraint.EnumCodes(ON_UNKNOWN_POLICIES)),
                    new CapabilityFieldSchema(
                            OPERATOR_FIELD, new CapabilityFieldConstraint.EnumCodes(OPERATORS)),
                    new CapabilityFieldSchema(
                            VALUE_FIELD, new CapabilityFieldConstraint.Text(MAX_VALUE_LENGTH)),
                    new CapabilityFieldSchema(
                            VALUE_TYPE_FIELD, new CapabilityFieldConstraint.EnumCodes(VALUE_TYPES))),
            List.of());

    public FlowConditionPack() {
        super(CAPABILITY_CODE, CONTRACT, SUMMARY_CODES, RISK_CODES, corpus());
    }

    /** The ordered in-code evaluation fixture corpus of this node. */
    static List<FlowEvaluationFixture> corpus() {
        return List.of(
                new FlowEvaluationFixture("flow-condition-string-valid", Expectation.ACCEPTED,
                        Map.of(FACT_CODE_FIELD, "member.tier", OPERATOR_FIELD, OPERATOR_EQUALS,
                                VALUE_TYPE_FIELD, TYPE_STRING, VALUE_FIELD, "GOLD",
                                ON_UNKNOWN_FIELD, ON_UNKNOWN)),
                new FlowEvaluationFixture("flow-condition-integer-valid", Expectation.ACCEPTED,
                        Map.of(FACT_CODE_FIELD, "order.count", OPERATOR_FIELD, OPERATOR_GREATER_OR_EQUAL,
                                VALUE_TYPE_FIELD, TYPE_INTEGER, VALUE_FIELD, "7",
                                ON_UNKNOWN_FIELD, ON_UNKNOWN_ERROR)),
                new FlowEvaluationFixture("flow-condition-missing-policy", Expectation.REJECTED,
                        Map.of(FACT_CODE_FIELD, "member.tier", OPERATOR_FIELD, OPERATOR_EQUALS,
                                VALUE_TYPE_FIELD, TYPE_STRING, VALUE_FIELD, "GOLD")),
                new FlowEvaluationFixture("flow-condition-unknown-operator", Expectation.REJECTED,
                        Map.of(FACT_CODE_FIELD, "member.tier", OPERATOR_FIELD, "LIKE",
                                VALUE_TYPE_FIELD, TYPE_STRING, VALUE_FIELD, "GOLD",
                                ON_UNKNOWN_FIELD, ON_UNKNOWN)),
                new FlowEvaluationFixture("flow-condition-unknown-value-type", Expectation.REJECTED,
                        Map.of(FACT_CODE_FIELD, "member.tier", OPERATOR_FIELD, OPERATOR_EQUALS,
                                VALUE_TYPE_FIELD, "MONEY", VALUE_FIELD, "GOLD",
                                ON_UNKNOWN_FIELD, ON_UNKNOWN)),
                new FlowEvaluationFixture("flow-condition-unknown-policy", Expectation.REJECTED,
                        Map.of(FACT_CODE_FIELD, "member.tier", OPERATOR_FIELD, OPERATOR_EQUALS,
                                VALUE_TYPE_FIELD, TYPE_STRING, VALUE_FIELD, "GOLD",
                                ON_UNKNOWN_FIELD, "SKIP")),
                new FlowEvaluationFixture("flow-condition-ordered-boolean", Expectation.REJECTED,
                        Map.of(FACT_CODE_FIELD, "member.active", OPERATOR_FIELD, OPERATOR_LESS_THAN,
                                VALUE_TYPE_FIELD, TYPE_BOOLEAN, VALUE_FIELD, "true",
                                ON_UNKNOWN_FIELD, ON_UNKNOWN)),
                new FlowEvaluationFixture("flow-condition-type-mismatch", Expectation.REJECTED,
                        Map.of(FACT_CODE_FIELD, "order.count", OPERATOR_FIELD, OPERATOR_EQUALS,
                                VALUE_TYPE_FIELD, TYPE_INTEGER, VALUE_FIELD, "many",
                                ON_UNKNOWN_FIELD, ON_UNKNOWN)),
                new FlowEvaluationFixture("flow-condition-unknown-field", Expectation.REJECTED,
                        Map.of(FACT_CODE_FIELD, "member.tier", OPERATOR_FIELD, OPERATOR_EQUALS,
                                VALUE_TYPE_FIELD, TYPE_STRING, VALUE_FIELD, "GOLD",
                                ON_UNKNOWN_FIELD, ON_UNKNOWN, "action", "send")));
    }

    @Override
    CapabilityPayload normalizeFields(Map<String, Object> fields) {
        String factCode = requireText(fields, FACT_CODE_FIELD);
        String operator = requireText(fields, OPERATOR_FIELD);
        String valueType = requireText(fields, VALUE_TYPE_FIELD);
        String value = requireText(fields, VALUE_FIELD);
        String onUnknown = requireText(fields, ON_UNKNOWN_FIELD);

        requireFactCode(factCode);
        if (!OPERATORS.contains(operator) || !VALUE_TYPES.contains(valueType)
                || !ON_UNKNOWN_POLICIES.contains(onUnknown)) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        if (TYPE_BOOLEAN.equals(valueType)
                && !OPERATOR_EQUALS.equals(operator) && !OPERATOR_NOT_EQUALS.equals(operator)) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        String canonicalValue = canonicalValue(valueType, value);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put(FACT_CODE_FIELD, factCode);
        canonical.put(ON_UNKNOWN_FIELD, onUnknown);
        canonical.put(OPERATOR_FIELD, operator);
        canonical.put(VALUE_FIELD, canonicalValue);
        canonical.put(VALUE_TYPE_FIELD, valueType);
        return CapabilityPayload.of(canonical);
    }

    /** Proves one fact code is the bounded dotted identifier this contract declares. */
    private static void requireFactCode(String factCode) {
        if (factCode.length() > MAX_FACT_CODE_LENGTH || !FACT_CODE.matcher(factCode).matches()) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        int segments = 0;
        for (String segment : factCode.split("\\.", -1)) {
            segments++;
            if (segments > MAX_FACT_SEGMENTS || RESERVED_FACT_SEGMENTS.contains(segment)) {
                throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
            }
        }
    }

    /** The one canonical text form of a typed scalar, or a bounded rejection. */
    private static String canonicalValue(String valueType, String value) {
        if (value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        return switch (valueType) {
            case TYPE_STRING -> value;
            case TYPE_INTEGER -> canonicalInteger(value);
            case TYPE_DECIMAL -> canonicalDecimal(value);
            case TYPE_BOOLEAN -> canonicalBoolean(value);
            case TYPE_DATE -> canonicalDate(value);
            case TYPE_DATETIME -> canonicalInstant(value);
            default -> throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        };
    }

    /** The plain decimal text of a bounded whole number. */
    private static String canonicalInteger(String value) {
        BigInteger integer;
        try {
            integer = new BigInteger(value);
        } catch (NumberFormatException rejected) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        if (integer.abs().compareTo(BigInteger.valueOf(CapabilityFieldConstraint.MAX_INTEGER_MAGNITUDE)) > 0) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        return integer.toString();
    }

    /** The trailing-zero-free plain decimal text of a bounded exact decimal. */
    private static String canonicalDecimal(String value) {
        if (value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
            // Exponential notation is an alternative spelling of one decimal, so it is refused rather than
            // normalized: one decimal keeps exactly one canonical text here.
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value);
        } catch (NumberFormatException rejected) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        String canonical = decimal.stripTrailingZeros().toPlainString();
        if (!DECIMAL.matcher(canonical).matches()) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        return canonical;
    }

    /** The one canonical truth text. */
    private static String canonicalBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
        return value;
    }

    /** The canonical ISO-8601 calendar date. */
    private static String canonicalDate(String value) {
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException rejected) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
    }

    /**
     * The canonical UTC instant of an explicitly offset instant.
     *
     * <p>An instant must carry its offset: a local date-time with no offset names no single instant, so it is
     * refused rather than guessed against a default zone, and every accepted instant normalizes to the one
     * UTC spelling of that instant.
     */
    private static String canonicalInstant(String value) {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toString();
        } catch (DateTimeParseException rejected) {
            throw new FlowPackException(FlowPackErrorCodes.CONDITION_INVALID);
        }
    }
}
