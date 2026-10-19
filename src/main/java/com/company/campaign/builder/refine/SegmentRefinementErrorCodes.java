package com.company.campaign.builder.refine;

/**
 * Stable machine-readable failure codes of the versioned refinement patch contract and its deterministic tree
 * editor.
 *
 * <p>These values are part of the wire contract: a host, an audit projection and a later task branch on them,
 * so an existing code is never renamed or reused for a different meaning. A new failure mode gets a new code.
 * No code echoes a rejected identifier, a payload value, a node value or an exception message.
 */
public final class SegmentRefinementErrorCodes {

    /** A declared refinement schema version is malformed or is not the exact supported version. */
    public static final String SCHEMA_VERSION_UNSUPPORTED = "SEGMENT_REFINEMENT_SCHEMA_VERSION_UNSUPPORTED";

    /** A JSON document is syntactically invalid or is not strict UTF-8. */
    public static final String JSON_MALFORMED = "SEGMENT_REFINEMENT_JSON_MALFORMED";

    /** A JSON document contains the same object key more than once. */
    public static final String JSON_DUPLICATE_KEY = "SEGMENT_REFINEMENT_JSON_DUPLICATE_KEY";

    /** A JSON object carries a field that is not part of the closed patch shape. */
    public static final String UNKNOWN_FIELD = "SEGMENT_REFINEMENT_UNKNOWN_FIELD";

    /** A known member is paired with the wrong JSON type, arity or required-field set. */
    public static final String SHAPE_INVALID = "SEGMENT_REFINEMENT_SHAPE_INVALID";

    /** A required field is absent. */
    public static final String MISSING_FIELD = "SEGMENT_REFINEMENT_MISSING_FIELD";

    /** A present field is explicitly null; omission and null are never interchangeable here. */
    public static final String NULL_FIELD = "SEGMENT_REFINEMENT_NULL_FIELD";

    /** An operation discriminator is not one of the four closed operation kinds. */
    public static final String UNKNOWN_OPERATION = "SEGMENT_REFINEMENT_UNKNOWN_OPERATION";

    /** An operation discriminator is a known kind written in a different casing. */
    public static final String OPERATION_CASING_INVALID = "SEGMENT_REFINEMENT_OPERATION_CASING_INVALID";

    /** A stable operation identifier is missing, malformed or outside the bounded reference grammar. */
    public static final String OPERATION_ID_INVALID = "SEGMENT_REFINEMENT_OPERATION_ID_INVALID";

    /** Two operations in one patch share an operation identifier. */
    public static final String DUPLICATE_OPERATION_ID = "SEGMENT_REFINEMENT_DUPLICATE_OPERATION_ID";

    /** A stable node identifier is missing, malformed or outside the bounded reference grammar. */
    public static final String NODE_ID_INVALID = "SEGMENT_REFINEMENT_NODE_ID_INVALID";

    /** A value addresses a position by array index or JSON Pointer/Patch path rather than by stable identity. */
    public static final String INDEX_PATH_REJECTED = "SEGMENT_REFINEMENT_INDEX_PATH_REJECTED";

    /** A raw array-index JSON Patch document was supplied instead of a stable-node refinement patch. */
    public static final String JSON_PATCH_REJECTED = "SEGMENT_REFINEMENT_JSON_PATCH_REJECTED";

    /** A field attempts to carry trusted context, a Metadata pin, SQL, a backend, a sample or an executable. */
    public static final String FORBIDDEN_FIELD = "SEGMENT_REFINEMENT_FORBIDDEN_FIELD";

    /** An input byte, operation-count, tree-node or individual text bound was exceeded. */
    public static final String LIMIT_EXCEEDED = "SEGMENT_REFINEMENT_LIMIT_EXCEEDED";

    /** A {@code REPLACE_FIELD} field is not one of the closed allowlisted semantic fields. */
    public static final String FIELD_UNKNOWN = "SEGMENT_REFINEMENT_FIELD_UNKNOWN";

    /** A replacement value does not match the wire type its field requires. */
    public static final String FIELD_VALUE_INVALID = "SEGMENT_REFINEMENT_FIELD_VALUE_INVALID";

    /** A declared patch fingerprint is not the canonical fingerprint of the patch's own semantic fields. */
    public static final String FINGERPRINT_MISMATCH = "SEGMENT_REFINEMENT_FINGERPRINT_MISMATCH";

    /** The declared base DSL hash is not the editable hash of the document the patch was applied to. */
    public static final String BASE_HASH_MISMATCH = "SEGMENT_REFINEMENT_BASE_HASH_MISMATCH";

    /** An operation names a node that does not exist in the tree at the point the operation is evaluated. */
    public static final String NODE_NOT_FOUND = "SEGMENT_REFINEMENT_NODE_NOT_FOUND";

    /** An added subtree reuses a node identifier that already exists in the tree or in an earlier addition. */
    public static final String NODE_ID_COLLISION = "SEGMENT_REFINEMENT_NODE_ID_COLLISION";

    /** A destination parent identifier names no node in the tree. */
    public static final String PARENT_NOT_FOUND = "SEGMENT_REFINEMENT_PARENT_NOT_FOUND";

    /** A destination parent exists but is not an {@code ALL}/{@code ANY} group. */
    public static final String PARENT_NOT_GROUP = "SEGMENT_REFINEMENT_PARENT_NOT_GROUP";

    /** A sibling anchor is not a direct child of the exact destination parent. */
    public static final String ANCHOR_NOT_FOUND = "SEGMENT_REFINEMENT_ANCHOR_NOT_FOUND";

    /** The root node cannot be removed or moved; it is the document's structural identity. */
    public static final String ROOT_NOT_EDITABLE = "SEGMENT_REFINEMENT_ROOT_NOT_EDITABLE";

    /** Removal was requested from a parent that is not an {@code ALL}/{@code ANY} group. */
    public static final String REMOVE_FROM_NON_GROUP = "SEGMENT_REFINEMENT_REMOVE_FROM_NON_GROUP";

    /** The operation would leave a group with no children. */
    public static final String GROUP_EMPTY = "SEGMENT_REFINEMENT_GROUP_EMPTY";

    /** A move would place a node inside itself or inside its own descendant subtree. */
    public static final String MOVE_INTO_DESCENDANT = "SEGMENT_REFINEMENT_MOVE_INTO_DESCENDANT";

    /** A move names itself as its own sibling anchor, which would place it twice. */
    public static final String DUPLICATE_PLACEMENT = "SEGMENT_REFINEMENT_DUPLICATE_PLACEMENT";

    /** A replacement field is not supported by the target node kind. */
    public static final String FIELD_NODE_INCOMPATIBLE = "SEGMENT_REFINEMENT_FIELD_NODE_INCOMPATIBLE";

    /** A replacement operator and the target's existing condition (or vice versa) do not form a valid shape. */
    public static final String FIELD_OPERATOR_INCOMPATIBLE = "SEGMENT_REFINEMENT_FIELD_OPERATOR_INCOMPATIBLE";

    /**
     * A reference re-pin names an expected prior pin that is not the one the base node actually carries.
     *
     * <p>This is the reference-level counterpart of the document-level base-hash check: an edit computed against
     * one immutable pin must fail rather than silently overwrite a pin that has since become a different one.
     */
    public static final String REFERENCE_BASE_STALE = "SEGMENT_REFINEMENT_REFERENCE_BASE_STALE";

    /**
     * The refined document carries an audience reference whose exact pin no accepted resolution backs.
     *
     * <p>A model may propose a pin but never choose one: every reference node a refinement produces — re-pinned
     * or newly added — must be backed by host resolution evidence for that exact node identity and pin.
     */
    public static final String REFERENCE_PIN_UNRESOLVED = "SEGMENT_REFINEMENT_REFERENCE_PIN_UNRESOLVED";

    private SegmentRefinementErrorCodes() {
    }
}