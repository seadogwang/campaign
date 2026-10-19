package com.company.campaign.evaluation.segment.reference;

import java.util.List;

/**
 * The closed coverage matrix of the T18 Existing Audience Reference corpus.
 *
 * <p>Every category is one assertion the T18 specification section 8 requires the corpus to make, and the
 * corpus validator refuses to call a corpus Gate-ready while any of them is empty or under-minimum. A
 * category is a claim about <em>what is proved</em>, never a label attached to a case for counting: each
 * category names the specific failure mode or success property its cases must exercise.
 */
public enum ReferenceCategory {

    /** Exact version identity/hash pinning, and the deterministic failure of a missing or stale pin. */
    EXACT_PIN_INTEGRITY("A reference names one exact immutable version, and a pin that is absent, stale,"
            + " changed or held by another tenant fails deterministically."),

    /** Self, two-node and deep cycles, including a candidate edit reaching its own root version. */
    CYCLE_REJECTION("A reference graph that reaches its own root — directly, through two nodes or"
            + " transitively — is rejected, and a diamond is not mistaken for a cycle."),

    /** Maximum depth, maximum distinct versions and maximum expanded complexity. */
    GRAPH_LIMIT_ENFORCEMENT("Depth, distinct-version and expanded-complexity bounds each stop a graph"
            + " that a bounded default must not admit."),

    /** Trusted DataScope intersection: narrowing, empty intersection and an unavailable port. */
    DATASCOPE_INTERSECTION("The effective scope is the trusted intersection, never a copied or"
            + " concatenated one, and an empty or unavailable intersection fails closed."),

    /** Same-tenant, program relation, SEGMENT authorization and subject-scope boundaries. */
    AUTHORIZATION_BOUNDARY("Owner, program relation, SEGMENT authorization and subject scope are enforced"
            + " from trusted facts only, and a missing trusted context fails closed."),

    /** References nested in AND/OR/NOT and inside EXISTS, plus duplicate references at several sites. */
    NESTED_BOOLEAN_AND_DUPLICATES("Reference sites stay structurally present under nested boolean"
            + " composition, duplicates are de-duplicated for accounting, and the subject scope follows"
            + " the tree shape."),

    /** One eligible immutable published version resolved to an exact pin and evidence. */
    UNIQUE_RESOLUTION_AND_PINNING("A request that matches one eligible immutable published version is"
            + " pinned to that exact version and its resolution evidence is recorded."),

    /** Several audiences or several eligible versions, answered by one structured clarification. */
    AMBIGUOUS_AUDIENCE_OR_VERSION("An ambiguous request never guesses: it asks one structured question,"
            + " and a host-applied answer resolves deterministically."),

    /** Words meaning current/latest never stay dynamic. */
    DYNAMIC_WORDING("A request whose wording implies current or latest is resolved once to an immutable"
            + " pin, or clarified, and never left dynamic."),

    /** Denied, unpublished, unknown and cross-tenant targets. */
    DENIED_AND_UNAVAILABLE_TARGET("A denied target is reported as denied without disclosing existence, and"
            + " an unknown or unpublished target is indistinguishable from one the caller cannot see."),

    /** Permission, availability and retention drift re-checked after a first successful resolution. */
    EXECUTION_TIME_DRIFT("A resolution decision is not permanent authority: a withdrawn version, a revoked"
            + " permission or a released retention hold is observed by a re-check."),

    /** Retention holds, release policy and hard deletion. */
    RETENTION_INTEGRITY("A published dependent's hold keeps an exact version alive, a release follows"
            + " explicit host policy, and a hard deletion under an active hold is refused."),

    /** Fixture membership lookup bound to the exact pin and its member-set hash. */
    MEMBERSHIP_SET_BINDING("Local fixture membership is bound to the exact pin it was read from, and a"
            + " different pin for one identity never answers with another version's member set.");

    private final String assertion;

    ReferenceCategory(String assertion) {
        this.assertion = assertion;
    }

    /** The one-sentence assertion this category's cases must make. */
    public String assertion() {
        return assertion;
    }

    /** Every category, in declaration order, which is the canonical coverage order. */
    public static List<ReferenceCategory> ordered() {
        return List.of(values());
    }
}