package com.company.campaign.segment.canonical;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataScalarValue;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.contract.UnicodeTexts;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The single canonical invariant and ordering helper shared by the canonical model constructors.
 *
 * <p>Every public canonical record validates through here, so a caller cannot construct a canonical value,
 * node, window or definition in a shape {@link SegmentCanonicalizer} would never emit. The ordering used to
 * sort commutative group children and set-like LIST items lives here too, so the model's canonical order and
 * the canonicalizer's are the same implementation rather than two that must be kept in step.
 *
 * <p>The helper only reads already-constructed children and values through {@link CanonicalSegmentCodec}; it
 * never constructs a canonical record, so there is no initialization cycle between the model and the codec.
 * Every failure is a stable bounded {@link CanonicalInvariantException} that echoes no payload value.
 */
final class CanonicalInvariants {

    private static final CanonicalSegmentCodec CODEC = new CanonicalSegmentCodec();

    /** The stable canonical order of set-like list items: content hash, then full canonical bytes. */
    static final Comparator<CanonicalValue> VALUE_ORDER =
            Comparator.comparing((CanonicalValue value) -> CODEC.hashValue(value).value())
                    .thenComparing(CODEC::writeValueString);

    /** The stable canonical order of commutative group children: content hash, then full canonical bytes. */
    static final Comparator<CanonicalNode> CHILD_ORDER =
            Comparator.comparing((CanonicalNode node) -> CODEC.hashNode(node).value())
                    .thenComparing(node -> new String(CODEC.writeNode(node), StandardCharsets.UTF_8));

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);
    private static final Pattern LOCAL_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private CanonicalInvariants() {
    }

    // ------------------------------------------------------------------ presence

    /**
     * Requires a public-boundary input to be present, returning it unchanged.
     *
     * <p>Every public canonical constructor and codec entry point routes its required arguments and collection
     * elements through here, so a null input is a stable coded {@link CanonicalInvariantException} rather than a
     * raw {@link NullPointerException}. The {@code invariant} detail is a closed name and the message is fixed
     * prose, so the rejected argument is never echoed outward.
     */
    static <T> T requirePresent(T value, String invariant) {
        if (value == null) {
            throw failure(CanonicalErrorCodes.CANONICAL_INPUT_MISSING, invariant,
                    "A canonical boundary requires a non-null input.");
        }
        return value;
    }

    // ------------------------------------------------------------------ versions

    static void requireCanonicalizerVersion(String value) {
        if (!CanonicalizerVersions.CANONICALIZER_VERSION.equals(value)) {
            throw failure(CanonicalErrorCodes.CANONICALIZER_VERSION_UNSUPPORTED, "canonicalizerVersion",
                    "A canonical root must carry the one supported canonicalizer version.");
        }
    }

    static void requireSupportedSchemaVersion(SchemaVersion version) {
        if (!SegmentDslSchemaVersions.isSupported(version)) {
            throw failure(CanonicalErrorCodes.CANONICAL_SCHEMA_UNSUPPORTED, "schemaVersion",
                    "A canonical root must carry the one supported Segment DSL schema version.");
        }
    }

    // ------------------------------------------------------------------ scalar forms

    static void requireCanonicalDecimal(String value) {
        if (!CanonicalForms.decimal(value).equals(value)) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, "decimal",
                    "A canonical DECIMAL must be a stripped plain-decimal text.");
        }
    }

    static void requireCanonicalInstant(String value) {
        if (!CanonicalForms.instant(value).equals(value)) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, "instant",
                    "A canonical DATETIME must be the one UTC form of its instant.");
        }
    }

    static void requireCanonicalDate(String value) {
        if (value == null || !LOCAL_DATE.matcher(value).matches()) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, "date",
                    "A canonical DATE must be an exact local calendar date.");
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, "date",
                    "A canonical DATE must be an exact local calendar date.");
        }
    }

    static void requireMachineCode(String value, String invariant) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, invariant,
                    "A canonical ENUM code must be a bounded uppercase machine code.");
        }
    }

    static void requireCurrency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, "currency",
                    "A canonical MONEY currency must be a three-letter uppercase ISO code.");
        }
    }

    static void requireReferenceId(String value, String invariant) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw failure(CanonicalErrorCodes.CANONICAL_REFERENCE_ID_INVALID, invariant,
                    "A canonical reference identifier must be a valid bounded reference identifier.");
        }
    }

    // ------------------------------------------------------------------ ordering

    /** Returns the items in canonical order, rejecting any repeat of one canonical meaning. */
    static List<CanonicalValue> orderedValues(List<CanonicalValue> items) {
        List<CanonicalValue> ordered = sortedValues(items);
        rejectRepeatedValues(ordered);
        return List.copyOf(ordered);
    }

    /** Returns the children in canonical order, rejecting any repeat of one canonical meaning. */
    static List<CanonicalNode> orderedChildren(List<CanonicalNode> children) {
        List<CanonicalNode> ordered = sortedChildren(children);
        rejectRepeatedChildren(ordered);
        return List.copyOf(ordered);
    }

    static List<CanonicalValue> sortedValues(List<CanonicalValue> items) {
        List<CanonicalValue> ordered = new ArrayList<>(items);
        ordered.sort(VALUE_ORDER);
        return ordered;
    }

    static List<CanonicalNode> sortedChildren(List<CanonicalNode> children) {
        List<CanonicalNode> ordered = new ArrayList<>(children);
        ordered.sort(CHILD_ORDER);
        return ordered;
    }

    private static void rejectRepeatedValues(List<CanonicalValue> ordered) {
        for (int index = 1; index < ordered.size(); index++) {
            if (VALUE_ORDER.compare(ordered.get(index - 1), ordered.get(index)) == 0) {
                throw failure(CanonicalErrorCodes.CANONICAL_LIST_NOT_CANONICAL, "listItems",
                        "A canonical LIST must not repeat one canonical meaning.");
            }
        }
    }

    private static void rejectRepeatedChildren(List<CanonicalNode> ordered) {
        for (int index = 1; index < ordered.size(); index++) {
            if (CHILD_ORDER.compare(ordered.get(index - 1), ordered.get(index)) == 0) {
                throw failure(CanonicalErrorCodes.CANONICAL_GROUP_NOT_CANONICAL, "groupChildren",
                        "A canonical group must not repeat one canonical meaning.");
            }
        }
    }

    // ------------------------------------------------------------------ value shapes

    /** Requires a canonical STRING to be well-formed Unicode, as every canonicalizer-emitted text is. */
    static void requireWellFormedText(String value) {
        if (!UnicodeTexts.isWellFormed(value)) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, "text",
                    "A canonical STRING must be well-formed Unicode.");
        }
    }

    /**
     * Requires a canonical RANGE to respect the intrinsic order of its bound representation.
     *
     * <p>Integer, decimal, date, instant and same-currency money bounds have an order intrinsic to their
     * representation, so the canonicalizer can never emit an inverted bound pair; a single-point pair must be
     * inclusive on both sides. STRING, ENUM and BOOLEAN bounds are left unconstrained because their order is
     * Metadata-owned and deliberately not decided here.
     */
    static void requireRangeOrder(
            CanonicalValue lower, CanonicalValue upper, boolean includeLower, boolean includeUpper) {
        Integer comparison = intrinsicComparison(lower, upper);
        if (comparison == null) {
            return;
        }
        if (comparison > 0) {
            throw failure(CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE, "rangeBoundsOrder",
                    "A canonical RANGE must not invert bounds with an intrinsic order.");
        }
        if (comparison == 0 && !(includeLower && includeUpper)) {
            throw failure(CanonicalErrorCodes.CANONICAL_RANGE_INCOMPATIBLE, "rangeBoundsEmpty",
                    "A canonical RANGE must not be empty at one point.");
        }
    }

    private static Integer intrinsicComparison(CanonicalValue lower, CanonicalValue upper) {
        return switch (lower) {
            case CanonicalValue.IntegerValue left when upper instanceof CanonicalValue.IntegerValue right ->
                    Long.compare(left.value(), right.value());
            case CanonicalValue.DecimalValue left when upper instanceof CanonicalValue.DecimalValue right ->
                    new BigDecimal(left.value()).compareTo(new BigDecimal(right.value()));
            case CanonicalValue.DateValue left when upper instanceof CanonicalValue.DateValue right ->
                    LocalDate.parse(left.value()).compareTo(LocalDate.parse(right.value()));
            case CanonicalValue.InstantValue left when upper instanceof CanonicalValue.InstantValue right ->
                    Instant.parse(left.instant()).compareTo(Instant.parse(right.instant()));
            case CanonicalValue.MoneyValue left when upper instanceof CanonicalValue.MoneyValue right ->
                    left.currency().equals(right.currency())
                            ? new BigDecimal(left.amount()).compareTo(new BigDecimal(right.amount()))
                            : null;
            default -> null;
        };
    }

    /**
     * True when the subtree contains a metric aggregate.
     *
     * <p>The canonicalizer never emits one beneath an EXISTS boundary — a nested metric has no unambiguous
     * scope and the validator rejects it — so the canonical EXISTS constructor refuses it defensively.
     */
    static boolean containsMetric(CanonicalNode node) {
        return switch (node) {
            case CanonicalNode.Metric ignored -> true;
            case CanonicalNode.MetricCalculation ignored -> true;
            case CanonicalNode.Group group -> group.children().stream().anyMatch(CanonicalInvariants::containsMetric);
            case CanonicalNode.Negation negation -> containsMetric(negation.child());
            case CanonicalNode.Exists exists -> containsMetric(exists.child());
            // A reference is a leaf over an immutable pin; it embeds no aggregate of this document.
            case CanonicalNode.AudienceReference ignored -> false;
            case CanonicalNode.Attribute ignored -> false;
        };
    }

    /** True when the value is a scalar, as a canonical LIST item or RANGE bound must be. */
    static boolean isScalar(CanonicalValue value) {
        return !(value instanceof CanonicalValue.ListValue) && !(value instanceof CanonicalValue.RangeValue);
    }

    /** The one canonical value kind a Metadata data type admits. */
    static CanonicalValueKind expectedKind(SemanticDataType dataType) {
        return switch (dataType) {
            case STRING -> CanonicalValueKind.STRING;
            case BOOLEAN -> CanonicalValueKind.BOOLEAN;
            case INTEGER -> CanonicalValueKind.INTEGER;
            case DECIMAL -> CanonicalValueKind.DECIMAL;
            case DATE -> CanonicalValueKind.DATE;
            case TIMESTAMP -> CanonicalValueKind.DATETIME;
            case ENUM, ORDERED_ENUM -> CanonicalValueKind.ENUM;
            case MONEY -> CanonicalValueKind.MONEY;
        };
    }

    /** Requires every scalar inside a value (scalar, list or range) to be the kind the data type admits. */
    static void requireValueKind(CanonicalValue value, SemanticDataType dataType, String invariant) {
        CanonicalValueKind expected = expectedKind(dataType);
        switch (value) {
            case CanonicalValue.ListValue list -> {
                for (CanonicalValue item : list.items()) {
                    requireKind(item, expected, invariant);
                }
            }
            case CanonicalValue.RangeValue range -> {
                requireKind(range.lower(), expected, invariant);
                requireKind(range.upper(), expected, invariant);
            }
            default -> requireKind(value, expected, invariant);
        }
    }

    private static void requireKind(CanonicalValue value, CanonicalValueKind expected, String invariant) {
        if (value.kind() != expected) {
            throw failure(CanonicalErrorCodes.CANONICAL_VALUE_FORM_INVALID, invariant,
                    "A canonical operand is not the value kind its definition's data type admits.");
        }
    }

    // ------------------------------------------------------------------ operator/value shape

    /**
     * Requires the exact canonical operator/value pairing: a NULL operator carries no value, a non-NULL
     * operator carries one, {@code IN}/{@code NOT_IN} carry a LIST, {@code BETWEEN} carries a RANGE and every
     * other operator carries a scalar.
     */
    static void requireOperatorValue(ComparisonOperator operator, CanonicalValue value) {
        if (operator.isNullOperator()) {
            if (value != null) {
                throw shape("nullOperatorValue");
            }
            return;
        }
        if (value == null) {
            throw shape("missingOperatorValue");
        }
        switch (operator) {
            case IN, NOT_IN -> {
                if (!(value instanceof CanonicalValue.ListValue)) {
                    throw shape("listOperatorValue");
                }
            }
            case BETWEEN -> {
                if (!(value instanceof CanonicalValue.RangeValue)) {
                    throw shape("rangeOperatorValue");
                }
            }
            default -> {
                if (!isScalar(value)) {
                    throw shape("scalarOperatorValue");
                }
            }
        }
    }

    private static CanonicalInvariantException shape(String invariant) {
        return failure(CanonicalErrorCodes.CANONICAL_OPERATOR_VALUE_SHAPE_INVALID, invariant,
                "A canonical predicate pairs an operator with a value shape the canonicalizer never emits.");
    }

    // ------------------------------------------------------------------ definitions

    static void requireAttributeDefinition(MetadataKind kind, SemanticDataType dataType,
            CurrencySemantics currencySemantics, StringSemantics stringSemantics) {
        if (kind != MetadataKind.ATTRIBUTE) {
            throw failure(CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID, "attributeKind",
                    "A canonical attribute definition must be an ATTRIBUTE definition.");
        }
        requireStringSemantics(dataType, stringSemantics);
        requireCurrencySemantics(dataType, currencySemantics);
    }

    static void requireMetricDefinition(SemanticDataType resultType, CurrencySemantics currencySemantics,
            StringSemantics stringSemantics, EmptySetBehavior emptySetBehavior, MetadataScalarValue emptySetValue) {
        requireStringSemantics(resultType, stringSemantics);
        requireCurrencySemantics(resultType, currencySemantics);
        if (emptySetBehavior == EmptySetBehavior.UNDEFINED) {
            throw failure(CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID, "metricEmptySet",
                    "A canonical metric must not publish an undefined empty-set behavior.");
        }
        if (emptySetBehavior == EmptySetBehavior.ZERO) {
            if (emptySetValue == null || emptySetValue.dataType() != resultType || !resultType.isNumeric()) {
                throw failure(CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID, "metricEmptySetValue",
                        "A canonical ZERO empty-set value must match the numeric metric result type.");
            }
        } else if (emptySetValue != null) {
            throw failure(CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID, "metricEmptySetValue",
                    "Only a canonical ZERO empty-set behavior may carry a value.");
        }
    }

    private static void requireStringSemantics(SemanticDataType dataType, StringSemantics stringSemantics) {
        boolean isString = dataType == SemanticDataType.STRING;
        if (isString != (stringSemantics != null)) {
            throw failure(CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID, "stringSemantics",
                    "Only a canonical STRING definition may pin string comparison semantics.");
        }
    }

    private static void requireCurrencySemantics(SemanticDataType dataType, CurrencySemantics currencySemantics) {
        boolean isMoney = dataType == SemanticDataType.MONEY;
        if (currencySemantics == null || isMoney != currencySemantics.isExplicitPolicy()) {
            throw failure(CanonicalErrorCodes.CANONICAL_DEFINITION_INVALID, "currencySemantics",
                    "Only a canonical MONEY definition may pin an explicit currency policy.");
        }
    }

    // ------------------------------------------------------------------ relation reference

    static void requireRelationReference(RelationJoinKind joinKind, String sourceJoinField,
            String targetJoinField, EventTimeSemantics eventTimeSemantics, String eventTimeField) {
        switch (joinKind) {
            case CORRELATED_BY_SOURCE_KEY -> {
                if (sourceJoinField == null || targetJoinField == null) {
                    throw relation("relationJoinFields");
                }
            }
            case TARGET_FILTERED -> {
                if (sourceJoinField != null || targetJoinField == null) {
                    throw relation("relationJoinFields");
                }
            }
            case GLOBAL -> {
                if (sourceJoinField != null || targetJoinField != null) {
                    throw relation("relationJoinFields");
                }
            }
        }
        if ((eventTimeSemantics == null) != (eventTimeField == null)
                || eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE) {
            throw relation("relationEventTime");
        }
    }

    private static CanonicalInvariantException relation(String invariant) {
        return failure(CanonicalErrorCodes.CANONICAL_RELATION_INVALID, invariant,
                "A canonical relation reference is not a declaration the canonicalizer ever emits.");
    }

    // ------------------------------------------------------------------ failure

    static CanonicalInvariantException failure(String code, String invariant, String message) {
        return new CanonicalInvariantException(code, invariant, message);
    }
}
