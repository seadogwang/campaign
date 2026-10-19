package com.company.campaign.builder.proposal;

/**
 * Stable machine-readable failure codes owned by the strict T15 CampaignProposalPatch boundary.
 *
 * <p>These values are part of the wire contract: a host, API error envelope or later task branches on them,
 * so an existing code is never renamed or reused for a different meaning and a new failure mode gets a new
 * code. Every code carries fixed prose and empty details; no code ever echoes a payload value, a field name,
 * a logical identifier, a tenant/program/user value, a capability payload or a thrown cause.
 */
public final class ProposalErrorCodes {

    // ---- wire shape -------------------------------------------------------------------------------

    /** The proposal body is absent or empty. */
    public static final String PROPOSAL_MISSING = "CAMPAIGN_PROPOSAL_MISSING";

    /** The proposal body exceeds the configured byte, depth or collection bound. */
    public static final String PROPOSAL_LIMIT_EXCEEDED = "CAMPAIGN_PROPOSAL_LIMIT_EXCEEDED";

    /** The proposal bytes are not strict UTF-8. */
    public static final String PROPOSAL_ENCODING_INVALID = "CAMPAIGN_PROPOSAL_ENCODING_INVALID";

    /** The proposal text is not one well-formed JSON document. */
    public static final String PROPOSAL_JSON_MALFORMED = "CAMPAIGN_PROPOSAL_JSON_MALFORMED";

    /** The proposal JSON repeats an object key at some nesting level. */
    public static final String PROPOSAL_DUPLICATE_KEY = "CAMPAIGN_PROPOSAL_DUPLICATE_KEY";

    /** A structural part of the proposal has the wrong JSON kind or an unexpected field. */
    public static final String PROPOSAL_SHAPE_INVALID = "CAMPAIGN_PROPOSAL_SHAPE_INVALID";

    /** A required structural field is absent. */
    public static final String PROPOSAL_MISSING_FIELD = "CAMPAIGN_PROPOSAL_MISSING_FIELD";

    /** A structural field is JSON null where a value is required. */
    public static final String PROPOSAL_NULL_FIELD = "CAMPAIGN_PROPOSAL_NULL_FIELD";

    /** The declared contract schema version is malformed or is not the one supported version. */
    public static final String PROPOSAL_SCHEMA_VERSION_UNSUPPORTED = "CAMPAIGN_PROPOSAL_SCHEMA_VERSION_UNSUPPORTED";

    /** The declared mode is neither {@code CREATE} nor {@code EDIT} in the exact canonical casing. */
    public static final String PROPOSAL_MODE_INVALID = "CAMPAIGN_PROPOSAL_MODE_INVALID";

    /** {@code campaignId} is present for CREATE, absent for EDIT, or outside its bounded grammar. */
    public static final String PROPOSAL_CAMPAIGN_ID_INVALID = "CAMPAIGN_PROPOSAL_CAMPAIGN_ID_INVALID";

    /** {@code baseRevisions} is absent, malformed, or names fields that do not belong to it. */
    public static final String PROPOSAL_BASE_REVISIONS_INVALID = "CAMPAIGN_PROPOSAL_BASE_REVISIONS_INVALID";

    /** The operation list is absent, empty, too long, contains a duplicate or a contradictory operation. */
    public static final String PROPOSAL_OPERATIONS_INVALID = "CAMPAIGN_PROPOSAL_OPERATIONS_INVALID";

    /** An operation carries neither or both identity forms, or an identity outside its bounded grammar. */
    public static final String PROPOSAL_IDENTITY_INVALID = "CAMPAIGN_PROPOSAL_IDENTITY_INVALID";

    /** A MOVE carries neither or both anchors, an anchor outside its grammar, or a self-anchor. */
    public static final String PROPOSAL_ANCHOR_INVALID = "CAMPAIGN_PROPOSAL_ANCHOR_INVALID";

    /** A {@code clientRef} is declared more than once, or collides with an existing {@code logicalId}. */
    public static final String PROPOSAL_CLIENT_REF_CONFLICT = "CAMPAIGN_PROPOSAL_CLIENT_REF_CONFLICT";

    /** The relative anchors cannot be satisfied by one acyclic ordering. */
    public static final String PROPOSAL_ORDER_CYCLE = "CAMPAIGN_PROPOSAL_ORDER_CYCLE";

    /**
     * A reserved trusted, security, runtime, plan or ChangeSet field appeared inside a capability payload
     * or the client presentation context, at any nesting level.
     */
    public static final String PROPOSAL_RESERVED_FIELD = "CAMPAIGN_PROPOSAL_RESERVED_FIELD";

    /** A capability payload is not a bounded canonical JSON object. */
    public static final String PROPOSAL_PAYLOAD_INVALID = "CAMPAIGN_PROPOSAL_PAYLOAD_INVALID";

    /** The client presentation context carries a field that is not part of the presentation contract. */
    public static final String PROPOSAL_CLIENT_CONTEXT_INVALID = "CAMPAIGN_PROPOSAL_CLIENT_CONTEXT_INVALID";

    // ---- Journey topology mutation -----------------------------------------------------------------

    /**
     * A Journey-topology mutation is carried by a Proposal schema version whose published shape has no such
     * member. The legacy {@code 1.3} Proposal is readable only without the member; the current {@code 1.4}
     * Proposal is the only version that may carry it.
     */
    public static final String PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED =
            "CAMPAIGN_PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED";

    /** The Journey-topology mutation is malformed, contradictory or names fields that do not belong to it. */
    public static final String PROPOSAL_TOPOLOGY_INVALID = "CAMPAIGN_PROPOSAL_TOPOLOGY_INVALID";

    /** The Journey-topology mutation declares a format version that is not the one supported version. */
    public static final String PROPOSAL_TOPOLOGY_VERSION_INVALID =
            "CAMPAIGN_PROPOSAL_TOPOLOGY_VERSION_INVALID";

    /** The Journey-topology mutation carries no edge, more edges than the hard bound, or an over-bound field. */
    public static final String PROPOSAL_TOPOLOGY_LIMIT_EXCEEDED =
            "CAMPAIGN_PROPOSAL_TOPOLOGY_LIMIT_EXCEEDED";

    /** The Journey-topology mutation repeats one edge identity or one semantic edge. */
    public static final String PROPOSAL_TOPOLOGY_DUPLICATE_EDGE = "CAMPAIGN_PROPOSAL_TOPOLOGY_DUPLICATE_EDGE";

    /** A Journey-topology edge begins and ends at the same declared endpoint identity. */
    public static final String PROPOSAL_TOPOLOGY_SELF_EDGE = "CAMPAIGN_PROPOSAL_TOPOLOGY_SELF_EDGE";

    /** A Journey-topology edge endpoint is not a bounded identity of one of the two accepted forms. */
    public static final String PROPOSAL_TOPOLOGY_ENDPOINT_INVALID =
            "CAMPAIGN_PROPOSAL_TOPOLOGY_ENDPOINT_INVALID";

    private ProposalErrorCodes() {
    }
}