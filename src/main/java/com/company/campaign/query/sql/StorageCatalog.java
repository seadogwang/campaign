package com.company.campaign.query.sql;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, versioned, content-hashed server-owned mapping from exact T08 execution identities to
 * PostgreSQL storage facts.
 *
 * <p>It is the only bridge between an accepted plan and physical storage, and it is deliberately typed rather
 * than textual: every schema, table and column is a validated {@link SqlIdentifier}, every entity pin is an
 * exact {@link EntityVersionRef}, every definition pin is an exact ID/subject/schema-version triple, and every
 * relation pin is an exact relation ID plus both source and target entity versions. There is no SQL fragment,
 * no expression, no JSON path and no caller-supplied accessor anywhere in the type.
 *
 * <p>Construction validates every entry — identifier grammar, entity-version agreement, join-shape
 * consistency, currency-column consistency, entity coverage and duplicate lookup keys — and then covers the
 * whole mapping with one canonical content hash. The hash material is a structured record per entry, never a
 * delimiter-concatenated string, so every execution-relevant field is covered exactly once and reordering an
 * equivalent input list or filter map cannot change the hash while changing any field must.
 *
 * <p>Lookup is exact by ID, subject and entity/schema version, so a definition resolved from a different
 * entity schema version, a relation whose source or target version shifted, or a metric whose declared
 * relation or filter field changed fails closed rather than silently binding to the wrong storage.
 */
public final class StorageCatalog {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");

    private final String catalogVersion;
    private final List<EntityStorage> entities;
    private final List<AttributeStorage> attributes;
    private final List<MetricStorage> metrics;
    private final List<RelationStorage> relations;
    private final DataScopeStorage dataScope;
    private final AudienceMembershipStorage audienceMembership;
    private final FxStrategy fxStrategy;
    private final RefundStorage refundStorage;
    private final Map<String, EntityStorage> entityIndex;
    private final Map<String, AttributeStorage> attributeIndex;
    private final Map<String, MetricStorage> metricIndex;
    private final Map<String, RelationStorage> relationIndex;
    private final ContentHash contentHash;

    private StorageCatalog(
            String catalogVersion,
            List<EntityStorage> entities,
            List<AttributeStorage> attributes,
            List<MetricStorage> metrics,
            List<RelationStorage> relations,
            DataScopeStorage dataScope,
            AudienceMembershipStorage audienceMembership,
            FxStrategy fxStrategy,
            RefundStorage refundStorage) {
        requireReferenceId(catalogVersion, "catalogVersion");
        this.catalogVersion = catalogVersion;
        this.dataScope = requirePresent(dataScope, "catalogDataScope");
        this.audienceMembership = audienceMembership;
        this.fxStrategy = fxStrategy;
        this.refundStorage = refundStorage;

        List<EntityStorage> sortedEntities = new ArrayList<>(requirePresent(entities, "catalogEntities"));
        sortedEntities.sort(Comparator
                .comparing((EntityStorage entry) -> entry.entityVersion().entity().name())
                .thenComparing(entry -> entry.entityVersion().schemaVersion().value()));
        this.entities = indexEntities(sortedEntities);

        List<AttributeStorage> sortedAttributes = new ArrayList<>(requirePresent(attributes, "catalogAttributes"));
        sortedAttributes.sort(Comparator
                .comparing(AttributeStorage::definitionId)
                .thenComparing(entry -> entry.subject().name())
                .thenComparing(entry -> entry.entitySchemaVersion().value()));
        this.attributes = indexAttributes(sortedAttributes);

        List<MetricStorage> sortedMetrics = new ArrayList<>(requirePresent(metrics, "catalogMetrics"));
        sortedMetrics.sort(Comparator
                .comparing(MetricStorage::definitionId)
                .thenComparing(entry -> entry.subject().name())
                .thenComparing(entry -> entry.entitySchemaVersion().value()));
        this.metrics = indexMetrics(sortedMetrics);

        List<RelationStorage> sortedRelations = new ArrayList<>(requirePresent(relations, "catalogRelations"));
        sortedRelations.sort(Comparator
                .comparing(RelationStorage::relationId)
                .thenComparing(entry -> entry.sourceEntity().name())
                .thenComparing(entry -> entry.sourceSchemaVersion().value())
                .thenComparing(entry -> entry.targetEntity().name())
                .thenComparing(entry -> entry.targetSchemaVersion().value()));
        this.relations = indexRelations(sortedRelations);

        Map<String, EntityStorage> entityByKey = new LinkedHashMap<>();
        for (EntityStorage entry : this.entities) {
            entityByKey.put(entityKey(entry.entityVersion()), entry);
        }
        this.entityIndex = Map.copyOf(entityByKey);

        Map<String, AttributeStorage> attributeByKey = new LinkedHashMap<>();
        for (AttributeStorage entry : this.attributes) {
            attributeByKey.put(definitionKey(entry.definitionId(), entry.subject(), entry.entitySchemaVersion()),
                    entry);
        }
        this.attributeIndex = Map.copyOf(attributeByKey);

        Map<String, MetricStorage> metricByKey = new LinkedHashMap<>();
        for (MetricStorage entry : this.metrics) {
            metricByKey.put(definitionKey(entry.definitionId(), entry.subject(), entry.entitySchemaVersion()), entry);
        }
        this.metricIndex = Map.copyOf(metricByKey);

        Map<String, RelationStorage> relationByKey = new LinkedHashMap<>();
        for (RelationStorage entry : this.relations) {
            relationByKey.put(relationKey(entry.relationId(), entry.sourceEntity(), entry.sourceSchemaVersion(),
                    entry.targetEntity(), entry.targetSchemaVersion()), entry);
        }
        this.relationIndex = Map.copyOf(relationByKey);

        requireEntityCoverage();
        this.contentHash = CANONICAL_JSON.hash(material());
    }

    /**
     * Publishes a validated, content-hashed storage catalog.
     *
     * <p>{@code fxStrategy} and {@code refundStorage} are optional: when absent, a plan that needs currency
     * conversion or refund subtraction fails closed at compile time rather than being approximated.
     */
    public static StorageCatalog publish(
            String catalogVersion,
            List<EntityStorage> entities,
            List<AttributeStorage> attributes,
            List<MetricStorage> metrics,
            List<RelationStorage> relations,
            DataScopeStorage dataScope,
            FxStrategy fxStrategy,
            RefundStorage refundStorage) {
        return new StorageCatalog(catalogVersion, entities, attributes, metrics, relations, dataScope, null,
                fxStrategy, refundStorage);
    }

    /**
     * Publishes a validated, content-hashed storage catalog that also binds the audience membership table.
     *
     * <p>{@code audienceMembership} is required only for a plan that references an immutable Audience Version;
     * a catalog without it still compiles every reference-free plan, and a reference compiled against it fails
     * closed rather than weakening to an unguarded membership test.
     */
    public static StorageCatalog publish(
            String catalogVersion,
            List<EntityStorage> entities,
            List<AttributeStorage> attributes,
            List<MetricStorage> metrics,
            List<RelationStorage> relations,
            DataScopeStorage dataScope,
            AudienceMembershipStorage audienceMembership,
            FxStrategy fxStrategy,
            RefundStorage refundStorage) {
        return new StorageCatalog(catalogVersion, entities, attributes, metrics, relations, dataScope,
                audienceMembership, fxStrategy, refundStorage);
    }

    /** The server-owned catalog version pinned into every compiled artifact. */
    public String catalogVersion() {
        return catalogVersion;
    }

    /** The canonical hash covering every mapping entry this catalog carries. */
    public ContentHash contentHash() {
        return contentHash;
    }

    /** Entity storage entries in deterministic entity-then-version order. */
    public List<EntityStorage> entities() {
        return entities;
    }

    /** Attribute storage entries in deterministic order. */
    public List<AttributeStorage> attributes() {
        return attributes;
    }

    /** Metric storage entries in deterministic order. */
    public List<MetricStorage> metrics() {
        return metrics;
    }

    /** Relation storage entries in deterministic order. */
    public List<RelationStorage> relations() {
        return relations;
    }

    /** The mandatory DataScope membership table and column binding. */
    public DataScopeStorage dataScope() {
        return dataScope;
    }

    /** The optional audience membership table, or {@code null} when references are unsupported. */
    public AudienceMembershipStorage audienceMembership() {
        return audienceMembership;
    }

    /** The optional FX storage strategy, or {@code null} when exact conversion is unsupported. */
    public FxStrategy fxStrategy() {
        return fxStrategy;
    }

    /** The optional refund storage, or {@code null} when refund subtraction is unsupported. */
    public RefundStorage refundStorage() {
        return refundStorage;
    }

    // ------------------------------------------------------------------ exact lookup

    EntityStorage entity(EntityVersionRef entityVersion) {
        return entityVersion == null ? null : entityIndex.get(entityKey(entityVersion));
    }

    AttributeStorage attribute(String definitionId, SubjectKind subject, SchemaVersion entitySchemaVersion) {
        return attributeIndex.get(definitionKey(definitionId, subject, entitySchemaVersion));
    }

    MetricStorage metric(String definitionId, SubjectKind subject, SchemaVersion entitySchemaVersion) {
        return metricIndex.get(definitionKey(definitionId, subject, entitySchemaVersion));
    }

    RelationStorage relation(
            String relationId,
            SubjectKind sourceEntity,
            SchemaVersion sourceSchemaVersion,
            SubjectKind targetEntity,
            SchemaVersion targetSchemaVersion) {
        return relationIndex.get(relationKey(
                relationId, sourceEntity, sourceSchemaVersion, targetEntity, targetSchemaVersion));
    }

    // ------------------------------------------------------------------ indexing

    private List<EntityStorage> indexEntities(List<EntityStorage> entries) {
        Map<String, EntityStorage> seen = new LinkedHashMap<>();
        for (EntityStorage entry : entries) {
            requirePresent(entry, "catalogEntity");
            if (seen.putIfAbsent(entityKey(entry.entityVersion()), entry) != null) {
                throw conflict("catalogEntityDuplicate");
            }
        }
        return List.copyOf(entries);
    }

    private List<AttributeStorage> indexAttributes(List<AttributeStorage> entries) {
        Map<String, AttributeStorage> seen = new LinkedHashMap<>();
        for (AttributeStorage entry : entries) {
            requirePresent(entry, "catalogAttribute");
            if (seen.putIfAbsent(definitionKey(entry.definitionId(), entry.subject(), entry.entitySchemaVersion()),
                    entry) != null) {
                throw conflict("catalogAttributeDuplicate");
            }
        }
        return List.copyOf(entries);
    }

    private List<MetricStorage> indexMetrics(List<MetricStorage> entries) {
        Map<String, MetricStorage> seen = new LinkedHashMap<>();
        for (MetricStorage entry : entries) {
            requirePresent(entry, "catalogMetric");
            if (seen.putIfAbsent(definitionKey(entry.definitionId(), entry.subject(), entry.entitySchemaVersion()),
                    entry) != null) {
                throw conflict("catalogMetricDuplicate");
            }
        }
        return List.copyOf(entries);
    }

    private List<RelationStorage> indexRelations(List<RelationStorage> entries) {
        Map<String, RelationStorage> seen = new LinkedHashMap<>();
        for (RelationStorage entry : entries) {
            requirePresent(entry, "catalogRelation");
            if (seen.putIfAbsent(relationKey(entry.relationId(), entry.sourceEntity(),
                    entry.sourceSchemaVersion(), entry.targetEntity(), entry.targetSchemaVersion()), entry) != null) {
                throw conflict("catalogRelationDuplicate");
            }
        }
        return List.copyOf(entries);
    }

    private void requireEntityCoverage() {
        if (audienceMembership != null) {
            requireEntity(audienceMembership.memberVersion().entity(),
                    audienceMembership.memberVersion().schemaVersion(), "catalogAudienceMembershipEntity");
        }
        for (AttributeStorage entry : attributes) {
            requireEntity(entry.subject(), entry.entitySchemaVersion(), "catalogAttributeEntity");
        }
        for (MetricStorage entry : metrics) {
            requireEntity(entry.subject(), entry.entitySchemaVersion(), "catalogMetricEntity");
        }
        for (RelationStorage entry : relations) {
            requireEntity(entry.sourceEntity(), entry.sourceSchemaVersion(), "catalogRelationSourceEntity");
            requireEntity(entry.targetEntity(), entry.targetSchemaVersion(), "catalogRelationTargetEntity");
        }
    }

    private void requireEntity(SubjectKind entity, SchemaVersion version, String invariant) {
        if (entityIndex.get(entityKey(new EntityVersionRef(entity, version))) == null) {
            throw invalid(invariant);
        }
    }

    // ------------------------------------------------------------------ hash material

    private Material material() {
        return new Material(
                SqlVersions.CATALOG_FORMAT_VERSION,
                catalogVersion,
                entities.stream().map(CanonicalEntity::of).toList(),
                attributes.stream().map(CanonicalAttribute::of).toList(),
                metrics.stream().map(CanonicalMetric::of).toList(),
                relations.stream().map(CanonicalRelation::of).toList(),
                CanonicalDataScope.of(dataScope),
                audienceMembership == null ? null : CanonicalAudienceMembership.of(audienceMembership),
                fxStrategy == null ? null : CanonicalFx.of(fxStrategy),
                refundStorage == null ? null : CanonicalRefund.of(refundStorage));
    }

    // ------------------------------------------------------------------ keys and validation

    private static String entityKey(EntityVersionRef entityVersion) {
        return entityVersion.entity().name() + "|" + entityVersion.schemaVersion().value();
    }

    private static String definitionKey(String definitionId, SubjectKind subject, SchemaVersion version) {
        return (definitionId == null ? "" : definitionId) + "|" + (subject == null ? "" : subject.name()) + "|"
                + (version == null ? "" : version.value());
    }

    private static String relationKey(
            String relationId,
            SubjectKind sourceEntity,
            SchemaVersion sourceVersion,
            SubjectKind targetEntity,
            SchemaVersion targetVersion) {
        return (relationId == null ? "" : relationId) + "|" + (sourceEntity == null ? "" : sourceEntity.name())
                + "|" + (sourceVersion == null ? "" : sourceVersion.value()) + "|"
                + (targetEntity == null ? "" : targetEntity.name()) + "|"
                + (targetVersion == null ? "" : targetVersion.value());
    }

    static void requireReferenceId(String value, String invariant) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw invalid(invariant);
        }
    }

    static void requireFieldName(String value, String invariant) {
        if (value == null || !FIELD_NAME.matcher(value).matches()) {
            throw invalid(invariant);
        }
    }

    static void requireCurrency(String value, String invariant) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw invalid(invariant);
        }
    }

    static <T> T requirePresent(T value, String invariant) {
        if (value == null) {
            throw invalid(invariant);
        }
        return value;
    }

    static void requireCurrencyColumn(SemanticDataType dataType, SqlIdentifier currencyColumn, String invariant) {
        boolean money = dataType == SemanticDataType.MONEY;
        if (money != (currencyColumn != null)) {
            throw invalid(invariant);
        }
    }

    static List<AggregateFunction> orderedAggregates(List<AggregateFunction> aggregates, String invariant) {
        if (aggregates == null || aggregates.isEmpty()) {
            throw invalid(invariant);
        }
        List<AggregateFunction> copy = new ArrayList<>(aggregates);
        copy.sort(Comparator.comparingInt(value -> value.ordinal()));
        for (int index = 0; index < copy.size(); index++) {
            requirePresent(copy.get(index), invariant);
            if (index > 0 && copy.get(index - 1) == copy.get(index)) {
                throw invalid(invariant);
            }
        }
        return List.copyOf(copy);
    }

    static List<EmptySetBehavior> orderedEmptySets(List<EmptySetBehavior> behaviors, String invariant) {
        if (behaviors == null || behaviors.isEmpty()) {
            throw invalid(invariant);
        }
        List<EmptySetBehavior> copy = new ArrayList<>(behaviors);
        copy.sort(Comparator.comparingInt(value -> value.ordinal()));
        for (int index = 0; index < copy.size(); index++) {
            requirePresent(copy.get(index), invariant);
            if (index > 0 && copy.get(index - 1) == copy.get(index)) {
                throw invalid(invariant);
            }
        }
        return List.copyOf(copy);
    }

    static Map<String, SqlIdentifier> orderedFilterColumns(Map<String, SqlIdentifier> filterColumns, String invariant) {
        Map<String, SqlIdentifier> copy = new LinkedHashMap<>();
        if (filterColumns != null) {
            for (Map.Entry<String, SqlIdentifier> entry : filterColumns.entrySet()) {
                requireFieldName(entry.getKey(), invariant);
                if (entry.getValue() == null) {
                    throw invalid(invariant);
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        List<String> keys = new ArrayList<>(copy.keySet());
        keys.sort(Comparator.naturalOrder());
        Map<String, SqlIdentifier> ordered = new LinkedHashMap<>();
        for (String key : keys) {
            ordered.put(key, copy.get(key));
        }
        return Map.copyOf(ordered);
    }

    static List<String> orderedCurrencies(List<String> currencies, String invariant) {
        if (currencies == null || currencies.isEmpty()) {
            throw invalid(invariant);
        }
        List<String> copy = new ArrayList<>(currencies);
        copy.sort(Comparator.naturalOrder());
        for (int index = 0; index < copy.size(); index++) {
            requireCurrency(copy.get(index), invariant);
            if (index > 0 && copy.get(index - 1).equals(copy.get(index))) {
                throw invalid(invariant);
            }
        }
        return List.copyOf(copy);
    }

    static SqlException invalid(String invariant) {
        return new SqlException(SqlErrorCodes.SQL_CATALOG_INVALID, invariant,
                "A server-owned storage catalog entry is inconsistent and cannot be published.");
    }

    static SqlException conflict(String invariant) {
        return new SqlException(SqlErrorCodes.SQL_MAPPING_CONFLICT, invariant,
                "Two server-owned storage catalog entries claim the same exact lookup key.");
    }

    // ------------------------------------------------------------------ entries

    /**
     * One entity and schema version bound to its physical table, primary-key column and its own exact
     * tenant/program context columns.
     *
     * <p>The context columns are mandatory and explicit per entity shape: two entity tables are never assumed
     * to share tenant/program columns, because that invariant is only true when the catalog says so and binds
     * it into the catalog hash. Omitting them is a contradictory catalog and fails closed at publication.
     */
    public record EntityStorage(
            EntityVersionRef entityVersion,
            TableRef table,
            SqlIdentifier primaryKeyColumn,
            ContextStorage context) {

        public EntityStorage {
            requirePresent(entityVersion, "entityVersion");
            requirePresent(table, "entityTable");
            requirePresent(primaryKeyColumn, "entityPrimaryKey");
            requirePresent(context, "entityContext");
        }
    }

    /** One attribute definition bound to its typed value column and its optional currency column. */
    public record AttributeStorage(
            String definitionId,
            SubjectKind subject,
            SchemaVersion entitySchemaVersion,
            SemanticDataType dataType,
            SqlIdentifier valueColumn,
            SqlIdentifier currencyColumn) {

        public AttributeStorage {
            requireReferenceId(definitionId, "attributeDefinitionId");
            requirePresent(subject, "attributeSubject");
            requirePresent(entitySchemaVersion, "attributeEntitySchemaVersion");
            requirePresent(dataType, "attributeDataType");
            requirePresent(valueColumn, "attributeValueColumn");
            requireCurrencyColumn(dataType, currencyColumn, "attributeCurrencyColumn");
        }
    }

    /**
     * One metric definition bound to its exact relation, input/currency/event-time/filter columns and the
     * aggregate and empty-set behavior the storage supports.
     */
    public record MetricStorage(
            String definitionId,
            SubjectKind subject,
            SchemaVersion entitySchemaVersion,
            SemanticDataType resultType,
            String relationId,
            String inputField,
            SqlIdentifier inputColumn,
            String currencyField,
            SqlIdentifier currencyColumn,
            String eventTimeField,
            SqlIdentifier eventTimeColumn,
            Map<String, SqlIdentifier> filterColumns,
            List<AggregateFunction> supportedAggregates,
            List<EmptySetBehavior> supportedEmptySetBehaviors,
            boolean refundSupported) {

        public MetricStorage {
            requireReferenceId(definitionId, "metricDefinitionId");
            requirePresent(subject, "metricSubject");
            requirePresent(entitySchemaVersion, "metricEntitySchemaVersion");
            requirePresent(resultType, "metricResultType");
            requireReferenceId(relationId, "metricRelationId");
            if (inputField != null) {
                requireFieldName(inputField, "metricInputField");
            }
            if (currencyField != null) {
                requireFieldName(currencyField, "metricCurrencyField");
            }
            if (eventTimeField != null) {
                requireFieldName(eventTimeField, "metricEventTimeField");
            }
            requireCurrencyColumn(resultType, currencyColumn, "metricCurrencyColumn");
            if ((inputField == null) != (inputColumn == null) || (currencyField == null) != (currencyColumn == null)
                    || (eventTimeField == null) != (eventTimeColumn == null)) {
                throw invalid("metricFieldColumn");
            }
            filterColumns = orderedFilterColumns(filterColumns, "metricFilterColumns");
            supportedAggregates = orderedAggregates(supportedAggregates, "metricAggregates");
            supportedEmptySetBehaviors = orderedEmptySets(supportedEmptySetBehaviors, "metricEmptySets");
        }
    }

    /**
     * One relation bound to its source/target entity versions, its join-kind shape, both join-key columns and
     * its target event-time column.
     */
    public record RelationStorage(
            String relationId,
            SubjectKind sourceEntity,
            SchemaVersion sourceSchemaVersion,
            SubjectKind targetEntity,
            SchemaVersion targetSchemaVersion,
            RelationJoinKind joinKind,
            String sourceJoinField,
            SqlIdentifier sourceKeyColumn,
            String targetJoinField,
            SqlIdentifier targetKeyColumn,
            String targetEventTimeField,
            SqlIdentifier targetEventTimeColumn) {

        public RelationStorage {
            requireReferenceId(relationId, "relationId");
            requirePresent(sourceEntity, "relationSourceEntity");
            requirePresent(sourceSchemaVersion, "relationSourceSchemaVersion");
            requirePresent(targetEntity, "relationTargetEntity");
            requirePresent(targetSchemaVersion, "relationTargetSchemaVersion");
            requirePresent(joinKind, "relationJoinKind");
            if ((targetEventTimeField == null) != (targetEventTimeColumn == null)) {
                throw invalid("relationEventTime");
            }
            switch (joinKind) {
                case CORRELATED_BY_SOURCE_KEY -> {
                    if (sourceJoinField == null || sourceKeyColumn == null
                            || targetJoinField == null || targetKeyColumn == null) {
                        throw invalid("relationJoinShape");
                    }
                }
                case TARGET_FILTERED -> {
                    if (sourceJoinField != null || sourceKeyColumn != null
                            || targetJoinField == null || targetKeyColumn == null) {
                        throw invalid("relationJoinShape");
                    }
                }
                case GLOBAL -> {
                    if (sourceJoinField != null || sourceKeyColumn != null
                            || targetJoinField != null || targetKeyColumn != null) {
                        throw invalid("relationJoinShape");
                    }
                }
            }
            if (sourceJoinField != null) {
                requireFieldName(sourceJoinField, "relationSourceJoinField");
            }
            if (targetJoinField != null) {
                requireFieldName(targetJoinField, "relationTargetJoinField");
            }
            if (targetEventTimeField != null) {
                requireFieldName(targetEventTimeField, "relationEventTimeField");
            }
        }
    }

    /**
     * The mandatory, exact tenant and program columns of one tenant-owned table shape.
     *
     * <p>Every entity table and the refund table carries its own explicit mapping, and the compiler constrains
     * every accessed tenant-owned alias from the trusted context using exactly that mapping. Two tables are
     * never assumed to share tenant/program columns: the assumption is only true when the catalog declares it,
     * and a changed mapping changes the catalog hash.
     */
    public record ContextStorage(SqlIdentifier tenantColumn, SqlIdentifier programColumn) {
        public ContextStorage {
            requirePresent(tenantColumn, "contextTenantColumn");
            requirePresent(programColumn, "contextProgramColumn");
        }
    }

    /** The DataScope membership table and the columns that bind a scope to its members. */
    public record DataScopeStorage(
            TableRef table,
            SqlIdentifier scopeIdColumn,
            SqlIdentifier memberIdColumn,
            SqlIdentifier tenantColumn,
            SqlIdentifier programColumn) {

        public DataScopeStorage {
            requirePresent(table, "dataScopeTable");
            requirePresent(scopeIdColumn, "dataScopeScopeIdColumn");
            requirePresent(memberIdColumn, "dataScopeMemberIdColumn");
            requirePresent(tenantColumn, "dataScopeTenantColumn");
            requirePresent(programColumn, "dataScopeProgramColumn");
        }
    }

    /**
     * The optional Existing Audience Reference membership binding: one server-owned table that answers "is this
     * member in this exact immutable audience version?".
     *
     * <p>Every column is an allowlisted {@link SqlIdentifier} or {@link TableRef}, so no caller-supplied text
     * can reach a statement. The version identity and content hash are separate columns precisely because a
     * reference names one frozen version: a lookup that keyed only on an audience identity would silently
     * answer a moving target, which is exactly what a reference must never do.
     *
     * <p>It is optional, and a reference-bearing plan compiled against a catalog that does not declare it fails
     * closed. Omitting the binding can therefore never degrade to "no membership narrowing at all".
     */
    public record AudienceMembershipStorage(
            EntityVersionRef memberVersion,
            TableRef table,
            SqlIdentifier audienceIdColumn,
            SqlIdentifier versionIdColumn,
            SqlIdentifier versionHashColumn,
            SqlIdentifier memberIdColumn,
            ContextStorage context) {

        public AudienceMembershipStorage {
            // The member identity the table keys on: a reference-only plan has no other entity pin, and a
            // membership row that could belong to any entity version would not be a membership fact at all.
            requirePresent(memberVersion, "audienceMembershipMemberVersion");
            requirePresent(table, "audienceMembershipTable");
            requirePresent(audienceIdColumn, "audienceMembershipAudienceIdColumn");
            requirePresent(versionIdColumn, "audienceMembershipVersionIdColumn");
            requirePresent(versionHashColumn, "audienceMembershipVersionHashColumn");
            requirePresent(memberIdColumn, "audienceMembershipMemberIdColumn");
            requirePresent(context, "audienceMembershipContext");
        }
    }

    /** The optional FX rate storage strategy; its declared currency vocabulary is closed and pinned. */
    public record FxStrategy(
            TableRef rateTable,
            SqlIdentifier currencyColumn,
            SqlIdentifier valueColumn,
            SqlIdentifier snapshotColumn,
            List<String> supportedSourceCurrencies) {

        public FxStrategy {
            requirePresent(rateTable, "fxRateTable");
            requirePresent(currencyColumn, "fxCurrencyColumn");
            requirePresent(valueColumn, "fxValueColumn");
            requirePresent(snapshotColumn, "fxSnapshotColumn");
            supportedSourceCurrencies = orderedCurrencies(supportedSourceCurrencies, "fxSupportedCurrencies");
        }
    }

    /**
     * The optional refund table, the columns that correlate a refund to the order it settles and its own exact
     * tenant/program context columns.
     *
     * <p>The context columns are mandatory for the same reason as an entity's: every refund subquery is
     * tenant/program constrained from the trusted context, never by an assumed shared column set.
     */
    public record RefundStorage(
            TableRef table,
            SqlIdentifier orderIdColumn,
            SqlIdentifier amountColumn,
            ContextStorage context) {

        public RefundStorage {
            requirePresent(table, "refundTable");
            requirePresent(orderIdColumn, "refundOrderIdColumn");
            requirePresent(amountColumn, "refundAmountColumn");
            requirePresent(context, "refundContext");
        }
    }

    // ------------------------------------------------------------------ canonical material

    private record CanonicalTable(String schema, String table) {
        static CanonicalTable of(TableRef ref) {
            return new CanonicalTable(ref.schema().value(), ref.table().value());
        }
    }

    private record CanonicalEntity(
            String entity, String schemaVersion, CanonicalTable table, String primaryKey,
            CanonicalContext context) {
        static CanonicalEntity of(EntityStorage entry) {
            return new CanonicalEntity(
                    entry.entityVersion().entity().name(),
                    entry.entityVersion().schemaVersion().value(),
                    CanonicalTable.of(entry.table()),
                    entry.primaryKeyColumn().value(),
                    CanonicalContext.of(entry.context()));
        }
    }

    private record CanonicalAttribute(
            String definitionId,
            String subject,
            String entitySchemaVersion,
            String dataType,
            String valueColumn,
            String currencyColumn) {

        static CanonicalAttribute of(AttributeStorage entry) {
            return new CanonicalAttribute(
                    entry.definitionId(),
                    entry.subject().name(),
                    entry.entitySchemaVersion().value(),
                    entry.dataType().name(),
                    entry.valueColumn().value(),
                    entry.currencyColumn() == null ? null : entry.currencyColumn().value());
        }
    }

    private record CanonicalFilter(String key, String column) {
    }

    private record CanonicalMetric(
            String definitionId,
            String subject,
            String entitySchemaVersion,
            String resultType,
            String relationId,
            String inputField,
            String inputColumn,
            String currencyField,
            String currencyColumn,
            String eventTimeField,
            String eventTimeColumn,
            List<CanonicalFilter> filterColumns,
            List<String> supportedAggregates,
            List<String> supportedEmptySetBehaviors,
            boolean refundSupported) {

        static CanonicalMetric of(MetricStorage entry) {
            List<CanonicalFilter> filters = entry.filterColumns().entrySet().stream()
                    .map(filter -> new CanonicalFilter(filter.getKey(), filter.getValue().value()))
                    .sorted(Comparator.comparing(CanonicalFilter::key))
                    .toList();
            return new CanonicalMetric(
                    entry.definitionId(),
                    entry.subject().name(),
                    entry.entitySchemaVersion().value(),
                    entry.resultType().name(),
                    entry.relationId(),
                    entry.inputField(),
                    entry.inputColumn() == null ? null : entry.inputColumn().value(),
                    entry.currencyField(),
                    entry.currencyColumn() == null ? null : entry.currencyColumn().value(),
                    entry.eventTimeField(),
                    entry.eventTimeColumn() == null ? null : entry.eventTimeColumn().value(),
                    filters,
                    entry.supportedAggregates().stream().map(value -> value.name()).toList(),
                    entry.supportedEmptySetBehaviors().stream().map(value -> value.name()).toList(),
                    entry.refundSupported());
        }
    }

    private record CanonicalRelation(
            String relationId,
            String sourceEntity,
            String sourceSchemaVersion,
            String targetEntity,
            String targetSchemaVersion,
            String joinKind,
            String sourceJoinField,
            String sourceKeyColumn,
            String targetJoinField,
            String targetKeyColumn,
            String targetEventTimeField,
            String targetEventTimeColumn) {

        static CanonicalRelation of(RelationStorage entry) {
            return new CanonicalRelation(
                    entry.relationId(),
                    entry.sourceEntity().name(),
                    entry.sourceSchemaVersion().value(),
                    entry.targetEntity().name(),
                    entry.targetSchemaVersion().value(),
                    entry.joinKind().name(),
                    entry.sourceJoinField(),
                    entry.sourceKeyColumn() == null ? null : entry.sourceKeyColumn().value(),
                    entry.targetJoinField(),
                    entry.targetKeyColumn() == null ? null : entry.targetKeyColumn().value(),
                    entry.targetEventTimeField(),
                    entry.targetEventTimeColumn() == null ? null : entry.targetEventTimeColumn().value());
        }
    }

    private record CanonicalContext(String tenantColumn, String programColumn) {
        static CanonicalContext of(ContextStorage entry) {
            return new CanonicalContext(entry.tenantColumn().value(), entry.programColumn().value());
        }
    }

    private record CanonicalDataScope(
            CanonicalTable table,
            String scopeIdColumn,
            String memberIdColumn,
            String tenantColumn,
            String programColumn) {

        static CanonicalDataScope of(DataScopeStorage entry) {
            return new CanonicalDataScope(
                    CanonicalTable.of(entry.table()),
                    entry.scopeIdColumn().value(),
                    entry.memberIdColumn().value(),
                    entry.tenantColumn().value(),
                    entry.programColumn().value());
        }
    }

    private record CanonicalAudienceMembership(
            String memberEntity,
            String memberSchemaVersion,
            CanonicalTable table,
            String audienceIdColumn,
            String versionIdColumn,
            String versionHashColumn,
            String memberIdColumn,
            CanonicalContext context) {

        static CanonicalAudienceMembership of(AudienceMembershipStorage entry) {
            return new CanonicalAudienceMembership(
                    entry.memberVersion().entity().name(),
                    entry.memberVersion().schemaVersion().value(),
                    CanonicalTable.of(entry.table()),
                    entry.audienceIdColumn().value(),
                    entry.versionIdColumn().value(),
                    entry.versionHashColumn().value(),
                    entry.memberIdColumn().value(),
                    CanonicalContext.of(entry.context()));
        }
    }

    private record CanonicalFx(
            CanonicalTable rateTable,
            String currencyColumn,
            String valueColumn,
            String snapshotColumn,
            List<String> supportedSourceCurrencies) {

        static CanonicalFx of(FxStrategy entry) {
            return new CanonicalFx(
                    CanonicalTable.of(entry.rateTable()),
                    entry.currencyColumn().value(),
                    entry.valueColumn().value(),
                    entry.snapshotColumn().value(),
                    entry.supportedSourceCurrencies());
        }
    }

    private record CanonicalRefund(
            CanonicalTable table, String orderIdColumn, String amountColumn, CanonicalContext context) {
        static CanonicalRefund of(RefundStorage entry) {
            return new CanonicalRefund(
                    CanonicalTable.of(entry.table()),
                    entry.orderIdColumn().value(),
                    entry.amountColumn().value(),
                    CanonicalContext.of(entry.context()));
        }
    }

    private record Material(
            String catalogFormatVersion,
            String catalogVersion,
            List<CanonicalEntity> entities,
            List<CanonicalAttribute> attributes,
            List<CanonicalMetric> metrics,
            List<CanonicalRelation> relations,
            CanonicalDataScope dataScope,
            CanonicalAudienceMembership audienceMembership,
            CanonicalFx fxStrategy,
            CanonicalRefund refundStorage) {
    }

    // ------------------------------------------------------------------ equality

    @Override
    public boolean equals(Object other) {
        return other instanceof StorageCatalog catalog && Objects.equals(contentHash, catalog.contentHash);
    }

    @Override
    public int hashCode() {
        return contentHash.hashCode();
    }

    @Override
    public String toString() {
        return "StorageCatalog[catalogVersion=" + catalogVersion
                + ", entities=" + entities.size()
                + ", attributes=" + attributes.size()
                + ", metrics=" + metrics.size()
                + ", relations=" + relations.size()
                + ", contentHash=" + contentHash + ']';
    }

    /** Kept so an empty-but-valid catalog can be expressed by callers that need no bindings. */
    public static StorageCatalog empty(String catalogVersion) {
        return publish(catalogVersion, List.of(), List.of(), List.of(), List.of(),
                new DataScopeStorage(
                        new TableRef(SqlIdentifier.of("public"), SqlIdentifier.of("data_scope_members")),
                        SqlIdentifier.of("scope_id"), SqlIdentifier.of("member_id"),
                        SqlIdentifier.of("tenant_id"), SqlIdentifier.of("program_id")),
                null, null);
    }
}