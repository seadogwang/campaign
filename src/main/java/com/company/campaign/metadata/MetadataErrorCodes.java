package com.company.campaign.metadata;

/**
 * Stable machine-readable failure codes owned by the T04 Semantic Metadata contract.
 *
 * <p>These values are part of the wire contract: callers and later resolver/planner tasks branch on them,
 * so an existing code is never renamed or reused for a different meaning. A new failure mode gets a new
 * code. Details attached to a failure never carry tenant/program identifiers or inaccessible definition
 * text.
 */
public final class MetadataErrorCodes {

    /** A snapshot declares a metadata schema version this build does not support. */
    public static final String METADATA_SCHEMA_VERSION_UNSUPPORTED = "METADATA_SCHEMA_VERSION_UNSUPPORTED";

    /** A metadata identifier (definition, snapshot, entity, field, enum code) is outside its bounded grammar. */
    public static final String METADATA_ID_INVALID = "METADATA_ID_INVALID";

    /** A display name, alias, example or disambiguation text is missing, blank or otherwise unusable. */
    public static final String METADATA_TEXT_INVALID = "METADATA_TEXT_INVALID";

    /** A structured metadata value (scalar, filter, empty-set) is malformed or outside its declared type. */
    public static final String METADATA_VALUE_INVALID = "METADATA_VALUE_INVALID";

    /** Two definitions in one snapshot share a stable ID, including across definition kinds. */
    public static final String METADATA_DUPLICATE_DEFINITION_ID = "METADATA_DUPLICATE_DEFINITION_ID";

    /** Two identifiers or aliases in one definition kind normalize to the same key. */
    public static final String METADATA_NORMALIZED_COLLISION = "METADATA_NORMALIZED_COLLISION";

    /** Operands of a definition contradict each other (type/operator, NULL, currency, window, aggregate, empty-set). */
    public static final String METADATA_SEMANTIC_COMBINATION_INVALID = "METADATA_SEMANTIC_COMBINATION_INVALID";

    /** A metric references a Relation ID that does not exist in the same snapshot. */
    public static final String METADATA_RELATION_REFERENCE_UNKNOWN = "METADATA_RELATION_REFERENCE_UNKNOWN";

    /** A metric references a Relation that is not compatible with the metric subject or source entity. */
    public static final String METADATA_RELATION_INCOMPATIBLE = "METADATA_RELATION_INCOMPATIBLE";

    /** The declared content hash is not the canonical hash of the snapshot's semantic material. */
    public static final String METADATA_CONTENT_HASH_MISMATCH = "METADATA_CONTENT_HASH_MISMATCH";

    /** A snapshot ID is already retained with different content. */
    public static final String METADATA_SNAPSHOT_CONFLICT = "METADATA_SNAPSHOT_CONFLICT";

    /** No retained snapshot exists for the requested ID. */
    public static final String METADATA_SNAPSHOT_NOT_FOUND = "METADATA_SNAPSHOT_NOT_FOUND";

    /** A search query is missing, blank or longer than the supported bound. */
    public static final String METADATA_SEARCH_QUERY_INVALID = "METADATA_SEARCH_QUERY_INVALID";

    /** A search result limit is outside the supported range. */
    public static final String METADATA_SEARCH_LIMIT_INVALID = "METADATA_SEARCH_LIMIT_INVALID";

    /** A T03 metadata descriptor does not agree with the retained T04 snapshot identity. */
    public static final String METADATA_DESCRIPTOR_MISMATCH = "METADATA_DESCRIPTOR_MISMATCH";

    private MetadataErrorCodes() {
    }
}