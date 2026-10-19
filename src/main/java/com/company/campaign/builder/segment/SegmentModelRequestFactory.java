package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.SegmentModelRequest.EnumAlias;
import com.company.campaign.builder.segment.SegmentModelRequest.MetadataProjection;
import com.company.campaign.builder.segment.SegmentModelRequest.MetadataSnapshotPin;
import com.company.campaign.builder.segment.SegmentModelRequest.MetricFilterProjection;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.EnumValueDefinition;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.resolution.ResolutionPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Builds the one immutable bounded safe model request from a host-supplied permitted Metadata snapshot.
 *
 * <p>The projection is deliberately lossy and safe: it carries only the stable id, kind, display name,
 * aliases, type, operators, enum aliases, window/currency/aggregation/relation semantics and disambiguation of
 * the already-permitted definitions. It carries no database identifier, raw example, owner or restricted field,
 * and it is bounded by {@link SegmentModelLimits}. No clock, random source, locale default or network is used.
 */
public final class SegmentModelRequestFactory {

    /** Builds the request for one utterance against the exact permitted snapshot. */
    public SegmentModelRequest build(String utterance, String localeTag, MetadataSnapshot permittedSnapshot) {
        return build(utterance, localeTag, permittedSnapshot, null);
    }

    /** Builds the request with the safe concept codes published by the exact resolution policy. */
    public SegmentModelRequest build(
            String utterance,
            String localeTag,
            MetadataSnapshot permittedSnapshot,
            ResolutionPolicy resolutionPolicy) {
        return build(utterance, localeTag, permittedSnapshot, resolutionPolicy, null, null, null);
    }

    /** Builds the request with safe temporal anchors used to interpret calendar dates deterministically. */
    public SegmentModelRequest build(
            String utterance,
            String localeTag,
            MetadataSnapshot permittedSnapshot,
            ResolutionPolicy resolutionPolicy,
            Instant asOf,
            ZoneId timezone) {
        return build(utterance, localeTag, permittedSnapshot, resolutionPolicy, asOf, timezone, null);
    }

    /** Builds the request with temporal anchors and the safe registered reporting currency. */
    public SegmentModelRequest build(
            String utterance,
            String localeTag,
            MetadataSnapshot permittedSnapshot,
            ResolutionPolicy resolutionPolicy,
            Instant asOf,
            ZoneId timezone,
            RegisteredDefaultSource registeredDefaults) {
        return build(utterance, localeTag, permittedSnapshot, resolutionPolicy, asOf, timezone, registeredDefaults,
                null);
    }

    /**
     * Builds the request with an exact authorized projection.
     *
     * <p>When {@code authorizedDefinitionIds} is supplied, only the definitions the T02 authorization boundary
     * already permitted are projected, so the safe model request cannot disclose a definition the run is not
     * allowed to use. An Event Selector is governed transitively by its referenced Metadata: it is projected
     * only when the exact admitted set also contains its complete
     * {@link EventSelectorDependencyClosure dependency closure} — every binding relation ID and identity
     * Attribute ID, every ordering Attribute ID, every fixed-filter Attribute key and every scope-filter
     * Attribute ID it needs to be described and executed. A denied, absent or stale dependency of any class
     * omits the entire selector rather than exposing its identity, binding shape or remaining permitted
     * dependencies. The snapshot pin still identifies the whole pinned snapshot, which is what a caller must be
     * able to recognize as stale. A {@code null} set keeps the diagnostic T11A behaviour of projecting the whole
     * permitted snapshot; the formal Agent always supplies the exact authorized set.
     */
    public SegmentModelRequest build(
            String utterance,
            String localeTag,
            MetadataSnapshot permittedSnapshot,
            ResolutionPolicy resolutionPolicy,
            Instant asOf,
            ZoneId timezone,
            RegisteredDefaultSource registeredDefaults,
            Set<String> authorizedDefinitionIds) {
        if (permittedSnapshot == null) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request requires a permitted snapshot");
        }
        List<MetadataProjection> projections = new ArrayList<>();
        for (AttributeDefinition attribute : permittedSnapshot.attributes()) {
            if (authorized(attribute.id(), authorizedDefinitionIds)) {
                projections.add(project(attribute, concepts(attribute.id(), resolutionPolicy)));
            }
        }
        for (MetricDefinition metric : permittedSnapshot.metrics()) {
            if (authorized(metric.id(), authorizedDefinitionIds)) {
                projections.add(project(metric, concepts(metric.id(), resolutionPolicy)));
            }
        }
        for (RelationDefinition relation : permittedSnapshot.relations()) {
            if (authorized(relation.id(), authorizedDefinitionIds)) {
                projections.add(project(relation, concepts(relation.id(), resolutionPolicy)));
            }
        }
        if (projections.size() > SegmentModelLimits.MAX_PROJECTIONS) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "The permitted snapshot exceeds the projection limit");
        }
        List<SegmentModelRequest.EventSelectorProjection> eventSelectors = new ArrayList<>();
        for (EventSelectorDefinition selector : permittedSnapshot.eventSelectors()) {
            if (EventSelectorDependencyClosure.isAuthorized(selector, authorizedDefinitionIds)) {
                eventSelectors.add(project(selector));
            }
        }
        MetadataSnapshotPin pin = new MetadataSnapshotPin(
                permittedSnapshot.snapshotId(),
                permittedSnapshot.snapshotVersion(),
                permittedSnapshot.schemaVersion(),
                permittedSnapshot.contentHash());
        SegmentModelRequest.ModelDefaults modelDefaults = modelDefaults(resolutionPolicy, registeredDefaults);
        return new SegmentModelRequest(
                utterance, localeTag, ContractVersions.CURRENT, pin, projections,
                eventSelectors,
                asOf == null || timezone == null
                        ? null
                        : new SegmentModelRequest.TemporalContext(asOf.toString(), timezone.getId()),
                modelDefaults,
                SegmentModelRequest.INSTRUCTIONS);
    }

    private static boolean authorized(String definitionId, Set<String> authorizedDefinitionIds) {
        return authorizedDefinitionIds == null || authorizedDefinitionIds.contains(definitionId);
    }

    private SegmentModelRequest.EventSelectorProjection project(EventSelectorDefinition definition) {
        List<SegmentModelRequest.EventSelectorBindingProjection> bindings = definition.bindings().stream()
                .map(binding -> new SegmentModelRequest.EventSelectorBindingProjection(
                        binding.relationId(), binding.scopeFilterDimensions().stream()
                        .map(dimension -> new MetricFilterProjection(
                                dimension.attributeId(), dimension.allowedOperators(),
                                dimension.domain(), dimension.identifierKind()))
                        .toList()))
                .toList();
        return new SegmentModelRequest.EventSelectorProjection(
                definition.id(), definition.displayName(), definition.aliases(),
                definition.unscopedByDefault(), bindings, definition.disambiguation());
    }

    private static SegmentModelRequest.ModelDefaults modelDefaults(
            ResolutionPolicy policy, RegisteredDefaultSource defaults) {
        if (policy == null || defaults == null || policy.reportingCurrencyDefaultId() == null) {
            return null;
        }
        return defaults.find(policy.reportingCurrencyDefaultId())
                .map(revision -> new SegmentModelRequest.ModelDefaults(revision.value()))
                .orElse(null);
    }

    private MetadataProjection project(AttributeDefinition definition, List<String> concepts) {
        List<EnumAlias> enumAliases = new ArrayList<>();
        for (EnumValueDefinition value : definition.enumValues()) {
            enumAliases.add(new EnumAlias(value.code(), value.aliases(), value.rank()));
        }
        return new MetadataProjection(
                definition.id(),
                concepts,
                definition.kind(),
                definition.displayName(),
                definition.aliases(),
                definition.dataType(),
                definition.allowedOperators(),
                enumAliases,
                List.of(),
                List.of(),
                definition.currencySemantics(),
                null,
                null,
                null,
                null,
                null,
                null,
                definition.disambiguation());
    }

    private MetadataProjection project(MetricDefinition definition, List<String> concepts) {
        List<MetricFilterProjection> filterDimensions = definition.scopeFilterDimensions().stream()
                .map(dimension -> new MetricFilterProjection(
                        dimension.attributeId(), dimension.allowedOperators(),
                        dimension.domain(), dimension.identifierKind()))
                .toList();
        return new MetadataProjection(
                definition.id(),
                concepts,
                definition.kind(),
                definition.displayName(),
                definition.aliases(),
                definition.resultType(),
                definition.allowedOperators(),
                List.of(),
                definition.supportedWindows(),
                filterDimensions,
                definition.currencySemantics(),
                definition.aggregation(),
                definition.relationId(),
                null,
                null,
                null,
                null,
                definition.disambiguation());
    }

    private MetadataProjection project(RelationDefinition definition, List<String> concepts) {
        return new MetadataProjection(
                definition.id(),
                concepts,
                definition.kind(),
                definition.displayName(),
                definition.aliases(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                definition.sourceEntity(),
                definition.targetEntity(),
                definition.cardinality(),
                definition.scope().joinKind(),
                definition.disambiguation());
    }

    private static List<String> concepts(String definitionId, ResolutionPolicy policy) {
        if (policy == null) {
            return List.of();
        }
        return policy.conceptDefinitionIds().entrySet().stream()
                .filter(entry -> entry.getValue().equals(definitionId))
                .map(java.util.Map.Entry::getKey)
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
