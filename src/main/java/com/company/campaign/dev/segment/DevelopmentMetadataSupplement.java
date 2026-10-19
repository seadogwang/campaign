package com.company.campaign.dev.segment;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.MetricFilterDimension;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RefundTreatment;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringNormalization;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DevelopmentMetadataSupplement(
        String snapshotVersion,
        Map<String, SemanticDataType> attributeTypeOverrides,
        List<AttributeSpec> attributes,
        List<MetricSpec> metrics,
        List<RelationDefinition> relations,
        List<EventSelectorDefinition> eventSelectors,
        Map<String, List<String>> metricAliasAdditions,
        Map<String, List<MetricFilterDimension>> metricScopeFilterAdditions,
        Map<String, List<TimeWindowKind>> metricWindowAdditions) {

    MetadataSnapshot apply(MetadataSnapshot base) {
        Map<String, AttributeDefinition> mergedAttributes = new LinkedHashMap<>();
        for (AttributeDefinition definition : base.attributes()) {
            SemanticDataType override = attributeTypeOverrides.get(definition.id());
            mergedAttributes.put(definition.id(), override == null ? definition : withType(definition, override));
        }
        for (AttributeSpec spec : attributes) {
            AttributeDefinition definition = spec.toDefinition();
            mergedAttributes.put(definition.id(), definition);
        }

        Map<String, MetricDefinition> mergedMetrics = new LinkedHashMap<>();
        for (MetricDefinition definition : base.metrics()) {
            List<String> aliasAdditions = metricAliasAdditions.get(definition.id());
            List<MetricFilterDimension> additions = metricScopeFilterAdditions.get(definition.id());
            List<TimeWindowKind> windowAdditions = metricWindowAdditions.get(definition.id());
            mergedMetrics.put(definition.id(), aliasAdditions == null && additions == null && windowAdditions == null
                    ? definition
                    : withEnhancements(definition, aliasAdditions, additions, windowAdditions));
        }
        for (MetricSpec spec : metrics) {
            MetricDefinition definition = spec.toDefinition();
            mergedMetrics.put(definition.id(), definition);
        }

        Map<String, RelationDefinition> mergedRelations = new LinkedHashMap<>();
        base.relations().forEach(definition -> mergedRelations.put(definition.id(), definition));
        relations.forEach(definition -> mergedRelations.put(definition.id(), definition));

        return MetadataSnapshot.publish(
                base.schemaVersion(), base.snapshotId(), snapshotVersion, base.tenantId(), base.programId(),
                List.copyOf(mergedAttributes.values()),
                List.copyOf(mergedMetrics.values()),
                List.copyOf(mergedRelations.values()),
                eventSelectors == null ? base.eventSelectors() : eventSelectors);
    }

    private static AttributeDefinition withType(AttributeDefinition definition, SemanticDataType dataType) {
        return new AttributeDefinition(
                definition.id(), definition.entityVersion(), definition.subject(), definition.displayName(),
                dataType, stringSemantics(dataType), definition.aliases(), operators(dataType),
                dataType == SemanticDataType.ENUM || dataType == SemanticDataType.ORDERED_ENUM
                        ? definition.enumValues() : List.of(),
                definition.nullSemantics(), currencySemantics(dataType), definition.positiveExamples(),
                definition.negativeExamples(), definition.disambiguation());
    }

    private static MetricDefinition withEnhancements(
            MetricDefinition definition,
            List<String> aliasAdditions,
            List<MetricFilterDimension> additions,
            List<TimeWindowKind> windowAdditions) {
        Map<String, String> aliases = new LinkedHashMap<>();
        definition.aliases().forEach(value -> aliases.put(value, value));
        if (aliasAdditions != null) {
            aliasAdditions.forEach(value -> aliases.put(value, value));
        }
        Map<String, MetricFilterDimension> merged = new LinkedHashMap<>();
        definition.scopeFilterDimensions().forEach(value -> merged.put(value.attributeId(), value));
        if (additions != null) {
            additions.forEach(value -> merged.put(value.attributeId(), value));
        }
        EnumSet<TimeWindowKind> windows = EnumSet.copyOf(definition.supportedWindows());
        if (windowAdditions != null) {
            windows.addAll(windowAdditions);
        }
        return new MetricDefinition(
                definition.id(), definition.entityVersion(), definition.subject(), definition.displayName(),
                definition.resultType(), definition.stringSemantics(), List.copyOf(aliases.values()),
                definition.allowedOperators(), definition.aggregation(), definition.relationId(),
                definition.eventTimeField(), definition.eventTimeSemantics(), List.copyOf(merged.values()),
                definition.filter(), definition.refundTreatment(), definition.currencySemantics(),
                List.copyOf(windows), definition.windowBoundarySemantics(), definition.nullSemantics(),
                definition.emptySet(), definition.positiveExamples(), definition.negativeExamples(),
                definition.disambiguation());
    }

    record AttributeSpec(
            String id,
            EntityVersionRef entityVersion,
            SubjectKind subject,
            String displayName,
            SemanticDataType dataType,
            List<String> aliases,
            List<EnumValueDefinition> enumValues,
            NullSemantics nullSemantics,
            String disambiguation) {

        AttributeDefinition toDefinition() {
            return new AttributeDefinition(
                    id, entityVersion, subject, displayName, dataType, stringSemantics(dataType), aliases,
                    operators(dataType), enumValues == null ? List.of() : enumValues,
                    nullSemantics == null ? NullSemantics.UNKNOWN_VALUE : nullSemantics,
                    currencySemantics(dataType), List.of(), List.of(), disambiguation);
        }
    }

    record MetricSpec(
            String id,
            String displayName,
            SemanticDataType resultType,
            List<String> aliases,
            AggregateFunction aggregation,
            String relationId,
            String eventTimeField,
            List<MetricFilterDimension> scopeFilterDimensions,
            MetadataFilter filter,
            RefundTreatment refundTreatment,
            String disambiguation) {

        MetricDefinition toDefinition() {
            AggregateEmptySet emptySet = switch (aggregation) {
                case COUNT, COUNT_DISTINCT, SUM -> AggregateEmptySet.zero(resultType);
                case AVG, MIN, MAX -> AggregateEmptySet.nullValue();
            };
            return new MetricDefinition(
                    id, new EntityVersionRef(SubjectKind.MEMBER, SchemaVersion.parse("1.2")), SubjectKind.MEMBER,
                    displayName, resultType, stringSemantics(resultType), aliases, operators(resultType),
                    aggregation, relationId, eventTimeField, EventTimeSemantics.EVENT_TIME,
                    scopeFilterDimensions == null ? List.of() : scopeFilterDimensions,
                    filter, refundTreatment, currencySemantics(resultType),
                    List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED,
                            TimeWindowKind.RELATIVE_TO_ATTRIBUTE),
                    WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END,
                    NullSemantics.EXCLUDED, emptySet,
                    List.of(), List.of(), disambiguation);
        }
    }

    private static List<ComparisonOperator> operators(SemanticDataType dataType) {
        EnumSet<ComparisonOperator> operators = switch (dataType) {
            case BOOLEAN -> EnumSet.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL);
            case ENUM -> EnumSet.of(
                    ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                    ComparisonOperator.IN, ComparisonOperator.NOT_IN,
                    ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL);
            case ORDERED_ENUM -> EnumSet.of(
                    ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                    ComparisonOperator.IN, ComparisonOperator.NOT_IN,
                    ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL,
                    ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL,
                    ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL,
                    ComparisonOperator.BETWEEN);
            case STRING -> EnumSet.of(
                    ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                    ComparisonOperator.IN, ComparisonOperator.NOT_IN,
                    ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL);
            default -> EnumSet.of(
                    ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                    ComparisonOperator.IN, ComparisonOperator.NOT_IN,
                    ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL,
                    ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL,
                    ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL,
                    ComparisonOperator.BETWEEN);
        };
        return List.copyOf(operators);
    }

    private static StringSemantics stringSemantics(SemanticDataType dataType) {
        return dataType == SemanticDataType.STRING
                ? StringSemantics.localeNeutral(StringCaseSensitivity.CASE_SENSITIVE, StringNormalization.NONE)
                : null;
    }

    private static CurrencySemantics currencySemantics(SemanticDataType dataType) {
        return dataType == SemanticDataType.MONEY
                ? CurrencySemantics.SINGLE_CURRENCY_ONLY
                : CurrencySemantics.NOT_APPLICABLE;
    }
}
