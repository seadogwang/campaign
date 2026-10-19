package com.company.campaign.metadata.domain;

/**
 * Closed relation-scope/join semantics.
 *
 * <p>These describe <em>how</em> a relation is scoped in deterministic terms; they deliberately contain no
 * SQL text or physical join plan, which the future T08 planner owns.
 */
public enum RelationJoinKind {

    /** Targets are correlated to the source by a source-side key field. */
    CORRELATED_BY_SOURCE_KEY,

    /** Targets are filtered by a target-side predicate rather than correlated. */
    TARGET_FILTERED,

    /** Targets are not correlated or filtered; the relation is global. */
    GLOBAL
}