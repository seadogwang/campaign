package com.company.campaign.query.plan;

/**
 * Version discriminators stamped into every Query AST and Semantic Query Plan.
 *
 * <p>Planning is a deterministic, versioned transformation: a change to a compilation, resolution or hashing
 * rule is a new version, so an evaluation can never silently compare plans produced under different rules.
 * Every value is part of the plan hash material and is reported verbatim in an evaluation run record.
 *
 * <p>All four discriminators moved with the T18 Existing Audience Reference slice, because each one names a
 * rule whose domain changed:
 *
 * <ul>
 *   <li>{@link #QUERY_AST_VERSION} — the closed AST union gained the audience-reference leaf, so {@code 1.0}
 *       could not describe a tree carrying one;</li>
 *   <li>{@link #PLANNER_VERSION} — the planner now re-resolves, reauthorizes and freezes every exact version
 *       pin and binds the host-computed effective DataScope instead of only the caller's scope;</li>
 *   <li>{@link #COMPILER_VERSION} — canonical-to-AST compilation now projects the reference leaf instead of
 *       refusing the document;</li>
 *   <li>{@link #PLAN_FORMAT_VERSION} — the plan document can now carry an audience dependency section.</li>
 * </ul>
 *
 * <p>The transform of a document with no reference is otherwise unchanged: it produces the same AST, the same
 * dependencies, the same windows, the same currency bindings and the same DataScope requirement as before, and
 * only the version pins — and therefore the hashes that embed them — move. A plan stamped with an earlier
 * version can never be read as one produced now.
 */
public final class PlanVersions {

    /** The Query AST discriminators' version. */
    public static final String QUERY_AST_VERSION = "segment-query-ast/1.1";

    /** The T08 planner version. */
    public static final String PLANNER_VERSION = "segment-query-planner/1.1";

    /** The T08 query compiler version. */
    public static final String COMPILER_VERSION = "segment-query-compiler/1.1";

    /** The canonical plan document format version. */
    public static final String PLAN_FORMAT_VERSION = "1.1";

    private PlanVersions() {
    }
}