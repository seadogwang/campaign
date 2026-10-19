package com.company.campaign.segment.dsl.predicate;

/**
 * The closed predicate union of the editable Segment DSL.
 *
 * <p>Every node carries an explicit stable {@code nodeId}. Array position is never an edit identity, so a
 * refinement can target a node by ID even after unrelated children are added, removed or moved. T05 never
 * flattens groups, reorders children, applies De Morgan, removes double negation or combines EXISTS nodes.
 *
 * <p>{@link AudienceReferencePredicate} is the one subtype T18 appends: a leaf whose entire meaning is one
 * exact immutable Audience Version pin. It is a predicate like any other, so it composes under ALL/ANY/NOT
 * and inside EXISTS without changing the shape of the tree around it, and it is never expanded into this
 * editable document — only the validated, hash-pinned dependency is recorded downstream.
 */
public sealed interface Predicate
        permits GroupPredicate, NotPredicate, AttributePredicate, MetricPredicate, MetricExpressionPredicate,
        ExistsPredicate, AudienceReferencePredicate {

    /** Stable identity of this node within one document; never array position. */
    String nodeId();

    /** The exact wire discriminator for this node. */
    PredicateType type();
}
