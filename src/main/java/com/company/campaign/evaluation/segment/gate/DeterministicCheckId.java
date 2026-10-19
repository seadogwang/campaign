package com.company.campaign.evaluation.segment.gate;

import java.util.List;

/**
 * The closed set of deterministic-layer checks the Gate requires at 100%.
 *
 * <p>They mirror Segment specification section 23.1 exactly: the schema, canonicalizer, validator, resolver,
 * Query Plan and fixture-execution layers must be correct on every explicit input; the same explicit inputs
 * compiled 100 times must produce identical output; every expected AST must produce its exact expected member
 * sets on every fixture; and authorization, complexity and side-effect safety must hold on every input.
 *
 * <p>Each identifier is a stable machine name, so a check can never be silently replaced by another. The
 * declaration order is the order a complete evidence artifact reports them in.
 */
public enum DeterministicCheckId {

    /** The Typed Segment DSL schema rejects every malformed document and accepts every valid one. */
    SCHEMA("schema"),

    /** The canonicalizer produces one stable canonical AST for every explicit input. */
    CANONICALIZER("canonicalizer"),

    /** The validator produces identical issues and ordering for every explicit input. */
    VALIDATOR("validator"),

    /** The resolver selects exactly the expected Metadata candidates for every explicit input. */
    RESOLVER("resolver"),

    /** The Query Plan preserves the predicate tree and authorization predicates for every explicit input. */
    QUERY_PLAN("query-plan"),

    /** Fixture execution produces the expected rows for every explicit input. */
    FIXTURE_EXECUTION("fixture-execution"),

    /** Exactly 100 identical explicit-input replays produce identical AST, issue ordering and Plan hash. */
    REPLAY_DETERMINISM("replay-determinism"),

    /** Every expected AST produces its exact expected member set on every fixture. */
    EXPECTED_AST_MEMBER_SETS("expected-ast-member-sets"),

    /** Authorization denies every unauthorized field, tenant and DataScope request. */
    AUTHORIZATION("authorization"),

    /** Complexity limits reject every oversized request instead of degrading. */
    COMPLEXITY("complexity"),

    /** No path executes a side effect: no write, no SQL, no provider call, no runtime mutation. */
    SIDE_EFFECT_SAFETY("side-effect-safety");

    private final String code;

    DeterministicCheckId(String code) {
        this.code = code;
    }

    /** The stable machine code reported for this check. */
    public String code() {
        return code;
    }

    /** Every required check, in deterministic evidence order. */
    public static List<DeterministicCheckId> ordered() {
        return List.of(values());
    }
}