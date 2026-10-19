package com.company.campaign.builder.segment;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.FilterDomain;
import com.company.campaign.metadata.domain.FilterIdentifierKind;
import com.company.campaign.metadata.domain.RelationCardinality;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The one immutable, bounded, host-owned request handed to a {@link SegmentIntentModelPort}.
 *
 * <p>It carries the user utterance and locale, the exact Intent schema/version identifier, an exact Metadata
 * snapshot pin and a bounded safe projection of already-permitted Metadata definitions. It deliberately has no
 * token, credential, tenant, user, DataScope, permission, storage schema/table/column, SQL catalog, Gate,
 * Save/Publish or Runtime field, so a provider can never be handed restricted host data.
 *
 * <p>Every bounded list is defensively copied and every text is length-bounded at construction. The
 * {@code instructions} text is the single fixed statement of the response contract.
 */
public record SegmentModelRequest(
        String utterance,
        String localeTag,
        SchemaVersion intentSchemaVersion,
        MetadataSnapshotPin metadataSnapshot,
        List<MetadataProjection> permittedDefinitions,
        List<EventSelectorProjection> eventSelectors,
        TemporalContext temporalContext,
        ModelDefaults modelDefaults,
        String instructions,
        SegmentModelCallContext callContext
) {

    /** The fixed instruction text: one raw JSON object, no Markdown, prose, SQL or trusted context. */
    public static final String INSTRUCTIONS = SegmentPromptContract.TEXT;

    /**
     * The one first-pass request shape, kept for every caller that does not express a repair attempt.
     *
     * <p>A request built through this constructor is exactly a {@code FIRST_PASS} call, so the accepted
     * behavior of the adapter is unchanged when no host repair policy is injected.
     */
    public SegmentModelRequest(
            String utterance,
            String localeTag,
            SchemaVersion intentSchemaVersion,
            MetadataSnapshotPin metadataSnapshot,
            List<MetadataProjection> permittedDefinitions,
            List<EventSelectorProjection> eventSelectors,
            TemporalContext temporalContext,
            ModelDefaults modelDefaults,
            String instructions) {
        this(utterance, localeTag, intentSchemaVersion, metadataSnapshot, permittedDefinitions, eventSelectors,
                temporalContext, modelDefaults, instructions, SegmentModelCallContext.FIRST_PASS);
    }

    public SegmentModelRequest {
        utterance = boundedText(utterance, "utterance", SegmentModelLimits.MAX_UTTERANCE_LENGTH, false);
        localeTag = boundedText(localeTag, "localeTag", SegmentModelLimits.MAX_LOCALE_TAG_LENGTH, false);
        if (intentSchemaVersion == null) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request requires an Intent schema version");
        }
        if (metadataSnapshot == null) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request requires a Metadata snapshot pin");
        }
        if (permittedDefinitions == null || permittedDefinitions.isEmpty()) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID,
                    "A model request requires at least one permitted definition");
        }
        if (permittedDefinitions.size() > SegmentModelLimits.MAX_PROJECTIONS) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID,
                    "A model request exceeds the permitted-definition limit");
        }
        permittedDefinitions = List.copyOf(new ArrayList<>(permittedDefinitions));
        eventSelectors = eventSelectors == null ? List.of() : List.copyOf(new ArrayList<>(eventSelectors));
        if (eventSelectors.size() > SegmentModelLimits.MAX_PROJECTIONS) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID,
                    "A model request exceeds the event-selector projection limit");
        }
        if (instructions == null || !instructions.equals(INSTRUCTIONS)) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request requires the fixed instruction text");
        }
        if (callContext == null) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request requires its host-owned call context");
        }
    }

    /**
     * The same frozen safe request, re-stamped for the single bounded schema-repair call.
     *
     * <p>Only the host-owned call kind and the captured stable schema issue codes change: every projected
     * definition, the Metadata pin, the temporal anchors, the registered defaults and the fixed instruction text
     * are byte-identical to the first-pass request, so a repair can never widen the Metadata projection.
     */
    public SegmentModelRequest forSchemaRepair(List<String> schemaIssueCodes) {
        return new SegmentModelRequest(
                utterance, localeTag, intentSchemaVersion, metadataSnapshot, permittedDefinitions, eventSelectors,
                temporalContext, modelDefaults, instructions, SegmentModelCallContext.schemaRepair(schemaIssueCodes));
    }

    /** Safe semantic projection of a governed event selector. */
    public record EventSelectorProjection(
            String stableId,
            String displayName,
            List<String> aliases,
            boolean unscopedByDefault,
            List<EventSelectorBindingProjection> bindings,
            String disambiguation) {
        public EventSelectorProjection {
            stableId = boundedText(stableId, "eventSelector.stableId", 128, false);
            displayName = boundedText(displayName, "eventSelector.displayName",
                    SegmentModelLimits.MAX_PROJECTED_TEXT_LENGTH, false);
            aliases = boundedTextList(aliases, "eventSelector.aliases",
                    SegmentModelLimits.MAX_ALIASES_PER_DEFINITION);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            if (bindings.isEmpty() || bindings.size() > SegmentModelLimits.MAX_PROJECTIONS) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID,
                        "An event selector requires bounded relation bindings");
            }
            disambiguation = disambiguation == null ? null : boundedText(
                    disambiguation, "eventSelector.disambiguation",
                    SegmentModelLimits.MAX_PROJECTED_TEXT_LENGTH, false);
        }
    }

    public record EventSelectorBindingProjection(
            String relationId,
            List<MetricFilterProjection> scopeFilterDimensions) {
        public EventSelectorBindingProjection {
            relationId = boundedText(relationId, "eventSelector.relationId", 128, false);
            scopeFilterDimensions = scopeFilterDimensions == null
                    ? List.of()
                    : List.copyOf(scopeFilterDimensions);
        }
    }

    /** Safe host-projected temporal anchors needed to interpret relative and calendar-date language exactly. */
    public record TemporalContext(String asOf, String timezone) {

        public TemporalContext {
            asOf = boundedText(asOf, "asOf", 64, false);
            timezone = boundedText(timezone, "timezone", 64, false);
            try {
                Instant.parse(asOf);
                ZoneId.of(timezone);
            } catch (RuntimeException failure) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID,
                        "A model temporal context requires an ISO instant and valid timezone");
            }
        }
    }

    /** Safe registered business defaults that the model may rely on without inventing provenance. */
    public record ModelDefaults(String reportingCurrency) {

        public ModelDefaults {
            reportingCurrency = boundedText(reportingCurrency, "reportingCurrency", 3, false);
            if (!reportingCurrency.matches("[A-Z]{3}")) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID,
                        "A model reporting currency requires an ISO-4217 code");
            }
        }
    }

    /** The exact Metadata snapshot identity pinned into a request. */
    public record MetadataSnapshotPin(
            String snapshotId,
            String snapshotVersion,
            SchemaVersion metadataSchemaVersion,
            ContentHash contentHash
    ) {

        public MetadataSnapshotPin {
            snapshotId = boundedText(snapshotId, "snapshotId", 128, false);
            snapshotVersion = boundedText(snapshotVersion, "snapshotVersion", 128, false);
            if (metadataSchemaVersion == null || contentHash == null) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID, "A snapshot pin requires its schema version and hash");
            }
        }
    }

    /** One bounded, already-permitted Metadata definition, projected for resolution only. */
    public record MetadataProjection(
            String stableId,
            List<String> conceptCodes,
            MetadataKind kind,
            String displayName,
            List<String> aliases,
            SemanticDataType dataType,
            List<ComparisonOperator> allowedOperators,
            List<EnumAlias> enumAliases,
            List<TimeWindowKind> supportedWindows,
            List<MetricFilterProjection> metricFilterDimensions,
            CurrencySemantics currencySemantics,
            AggregateFunction aggregation,
            String metricRelationId,
            SubjectKind relationSourceEntity,
            SubjectKind relationTargetEntity,
            RelationCardinality relationCardinality,
            RelationJoinKind relationJoinKind,
            String disambiguation
    ) {

        public MetadataProjection {
            stableId = boundedText(stableId, "stableId", 128, false);
            conceptCodes = boundedTextList(
                    conceptCodes, "conceptCodes", SegmentModelLimits.MAX_ALIASES_PER_DEFINITION);
            if (kind == null) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID, "A projection requires a definition kind");
            }
            displayName = boundedText(displayName, "displayName", SegmentModelLimits.MAX_PROJECTED_TEXT_LENGTH, false);
            aliases = boundedTextList(aliases, "aliases", SegmentModelLimits.MAX_ALIASES_PER_DEFINITION);
            allowedOperators = allowedOperators == null
                    ? List.of()
                    : List.copyOf(new ArrayList<>(allowedOperators));
            if (allowedOperators.size() > SegmentModelLimits.MAX_OPERATORS_PER_DEFINITION) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID, "A projection exceeds the operator limit");
            }
            enumAliases = enumAliases == null ? List.of() : List.copyOf(new ArrayList<>(enumAliases));
            if (enumAliases.size() > SegmentModelLimits.MAX_ENUM_VALUES_PER_DEFINITION) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID, "A projection exceeds the enum-value limit");
            }
            supportedWindows = supportedWindows == null ? List.of() : List.copyOf(new ArrayList<>(supportedWindows));
            if (supportedWindows.size() > SegmentModelLimits.MAX_WINDOWS_PER_DEFINITION) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID, "A projection exceeds the window limit");
            }
            metricFilterDimensions = metricFilterDimensions == null
                    ? List.of()
                    : List.copyOf(new ArrayList<>(metricFilterDimensions));
            if (metricFilterDimensions.size() > SegmentModelLimits.MAX_METRIC_FILTER_DIMENSIONS) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID,
                        "A projection exceeds the Metric filter-dimension limit");
            }
            disambiguation = disambiguation == null
                    ? null
                    : boundedText(
                            disambiguation, "disambiguation", SegmentModelLimits.MAX_PROJECTED_TEXT_LENGTH, false);
            metricRelationId = metricRelationId == null
                    ? null
                    : boundedText(metricRelationId, "metricRelationId", 128, false);
        }
    }

    /** One permitted Attribute and operator subset usable inside a Metric's scopeFilters array. */
    public record MetricFilterProjection(
            String attributeId,
            List<ComparisonOperator> allowedOperators,
            FilterDomain domain,
            FilterIdentifierKind identifierKind
    ) {

        public MetricFilterProjection {
            attributeId = boundedText(attributeId, "metricFilterAttributeId", 128, false);
            allowedOperators = allowedOperators == null
                    ? List.of()
                    : List.copyOf(new ArrayList<>(allowedOperators));
            if (allowedOperators.isEmpty()
                    || allowedOperators.size() > SegmentModelLimits.MAX_OPERATORS_PER_DEFINITION) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID,
                            "A Metric filter projection requires a bounded operator list");
            }
            if (domain == null || identifierKind == null) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID,
                        "A Metric filter projection requires domain and identifier semantics");
            }
        }
    }

    /** The machine code of one permitted enum value plus its bounded aliases. */
    public record EnumAlias(String code, List<String> aliases, Integer rank) {

        public EnumAlias(String code, List<String> aliases) {
            this(code, aliases, null);
        }

        public EnumAlias {
            code = boundedText(code, "enumCode", 64, false);
            aliases = boundedTextList(aliases, "enumAliases", SegmentModelLimits.MAX_ENUM_ALIASES_PER_VALUE);
        }
    }

    private static String boundedText(String value, String field, int maxLength, boolean nullable) {
        if (value == null) {
            if (nullable) {
                return null;
            }
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request field is missing: " + field);
        }
        if (value.length() > maxLength) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request field exceeds its bound: " + field);
        }
        return value;
    }

    private static List<String> boundedTextList(List<String> values, String field, int maxEntries) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maxEntries) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model request list exceeds its bound: " + field);
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(boundedText(value, field, SegmentModelLimits.MAX_PROJECTED_TEXT_LENGTH, false));
        }
        return List.copyOf(copy);
    }
}
