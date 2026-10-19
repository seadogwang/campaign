package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.MetadataTexts;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable semantic Metric definition.
 *
 * <p>A metric pins everything that can change an evaluation: the referenced relation and event-time field,
 * immutable filter semantics, refund treatment, currency policy, supported windows with their boundary and
 * event-time semantics, NULL semantics, string-comparison semantics for a STRING result and empty-set
 * behavior. Referenced relations and entity versions are validated when the metric is placed in a snapshot.
 *
 * <p>The event-time field and semantics are nullable only for a {@code LIFETIME}-only metric, which the
 * v1.3 spec defines by retained history and the data watermark rather than a bounded interval; a bounded
 * window and any partial declaration are rejected by the compact constructor.
 */
public record MetricDefinition(
        String id,
        EntityVersionRef entityVersion,
        SubjectKind subject,
        String displayName,
        SemanticDataType resultType,
        StringSemantics stringSemantics,
        List<String> aliases,
        List<ComparisonOperator> allowedOperators,
        AggregateFunction aggregation,
        String relationId,
        String eventTimeField,
        EventTimeSemantics eventTimeSemantics,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<MetricFilterDimension> scopeFilterDimensions,
        MetadataFilter filter,
        RefundTreatment refundTreatment,
        CurrencySemantics currencySemantics,
        List<TimeWindowKind> supportedWindows,
        WindowBoundarySemantics windowBoundarySemantics,
        NullSemantics nullSemantics,
        AggregateEmptySet emptySet,
        List<String> positiveExamples,
        List<String> negativeExamples,
        String disambiguation
) implements MetadataDefinition {

    public MetricDefinition {
        id = MetadataTexts.requireId(id, "metricId");
        entityVersion = MetadataTexts.requireNonNull(
                entityVersion, "entityVersion", MetadataErrorCodes.METADATA_VALUE_INVALID);
        subject = MetadataTexts.requireNonNull(subject, "subject", MetadataErrorCodes.METADATA_VALUE_INVALID);
        if (subject != entityVersion.entity()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "Metric subject must match its referenced entity");
        }
        displayName = MetadataTexts.requireDisplayName(displayName, "metricDisplayName");
        resultType = MetadataTexts.requireNonNull(
                resultType, "resultType", MetadataErrorCodes.METADATA_VALUE_INVALID);
        MetadataVocabulary.requireStringSemanticsConsistency(resultType, stringSemantics);
        aliases = MetadataTexts.canonicalTextList(aliases, "metricAlias");
        MetadataTexts.requireNoCollision(displayName, aliases, "metricAlias");
        allowedOperators = MetadataVocabulary.canonicalOperators(allowedOperators, resultType);
        aggregation = MetadataTexts.requireNonNull(
                aggregation, "aggregation", MetadataErrorCodes.METADATA_VALUE_INVALID);
        if (!aggregation.supports(resultType)) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "Aggregate " + aggregation + " cannot produce result type " + resultType);
        }
        relationId = MetadataTexts.requireId(relationId, "metricRelationId");
        filter = MetadataTexts.requireNonNull(filter, "filter", MetadataErrorCodes.METADATA_VALUE_INVALID);
        refundTreatment = MetadataTexts.requireNonNull(
                refundTreatment, "refundTreatment", MetadataErrorCodes.METADATA_VALUE_INVALID);
        currencySemantics = MetadataTexts.requireNonNull(
                currencySemantics, "currencySemantics", MetadataErrorCodes.METADATA_VALUE_INVALID);
        MetadataVocabulary.requireCurrencyConsistency(resultType, currencySemantics);
        supportedWindows = MetadataVocabulary.canonicalWindows(supportedWindows);
        windowBoundarySemantics = MetadataTexts.requireNonNull(
                windowBoundarySemantics, "windowBoundarySemantics", MetadataErrorCodes.METADATA_VALUE_INVALID);
        MetadataVocabulary.requireBoundaryConsistency(supportedWindows, windowBoundarySemantics);
        eventTimeField = MetadataVocabulary.requireEventTimeConsistency(
                supportedWindows, eventTimeField, eventTimeSemantics);
        scopeFilterDimensions = canonicalScopeFilterDimensions(scopeFilterDimensions);
        nullSemantics = MetadataTexts.requireNonNull(
                nullSemantics, "nullSemantics", MetadataErrorCodes.METADATA_VALUE_INVALID);
        emptySet = MetadataTexts.requireNonNull(emptySet, "emptySet", MetadataErrorCodes.METADATA_VALUE_INVALID);
        MetadataVocabulary.requireEmptySetConsistency(resultType, emptySet);
        MetadataVocabulary.requireAggregateEmptySetConsistency(aggregation, emptySet);
        positiveExamples = MetadataTexts.canonicalTextList(positiveExamples, "positiveExample");
        negativeExamples = MetadataTexts.canonicalTextList(negativeExamples, "negativeExample");
        MetadataTexts.requireDisjoint(positiveExamples, negativeExamples, "negativeExample");
        disambiguation = MetadataTexts.optionalText(disambiguation, "disambiguation");
    }

    /** Backward-compatible constructor for metrics that do not allow dynamic row filters. */
    public MetricDefinition(
            String id,
            EntityVersionRef entityVersion,
            SubjectKind subject,
            String displayName,
            SemanticDataType resultType,
            StringSemantics stringSemantics,
            List<String> aliases,
            List<ComparisonOperator> allowedOperators,
            AggregateFunction aggregation,
            String relationId,
            String eventTimeField,
            EventTimeSemantics eventTimeSemantics,
            MetadataFilter filter,
            RefundTreatment refundTreatment,
            CurrencySemantics currencySemantics,
            List<TimeWindowKind> supportedWindows,
            WindowBoundarySemantics windowBoundarySemantics,
            NullSemantics nullSemantics,
            AggregateEmptySet emptySet,
            List<String> positiveExamples,
            List<String> negativeExamples,
            String disambiguation) {
        this(id, entityVersion, subject, displayName, resultType, stringSemantics, aliases, allowedOperators,
                aggregation, relationId, eventTimeField, eventTimeSemantics, List.of(), filter, refundTreatment,
                currencySemantics, supportedWindows, windowBoundarySemantics, nullSemantics, emptySet,
                positiveExamples, negativeExamples, disambiguation);
    }

    @Override
    public MetadataKind kind() {
        return MetadataKind.METRIC;
    }

    /** Normalized keys for every discoverable name of this definition, used for collision checks. */
    public List<String> discoverableKeys() {
        List<String> keys = new ArrayList<>(aliases.size() + 1);
        keys.add(MetadataTexts.normalizedKey(displayName, "metricDisplayName"));
        aliases.forEach(alias -> keys.add(MetadataTexts.normalizedKey(alias, "metricAlias")));
        return List.copyOf(keys);
    }

    private static List<MetricFilterDimension> canonicalScopeFilterDimensions(
            List<MetricFilterDimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return List.of();
        }
        List<MetricFilterDimension> canonical = new ArrayList<>(dimensions.size());
        Set<String> attributeIds = new HashSet<>();
        for (MetricFilterDimension dimension : dimensions) {
            if (dimension == null || !attributeIds.add(dimension.attributeId())) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                        "Metric filter dimensions must be non-null and unique by Attribute ID");
            }
            canonical.add(dimension);
        }
        canonical.sort(MetricFilterDimension::compareTo);
        return List.copyOf(canonical);
    }
}
