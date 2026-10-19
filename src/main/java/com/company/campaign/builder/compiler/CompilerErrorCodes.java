package com.company.campaign.builder.compiler;

/**
 * Stable machine-readable failure codes owned by the T15 Campaign compiler and Plan boundary.
 *
 * <p>These values are part of the wire contract: a host, API error envelope or later task branches on them,
 * so an existing code is never renamed or reused for a different meaning and a new failure mode gets a new
 * code. Every code carries fixed prose plus, at most, a closed-set invariant name; no code ever echoes a
 * payload value, a capability payload, a logical identifier, a tenant/program/user value or a thrown cause.
 */
public final class CompilerErrorCodes {

    /** A required host input — patch, catalog, policy, instants, context or authorization — was null. */
    public static final String COMPILER_INPUT_MISSING = "CAMPAIGN_COMPILER_INPUT_MISSING";

    /** The explicit compile/expiry instants are absent, not ordered, or exceed the bounded lifetime. */
    public static final String COMPILER_INSTANTS_INVALID = "CAMPAIGN_COMPILER_INSTANTS_INVALID";

    /**
     * The trusted Current campaign, its Draft identity, its tenant/program ownership or its campaign revision
     * does not match the patch and the trusted context. One non-disclosing code covers an absent, stale,
     * foreign, published-in-place and contradictory Current.
     */
    public static final String COMPILER_TARGET_UNAVAILABLE = "CAMPAIGN_COMPILER_TARGET_UNAVAILABLE";

    /** The patch's frozen catalog or policy version/hash is not the frozen input the host supplied. */
    public static final String COMPILER_BASE_REVISIONS_STALE = "CAMPAIGN_COMPILER_BASE_REVISIONS_STALE";

    /** A frozen input contradicts itself: duplicate capability, ambiguous reference, non-deterministic contract. */
    public static final String COMPILER_FROZEN_INPUT_INVALID = "CAMPAIGN_COMPILER_FROZEN_INPUT_INVALID";

    /** The named capability is not present in the frozen catalog. */
    public static final String COMPILER_CAPABILITY_UNKNOWN = "CAMPAIGN_COMPILER_CAPABILITY_UNKNOWN";

    /** The named capability is present but disabled by the frozen catalog. */
    public static final String COMPILER_CAPABILITY_DISABLED = "CAMPAIGN_COMPILER_CAPABILITY_DISABLED";

    /** The capability contract version, or a carried component's pinned contract version, does not match. */
    public static final String COMPILER_CAPABILITY_VERSION_MISMATCH =
            "CAMPAIGN_COMPILER_CAPABILITY_VERSION_MISMATCH";

    /** A composition slot rule was violated: a singleton slot holds more than one component. */
    public static final String COMPILER_COMPOSITION_SLOT_VIOLATION =
            "CAMPAIGN_COMPILER_COMPOSITION_SLOT_VIOLATION";

    /** A catalog reference could not be resolved, was ambiguous, or named a foreign entry. */
    public static final String COMPILER_REFERENCE_UNRESOLVED = "CAMPAIGN_COMPILER_REFERENCE_UNRESOLVED";

    /** A capability contract returned a contradictory or non-deterministic result. */
    public static final String COMPILER_CAPABILITY_CONTRACT_INVALID =
            "CAMPAIGN_COMPILER_CAPABILITY_CONTRACT_INVALID";

    /** The injected authoring policy reported at least one blocking issue. */
    public static final String COMPILER_POLICY_VIOLATION = "CAMPAIGN_COMPILER_POLICY_VIOLATION";

    /**
     * Authorization for the server-resolved Campaign/Create resource was not an explicit, structurally exact
     * ALLOWED decision with the Campaign kind, the Campaign Edit permission and the Allowed reason.
     */
    public static final String COMPILER_AUTHORIZATION_DENIED = "CAMPAIGN_COMPILER_AUTHORIZATION_DENIED";

    /** An internal compiler or Manifest invariant is not satisfied; no partially consistent output is published. */
    public static final String COMPILER_OUTPUT_INCONSISTENT = "CAMPAIGN_COMPILER_OUTPUT_INCONSISTENT";

    /** An operation names a {@code logicalId} the trusted Current campaign does not own. */
    public static final String COMPILER_LOGICAL_ID_UNKNOWN = "CAMPAIGN_COMPILER_LOGICAL_ID_UNKNOWN";

    /** A derived plan-scoped {@code logicalId} collided with one that already exists. */
    public static final String COMPILER_LOGICAL_ID_COLLISION = "CAMPAIGN_COMPILER_LOGICAL_ID_COLLISION";

    /** A MOVE anchor names neither a declared {@code clientRef} nor an object of the current campaign. */
    public static final String COMPILER_ANCHOR_UNRESOLVED = "CAMPAIGN_COMPILER_ANCHOR_UNRESOLVED";

    /** A Plan store already holds a different Plan under the same identifier. */
    public static final String COMPILER_PLAN_STORE_CONFLICT = "CAMPAIGN_COMPILER_PLAN_STORE_CONFLICT";

    // ---- Journey topology ---------------------------------------------------------------------------

    /**
     * A replacement Journey topology was requested without the frozen pack index the graph must be decided
     * against, so the compile cannot prove the graph and refuses rather than freezing an unproven one.
     */
    public static final String COMPILER_TOPOLOGY_INPUT_MISSING = "CAMPAIGN_COMPILER_TOPOLOGY_INPUT_MISSING";

    /** A Journey-topology edge endpoint resolves to neither an existing component nor a declared reference. */
    public static final String COMPILER_TOPOLOGY_ENDPOINT_UNRESOLVED =
            "CAMPAIGN_COMPILER_TOPOLOGY_ENDPOINT_UNRESOLVED";

    /** The resolved topology material is not a representable T20 topology. */
    public static final String COMPILER_TOPOLOGY_INVALID = "CAMPAIGN_COMPILER_TOPOLOGY_INVALID";

    /** The complete merged node and edge set is not a valid Journey graph. */
    public static final String COMPILER_TOPOLOGY_GRAPH_INVALID = "CAMPAIGN_COMPILER_TOPOLOGY_GRAPH_INVALID";

    /** A topology removal was requested while the merged Campaign still holds a Flow node. */
    public static final String COMPILER_TOPOLOGY_REMOVE_INVALID = "CAMPAIGN_COMPILER_TOPOLOGY_REMOVE_INVALID";

    /** The Plan store refused a Plan that is not a genuine compiler-produced Plan. */
    public static final String COMPILER_PLAN_STORE_INVALID = "CAMPAIGN_COMPILER_PLAN_STORE_INVALID";

    private CompilerErrorCodes() {
    }
}