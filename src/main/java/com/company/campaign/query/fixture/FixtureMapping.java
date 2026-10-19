package com.company.campaign.query.fixture;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, versioned, content-hashed server-owned mapping from accepted Metadata identifiers to closed
 * dataset fields.
 *
 * <p>It is the only bridge between a plan and fixture data, and it is deliberately typed rather than textual:
 * an entry names an exact Metadata kind, definition or relation ID, subject entity and entity schema version,
 * and binds it to one {@link DatasetField} constant. There is no JSON path, no reflection, no accessor name
 * and no caller-supplied expression anywhere in the type, so a caller cannot reach a payload field the server
 * did not map.
 *
 * <p>Construction validates every entry — entity agreement, an actually modelled target entity for a relation,
 * no duplicate keys — and then covers the whole mapping with a canonical content hash. Lookup is exact by ID,
 * subject and entity schema version, so a definition resolved from a different entity schema version, or a
 * relation whose source/target versions shifted, fails closed rather than silently binding to the wrong field.
 */
public final class FixtureMapping {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /** Subject entities the fixture dataset can actually produce rows for. */
    private static final Set<SubjectKind> ROW_ENTITIES = Set.of(SubjectKind.MEMBER, SubjectKind.ORDER, SubjectKind.EVENT);

    private final String mappingVersion;
    private final List<FieldBinding> fields;
    private final List<AttributeBinding> attributes;
    private final List<MetricBinding> metrics;
    private final List<RelationBinding> relations;
    private final Map<String, DatasetField> fieldsByName;
    private final Map<String, AttributeBinding> attributesByKey;
    private final Map<String, MetricBinding> metricsByKey;
    private final Map<String, RelationBinding> relationsByKey;
    private final ContentHash mappingHash;

    private FixtureMapping(
            String mappingVersion,
            List<FieldBinding> fields,
            List<AttributeBinding> attributes,
            List<MetricBinding> metrics,
            List<RelationBinding> relations) {
        this.mappingVersion = requireVersion(mappingVersion);
        this.fields = List.copyOf(requirePresent(fields, "fields"));
        this.attributes = List.copyOf(requirePresent(attributes, "attributes"));
        this.metrics = List.copyOf(requirePresent(metrics, "metrics"));
        this.relations = List.copyOf(requirePresent(relations, "relations"));

        Map<String, DatasetField> fieldIndex = new LinkedHashMap<>();
        for (FieldBinding binding : this.fields) {
            requirePresent(binding, "fieldBinding");
            if (fieldIndex.putIfAbsent(fieldKey(binding.fieldName(), binding.entity()), binding.field()) != null) {
                throw invalid("fieldBindingDuplicate");
            }
        }
        this.fieldsByName = Map.copyOf(fieldIndex);

        Map<String, AttributeBinding> attributeIndex = new LinkedHashMap<>();
        for (AttributeBinding binding : this.attributes) {
            requirePresent(binding, "attributeBinding");
            if (binding.field().entity() != binding.subject()) {
                throw invalid("attributeFieldEntity");
            }
            if (attributeIndex.putIfAbsent(attributeKey(binding.definitionId(), binding.subject(),
                    binding.entitySchemaVersion()), binding) != null) {
                throw invalid("attributeBindingDuplicate");
            }
        }
        Map<String, MetricBinding> metricIndex = new LinkedHashMap<>();
        for (MetricBinding binding : this.metrics) {
            requirePresent(binding, "metricBinding");
            requireRowEntity(binding.targetEntity(), "metricTargetEntity");
            if (binding.inputField() != null && binding.inputField().entity() != binding.targetEntity()) {
                throw invalid("metricInputFieldEntity");
            }
            if (binding.eventTimeField() != null && binding.eventTimeField().entity() != binding.targetEntity()) {
                throw invalid("metricEventTimeFieldEntity");
            }
            for (DatasetField field : binding.filterFields().values()) {
                if (field.entity() != binding.targetEntity()) {
                    throw invalid("metricFilterFieldEntity");
                }
            }
            if (metricIndex.putIfAbsent(metricKey(binding.definitionId(), binding.subject(),
                    binding.entitySchemaVersion()), binding) != null) {
                throw invalid("metricBindingDuplicate");
            }
        }
        Map<String, RelationBinding> relationIndex = new LinkedHashMap<>();
        for (RelationBinding binding : this.relations) {
            requirePresent(binding, "relationBinding");
            requireRowEntity(binding.targetEntity(), "relationTargetEntity");
            if (binding.sourceKeyField().entity() != binding.sourceEntity()) {
                throw invalid("relationSourceKeyFieldEntity");
            }
            if (binding.targetKeyField().entity() != binding.targetEntity()) {
                throw invalid("relationTargetKeyFieldEntity");
            }
            if (relationIndex.putIfAbsent(relationKey(binding.relationId(), binding.sourceEntity(),
                    binding.sourceSchemaVersion(), binding.targetEntity(), binding.targetSchemaVersion()),
                    binding) != null) {
                throw invalid("relationBindingDuplicate");
            }
        }

        this.attributesByKey = Map.copyOf(attributeIndex);
        this.metricsByKey = Map.copyOf(metricIndex);
        this.relationsByKey = Map.copyOf(relationIndex);
        this.mappingHash = CANONICAL_JSON.hash(material());
    }

    /** Publishes a validated, content-hashed mapping. */
    public static FixtureMapping publish(
            String mappingVersion,
            List<FieldBinding> fields,
            List<AttributeBinding> attributes,
            List<MetricBinding> metrics,
            List<RelationBinding> relations) {
        List<FieldBinding> sortedFields = new ArrayList<>(fields == null ? List.of() : fields);
        sortedFields.sort(Comparator.comparing(FieldBinding::sortKey));
        List<AttributeBinding> sortedAttributes = new ArrayList<>(attributes == null ? List.of() : attributes);
        sortedAttributes.sort(Comparator.comparing(AttributeBinding::sortKey));
        List<MetricBinding> sortedMetrics = new ArrayList<>(metrics == null ? List.of() : metrics);
        sortedMetrics.sort(Comparator.comparing(MetricBinding::sortKey));
        List<RelationBinding> sortedRelations = new ArrayList<>(relations == null ? List.of() : relations);
        sortedRelations.sort(Comparator.comparing(RelationBinding::sortKey));
        return new FixtureMapping(mappingVersion, sortedFields, sortedAttributes, sortedMetrics, sortedRelations);
    }

    /** The server-owned mapping version pinned into every execution result. */
    public String mappingVersion() {
        return mappingVersion;
    }

    /** The canonical hash covering every entry this mapping carries. */
    public ContentHash mappingHash() {
        return mappingHash;
    }

    /** Metadata field-name bindings in deterministic order. */
    public List<FieldBinding> fields() {
        return fields;
    }

    /** Attribute bindings in deterministic order. */
    public List<AttributeBinding> attributes() {
        return attributes;
    }

    /** Metric bindings in deterministic order. */
    public List<MetricBinding> metrics() {
        return metrics;
    }

    /** Relation bindings in deterministic order. */
    public List<RelationBinding> relations() {
        return relations;
    }

    /** The dataset field bound to one exact Metadata field name on one entity, or {@code null} when unmapped. */
    DatasetField field(String fieldName, SubjectKind entity) {
        return fieldsByName.get(fieldKey(fieldName, entity));
    }

    /** The exact attribute binding for an ID/subject/entity-version pin, or {@code null} when none exists. */
    AttributeBinding attribute(String definitionId, SubjectKind subject, SchemaVersion entitySchemaVersion) {
        return attributesByKey.get(attributeKey(definitionId, subject, entitySchemaVersion));
    }

    /** The exact metric binding for an ID/subject/entity-version pin, or {@code null} when none exists. */
    MetricBinding metric(String definitionId, SubjectKind subject, SchemaVersion entitySchemaVersion) {
        return metricsByKey.get(metricKey(definitionId, subject, entitySchemaVersion));
    }

    /** The exact relation binding for a full version pin, or {@code null} when none exists. */
    RelationBinding relation(
            String relationId,
            SubjectKind sourceEntity,
            SchemaVersion sourceSchemaVersion,
            SubjectKind targetEntity,
            SchemaVersion targetSchemaVersion) {
        return relationsByKey.get(relationKey(
                relationId, sourceEntity, sourceSchemaVersion, targetEntity, targetSchemaVersion));
    }

    /** One exact Metadata field name on one entity bound to the closed dataset field that carries it. */
    public record FieldBinding(String fieldName, SubjectKind entity, DatasetField field) {

        public FieldBinding {
            requireId(fieldName, "fieldName");
            requirePresent(entity, "fieldEntity");
            requirePresent(field, "field");
            if (field.entity() != entity) {
                throw invalid("fieldEntityMismatch");
            }
        }

        String sortKey() {
            return entity.name() + "|" + fieldName + "|" + field.name();
        }
    }

    /** One attribute definition bound to the closed dataset field that carries it. */
    public record AttributeBinding(
            String definitionId,
            SubjectKind subject,
            SchemaVersion entitySchemaVersion,
            DatasetField field) {

        public AttributeBinding {
            requireId(definitionId, "attributeDefinitionId");
            requirePresent(subject, "attributeSubject");
            requirePresent(entitySchemaVersion, "attributeEntitySchemaVersion");
            requirePresent(field, "attributeField");
        }

        String sortKey() {
            return MetadataKind.ATTRIBUTE.ordinal() + "|" + definitionId + "|" + subject.name()
                    + "|" + entitySchemaVersion.value();
        }
    }

    /**
     * One metric definition bound to its relation, aggregate input field, immutable filter fields and
     * event-time field, all as closed dataset fields.
     */
    public record MetricBinding(
            String definitionId,
            SubjectKind subject,
            SchemaVersion entitySchemaVersion,
            String relationId,
            SubjectKind targetEntity,
            DatasetField inputField,
            Map<String, DatasetField> filterFields,
            DatasetField eventTimeField) {

        public MetricBinding {
            requireId(definitionId, "metricDefinitionId");
            requirePresent(subject, "metricSubject");
            requirePresent(entitySchemaVersion, "metricEntitySchemaVersion");
            requireId(relationId, "metricRelationId");
            requirePresent(targetEntity, "metricTargetEntity");
            Map<String, DatasetField> filters = new LinkedHashMap<>();
            if (filterFields != null) {
                for (Map.Entry<String, DatasetField> entry : filterFields.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                        throw invalid("metricFilterFields");
                    }
                    filters.put(entry.getKey(), entry.getValue());
                }
            }
            filterFields = Map.copyOf(filters);
        }

        String sortKey() {
            return MetadataKind.METRIC.ordinal() + "|" + definitionId + "|" + subject.name()
                    + "|" + entitySchemaVersion.value();
        }
    }

    /** One relation bound to the closed dataset fields that correlate its source and target rows. */
    public record RelationBinding(
            String relationId,
            SubjectKind sourceEntity,
            SchemaVersion sourceSchemaVersion,
            SubjectKind targetEntity,
            SchemaVersion targetSchemaVersion,
            DatasetField sourceKeyField,
            DatasetField targetKeyField) {

        public RelationBinding {
            requireId(relationId, "relationId");
            requirePresent(sourceEntity, "relationSourceEntity");
            requirePresent(sourceSchemaVersion, "relationSourceSchemaVersion");
            requirePresent(targetEntity, "relationTargetEntity");
            requirePresent(targetSchemaVersion, "relationTargetSchemaVersion");
            requirePresent(sourceKeyField, "relationSourceKeyField");
            requirePresent(targetKeyField, "relationTargetKeyField");
        }

        String sortKey() {
            return MetadataKind.RELATION.ordinal() + "|" + relationId + "|" + sourceEntity.name()
                    + "|" + sourceSchemaVersion.value() + "|" + targetEntity.name()
                    + "|" + targetSchemaVersion.value();
        }
    }

    /**
     * The canonical material the mapping hash covers.
     *
     * <p>The {@code sortKey} methods exist only to make {@link #publish} order deterministic; they are
     * deliberately not the hash material, because an ordering key may omit semantic components. This material
     * is instead a structured record per binding, so every execution-relevant fact is covered exactly once and
     * delimiter concatenation can neither hide an omission nor blur two distinct facts into one string. The
     * binding lists are already in deterministic publish order, a metric's filter fields are emitted in
     * ascending key order and nullable components are written as JSON {@code null}, so "absent" can never be
     * confused with a value.
     */
    private Material material() {
        return new Material(
                mappingVersion,
                fields.stream().map(CanonicalField::of).toList(),
                attributes.stream().map(CanonicalAttribute::of).toList(),
                metrics.stream().map(CanonicalMetric::of).toList(),
                relations.stream().map(CanonicalRelation::of).toList());
    }

    private static String fieldKey(String fieldName, SubjectKind entity) {
        return (fieldName == null ? "" : fieldName) + "|" + (entity == null ? "" : entity.name());
    }

    private static String attributeKey(String definitionId, SubjectKind subject, SchemaVersion version) {
        return definitionId + "|" + (subject == null ? "" : subject.name()) + "|"
                + (version == null ? "" : version.value());
    }

    private static String metricKey(String definitionId, SubjectKind subject, SchemaVersion version) {
        return attributeKey(definitionId, subject, version);
    }

    private static String relationKey(
            String relationId,
            SubjectKind sourceEntity,
            SchemaVersion sourceVersion,
            SubjectKind targetEntity,
            SchemaVersion targetVersion) {
        return relationId + "|" + (sourceEntity == null ? "" : sourceEntity.name()) + "|"
                + (sourceVersion == null ? "" : sourceVersion.value()) + "|"
                + (targetEntity == null ? "" : targetEntity.name()) + "|"
                + (targetVersion == null ? "" : targetVersion.value());
    }

    private static void requireRowEntity(SubjectKind entity, String invariant) {
        if (entity == null || !ROW_ENTITIES.contains(entity)) {
            throw invalid(invariant);
        }
    }

    private static String requireVersion(String value) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw invalid("mappingVersion");
        }
        return value;
    }

    private static void requireId(String value, String invariant) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw invalid(invariant);
        }
    }

    private static <T> T requirePresent(T value, String invariant) {
        if (value == null) {
            throw invalid(invariant);
        }
        return value;
    }

    private static FixtureException invalid(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_MAPPING_INVALID, invariant,
                "A server-owned fixture mapping entry is inconsistent and cannot be published.");
    }

    /** Canonical hash material for one Metadata field binding: field name, entity and bound dataset field. */
    private record CanonicalField(String fieldName, String entity, String datasetField) {

        static CanonicalField of(FieldBinding binding) {
            return new CanonicalField(binding.fieldName(), binding.entity().name(), binding.field().name());
        }
    }

    /** Canonical hash material for one attribute binding: definition, subject, version and dataset field. */
    private record CanonicalAttribute(
            String definitionId, String subject, String entitySchemaVersion, String datasetField) {

        static CanonicalAttribute of(AttributeBinding binding) {
            return new CanonicalAttribute(
                    binding.definitionId(),
                    binding.subject().name(),
                    binding.entitySchemaVersion().value(),
                    binding.field().name());
        }
    }

    /**
     * Canonical hash material for one metric binding, covering every fact execution depends on: the relation
     * it aggregates, its target entity, its nullable input field, its complete filter map in ascending key
     * order and its nullable event-time field.
     */
    private record CanonicalMetric(
            String definitionId,
            String subject,
            String entitySchemaVersion,
            String relationId,
            String targetEntity,
            String inputField,
            List<CanonicalFilterField> filterFields,
            String eventTimeField) {

        static CanonicalMetric of(MetricBinding binding) {
            List<CanonicalFilterField> filters = binding.filterFields().entrySet().stream()
                    .map(entry -> new CanonicalFilterField(entry.getKey(), entry.getValue().name()))
                    .sorted(Comparator.comparing(CanonicalFilterField::key))
                    .toList();
            return new CanonicalMetric(
                    binding.definitionId(),
                    binding.subject().name(),
                    binding.entitySchemaVersion().value(),
                    binding.relationId(),
                    binding.targetEntity().name(),
                    binding.inputField() == null ? null : binding.inputField().name(),
                    filters,
                    binding.eventTimeField() == null ? null : binding.eventTimeField().name());
        }
    }

    /** One metric filter's contextual key bound to the closed dataset field that carries it. */
    private record CanonicalFilterField(String key, String datasetField) {
    }

    /**
     * Canonical hash material for one relation binding, covering both version pins and both join-key dataset
     * fields that execution correlates rows on.
     */
    private record CanonicalRelation(
            String relationId,
            String sourceEntity,
            String sourceSchemaVersion,
            String targetEntity,
            String targetSchemaVersion,
            String sourceKeyField,
            String targetKeyField) {

        static CanonicalRelation of(RelationBinding binding) {
            return new CanonicalRelation(
                    binding.relationId(),
                    binding.sourceEntity().name(),
                    binding.sourceSchemaVersion().value(),
                    binding.targetEntity().name(),
                    binding.targetSchemaVersion().value(),
                    binding.sourceKeyField().name(),
                    binding.targetKeyField().name());
        }
    }

    private record Material(
            String mappingVersion,
            List<CanonicalField> fields,
            List<CanonicalAttribute> attributes,
            List<CanonicalMetric> metrics,
            List<CanonicalRelation> relations) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FixtureMapping mapping && Objects.equals(mappingHash, mapping.mappingHash);
    }

    @Override
    public int hashCode() {
        return mappingHash.hashCode();
    }

    @Override
    public String toString() {
        return "FixtureMapping[mappingVersion=" + mappingVersion
                + ", fields=" + fields.size()
                + ", attributes=" + attributes.size()
                + ", metrics=" + metrics.size()
                + ", relations=" + relations.size()
                + ", mappingHash=" + mappingHash + ']';
    }

    /** Kept so an empty mapping is still hashable and expressible by callers that need no bindings. */
    public static FixtureMapping empty(String mappingVersion) {
        return publish(mappingVersion, List.of(), List.of(), List.of(), List.of());
    }
}