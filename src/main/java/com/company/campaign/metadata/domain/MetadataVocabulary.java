package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-vocabulary invariants shared by the definition records.
 *
 * <p>These checks are the difference between a schema mirror and a semantic contract: an operator that
 * does not apply to a data type, an enum without values, a money type without a currency policy, a window
 * without event-time/boundary semantics or an empty-set value that contradicts the result type are all
 * rejected here with {@link MetadataErrorCodes#METADATA_SEMANTIC_COMBINATION_INVALID}.
 */
final class MetadataVocabulary {

    private MetadataVocabulary() {
    }

    /** Canonical, distinct, declaration-ordered operator list whose every member supports the data type. */
    static List<ComparisonOperator> canonicalOperators(List<ComparisonOperator> operators, SemanticDataType dataType) {
        if (operators == null || operators.isEmpty()) {
            throw combination("A definition must declare at least one comparison operator");
        }
        EnumSet<ComparisonOperator> ordered = EnumSet.noneOf(ComparisonOperator.class);
        for (ComparisonOperator operator : operators) {
            if (operator == null) {
                throw combination("A comparison operator must not be null");
            }
            if (!ordered.add(operator)) {
                throw combination("A definition must not declare a comparison operator twice");
            }
            if (!operator.supports(dataType)) {
                throw combination("Operator " + operator + " does not apply to data type " + dataType);
            }
        }
        return List.copyOf(ordered);
    }

    /** Canonical enum values: required for ENUM/ORDERED_ENUM and forbidden for every other type. */
    static List<EnumValueDefinition> canonicalEnumValues(
            SemanticDataType dataType, List<EnumValueDefinition> values) {
        if (dataType == SemanticDataType.ENUM || dataType == SemanticDataType.ORDERED_ENUM) {
            if (values == null || values.isEmpty()) {
                throw combination("An enum-typed definition must declare at least one value");
            }
            List<EnumValueDefinition> copy = new ArrayList<>(values.size());
            Set<String> codes = new LinkedHashSet<>();
            Set<String> aliasKeys = new LinkedHashSet<>();
            Set<Integer> ranks = new LinkedHashSet<>();
            for (EnumValueDefinition value : values) {
                if (value == null) {
                    throw combination("An enum value must not be null");
                }
                if (!codes.add(value.code())) {
                    throw new MetadataException(
                            MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                            "An enum declares a duplicate value code");
                }
                for (String aliasKey : value.normalizedAliasKeys()) {
                    if (!aliasKeys.add(aliasKey)) {
                        throw new MetadataException(
                                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                                "An enum declares a duplicate value alias after normalization");
                    }
                }
                if (dataType == SemanticDataType.ORDERED_ENUM) {
                    if (value.rank() == null) {
                        throw combination("An ordered enum value must declare a rank");
                    }
                    if (!ranks.add(value.rank())) {
                        throw combination("An ordered enum must not declare the same rank twice");
                    }
                } else if (value.rank() != null) {
                    throw combination("Only an ordered enum value may declare a rank");
                }
                copy.add(value);
            }
            if (dataType == SemanticDataType.ORDERED_ENUM) {
                copy.sort(Comparator.comparing(EnumValueDefinition::rank)
                        .thenComparing(EnumValueDefinition::code));
            } else {
                copy.sort(EnumValueDefinition::compareTo);
            }
            return List.copyOf(copy);
        }
        if (values != null && !values.isEmpty()) {
            throw combination("Only an enum-typed definition may declare enum values");
        }
        return List.of();
    }

    /** Canonical, distinct, declaration-ordered window list. */
    static List<TimeWindowKind> canonicalWindows(List<TimeWindowKind> windows) {
        if (windows == null || windows.isEmpty()) {
            throw combination("A metric must declare at least one supported window");
        }
        EnumSet<TimeWindowKind> ordered = EnumSet.noneOf(TimeWindowKind.class);
        for (TimeWindowKind window : windows) {
            if (window == null) {
                throw combination("A supported window must not be null");
            }
            if (!ordered.add(window)) {
                throw combination("A metric must not declare a window kind twice");
            }
        }
        return List.copyOf(ordered);
    }

    /** A money type requires an explicit currency policy; every other type must declare none. */
    static void requireCurrencyConsistency(SemanticDataType dataType, CurrencySemantics currencySemantics) {
        if (currencySemantics == null || currencySemantics == CurrencySemantics.NOT_APPLICABLE) {
            if (dataType == SemanticDataType.MONEY) {
                throw combination("A money definition must declare an explicit currency policy");
            }
            return;
        }
        if (dataType != SemanticDataType.MONEY) {
            throw combination("Only a money definition may declare a currency policy");
        }
    }

    /** Bounded windows require boundary semantics; an all-unbounded window set must not declare them. */
    static void requireBoundaryConsistency(
            List<TimeWindowKind> windows, WindowBoundarySemantics boundarySemantics) {
        boolean hasBounded = windows.stream().anyMatch(TimeWindowKind::isBounded);
        if (hasBounded && boundarySemantics == WindowBoundarySemantics.NOT_APPLICABLE) {
            throw combination("A bounded window requires explicit boundary semantics");
        }
        if (!hasBounded && boundarySemantics != WindowBoundarySemantics.NOT_APPLICABLE) {
            throw combination("Unbounded windows must not declare boundary semantics");
        }
    }

    /** A ZERO empty-set value must be numeric and of exactly the metric result type. */
    static void requireEmptySetConsistency(SemanticDataType resultType, AggregateEmptySet emptySet) {
        if (emptySet.behavior() != EmptySetBehavior.ZERO) {
            return;
        }
        if (!resultType.isNumeric() || emptySet.value().dataType() != resultType) {
            throw combination("A ZERO empty-set value must match the numeric metric result type");
        }
    }

    /**
     * Freezes the Phase A aggregate empty-set behavior of Segment spec section 10.
     *
     * <p>{@code COUNT}/{@code COUNT_DISTINCT} over no rows are zero of the result type; {@code AVG},
     * {@code MIN} and {@code MAX} over no rows are NULL; {@code SUM} is zero only when explicitly declared
     * and otherwise NULL. {@code UNDEFINED} is not permitted for any Phase A metric aggregate — it stays
     * meaningful only for Relation empty semantics — so a metric can never publish an undecided empty set.
     * The value carried by a {@code ZERO} behavior is checked separately against the result type.
     */
    static void requireAggregateEmptySetConsistency(
            AggregateFunction aggregation, AggregateEmptySet emptySet) {
        EmptySetBehavior behavior = emptySet.behavior();
        switch (aggregation) {
            case COUNT, COUNT_DISTINCT -> {
                if (behavior != EmptySetBehavior.ZERO) {
                    throw combination(
                            "COUNT and COUNT_DISTINCT over no rows are zero of result type INTEGER");
                }
            }
            case AVG, MIN, MAX -> {
                if (behavior != EmptySetBehavior.NULL) {
                    throw combination("AVG, MIN and MAX over no rows are NULL");
                }
            }
            case SUM -> {
                if (behavior != EmptySetBehavior.ZERO && behavior != EmptySetBehavior.NULL) {
                    throw combination("SUM over no rows is zero only when explicitly declared, or NULL");
                }
            }
        }
    }

    /** Only a STRING definition declares string semantics; a STRING definition must declare it. */
    static void requireStringSemanticsConsistency(SemanticDataType dataType, StringSemantics stringSemantics) {
        if (dataType == SemanticDataType.STRING) {
            if (stringSemantics == null) {
                throw combination("A STRING definition must declare string comparison semantics");
            }
            return;
        }
        if (stringSemantics != null) {
            throw combination("Only a STRING definition may declare string comparison semantics");
        }
    }

    /**
     * Validates and canonicalizes a metric's event-time declaration against its supported windows.
     *
     * <p>A bounded ({@code ROLLING}/{@code FIXED}) window requires both an event-time field and its
     * semantics. A {@code LIFETIME}-only metric may omit event time entirely — the v1.3 spec defines it by
     * retained history and the data watermark rather than a bounded interval — but a partial declaration or
     * {@code NOT_APPLICABLE} semantics is rejected rather than silently defaulted.
     *
     * @return the validated event-time field, or {@code null} when a {@code LIFETIME}-only metric omits it
     */
    static String requireEventTimeConsistency(
            List<TimeWindowKind> windows, String eventTimeField, EventTimeSemantics eventTimeSemantics) {
        boolean bounded = windows.stream().anyMatch(TimeWindowKind::isBounded);
        if (bounded) {
            if (eventTimeField == null || eventTimeSemantics == null) {
                throw combination("A bounded-window metric must pin both an event-time field and its semantics");
            }
            if (eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE) {
                throw combination("A metric must not declare NOT_APPLICABLE event-time semantics");
            }
            return MetadataTexts.requireId(eventTimeField, "eventTimeField");
        }
        if (eventTimeField == null && eventTimeSemantics == null) {
            return null;
        }
        if ((eventTimeField == null) != (eventTimeSemantics == null)) {
            throw combination("A metric must declare an event-time field and its semantics together");
        }
        if (eventTimeSemantics == EventTimeSemantics.NOT_APPLICABLE) {
            throw combination("A metric must not declare NOT_APPLICABLE event-time semantics");
        }
        return MetadataTexts.requireId(eventTimeField, "eventTimeField");
    }

    /**
     * Canonical definition order shared by the snapshot and its hash material.
     *
     * <p>Rejects a null element and sorts by ascending stable definition ID, so neither publication nor
     * hashing depends on the order a caller supplied.
     */
    static <T extends MetadataDefinition> List<T> canonicalDefinitions(List<T> definitions, String field) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(definitions.size());
        for (T definition : definitions) {
            if (definition == null) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_VALUE_INVALID,
                        "Metadata '" + field + "' must not contain a null definition");
            }
            copy.add(definition);
        }
        // Documented canonical order: ascending stable definition ID, independent of insertion order.
        copy.sort(Comparator.comparing(MetadataDefinition::id));
        return List.copyOf(copy);
    }

    private static MetadataException combination(String message) {
        return new MetadataException(MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID, message);
    }
}
