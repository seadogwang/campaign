package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.contracts.ContractPatterns;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * One explicit scenario fact supplied to a Journey simulation.
 *
 * <p>A fact is exactly one of three closed shapes, and the shape is unrepresentable otherwise: a
 * {@link ScenarioFactOutcome#KNOWN} fact carries one exact typed canonical value of a T19 Flow Condition value
 * kind; a {@link ScenarioFactOutcome#UNKNOWN} fact carries nothing else; an {@link ScenarioFactOutcome#ERROR}
 * fact carries only a bounded machine reason code. The typed value reuses the exact T19 Flow Condition value
 * kinds and canonical text forms, so there is one representation per value and no second condition vocabulary.
 *
 * <p>Construction fails closed on an unbounded fact code, a value kind outside the T19 closed set, a value
 * that is not the canonical text of its declared kind and a reason code that is not a bounded machine code. No
 * fact carries a member identity, PII, a template body, a reward secret or a provider response.
 */
public record ScenarioFact(
        String factCode,
        ScenarioFactOutcome outcome,
        String valueType,
        String value,
        String reasonCode) {

    /** The bounded dotted lowercase fact identifier a scenario fact may declare, mirroring the T19 grammar. */
    private static final Pattern FACT_CODE =
            Pattern.compile("^[a-z][a-z0-9_]{0,30}(\\.[a-z][a-z0-9_]{0,30}){0,2}$");

    private static final Pattern REASON_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    /** The canonical plain-decimal form of a T19 DECIMAL value. */
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d{1,18}(\\.\\d{1,6})?$");

    public ScenarioFact {
        if (factCode == null || factCode.length() > FlowConditionPack.MAX_FACT_CODE_LENGTH
                || !FACT_CODE.matcher(factCode).matches()) {
            throw invalid("factCode");
        }
        if (outcome == null) {
            throw invalid("outcome");
        }
        switch (outcome) {
            case KNOWN -> {
                if (valueType == null || !FlowConditionPack.VALUE_TYPES.contains(valueType)) {
                    throw invalid("valueType");
                }
                if (value == null || value.isEmpty()
                        || value.length() > FlowConditionPack.MAX_VALUE_LENGTH) {
                    throw invalid("value");
                }
                requireCanonical(valueType, value);
                if (reasonCode != null) {
                    throw invalid("reasonCode");
                }
            }
            case UNKNOWN -> {
                if (valueType != null || value != null || reasonCode != null) {
                    throw invalid("unknownFact");
                }
            }
            case ERROR -> {
                if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
                    throw invalid("reasonCode");
                }
                if (valueType != null || value != null) {
                    throw invalid("errorFact");
                }
            }
        }
    }

    /** A known fact carrying the canonical text of one T19 Flow Condition value kind. */
    public static ScenarioFact known(String factCode, String valueType, String value) {
        return new ScenarioFact(factCode, ScenarioFactOutcome.KNOWN, valueType, value, null);
    }

    /** An explicitly unknown fact; it may never be treated as false. */
    public static ScenarioFact unknown(String factCode) {
        return new ScenarioFact(factCode, ScenarioFactOutcome.UNKNOWN, null, null, null);
    }

    /** An explicit fact error carrying a bounded machine reason code only. */
    public static ScenarioFact error(String factCode, String reasonCode) {
        return new ScenarioFact(factCode, ScenarioFactOutcome.ERROR, null, null, reasonCode);
    }

    /** Deterministic canonical order: by fact code. */
    public static int order(ScenarioFact left, ScenarioFact right) {
        return left.factCode.compareTo(right.factCode);
    }

    /** Redacted diagnostics: only already-safe machine facts. */
    @Override
    public String toString() {
        return "ScenarioFact[factCode=" + factCode + ", outcome=" + outcome + ']';
    }

    /** The one canonical text form of a typed scalar, or a bounded rejection. */
    private static void requireCanonical(String valueType, String value) {
        switch (valueType) {
            case FlowConditionPack.TYPE_STRING -> {
                // A string is already its own canonical form.
            }
            case FlowConditionPack.TYPE_INTEGER -> {
                BigInteger integer;
                try {
                    integer = new BigInteger(value);
                } catch (NumberFormatException rejected) {
                    throw invalid("value");
                }
                if (!integer.toString().equals(value)) {
                    throw invalid("value");
                }
            }
            case FlowConditionPack.TYPE_DECIMAL -> {
                if (value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
                    throw invalid("value");
                }
                BigDecimal decimal;
                try {
                    decimal = new BigDecimal(value);
                } catch (NumberFormatException rejected) {
                    throw invalid("value");
                }
                String canonical = decimal.stripTrailingZeros().toPlainString();
                if (!canonical.equals(value) || !DECIMAL.matcher(value).matches()) {
                    throw invalid("value");
                }
            }
            case FlowConditionPack.TYPE_BOOLEAN -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw invalid("value");
                }
            }
            case FlowConditionPack.TYPE_DATE -> {
                try {
                    if (!LocalDate.parse(value).toString().equals(value)) {
                        throw invalid("value");
                    }
                } catch (DateTimeParseException rejected) {
                    throw invalid("value");
                }
            }
            case FlowConditionPack.TYPE_DATETIME -> {
                try {
                    if (!OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            .toInstant().toString().equals(value)) {
                        throw invalid("value");
                    }
                } catch (DateTimeParseException rejected) {
                    throw invalid("value");
                }
            }
            default -> throw invalid("valueType");
        }
    }

    private static SimulationException invalid(String invariant) {
        return new SimulationException(SimulationErrorCodes.SCENARIO_INVALID, invariant,
                "A scenario fact is not one of the three representable shapes.");
    }
}
