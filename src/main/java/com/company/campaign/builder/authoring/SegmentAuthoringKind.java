package com.company.campaign.builder.authoring;

/**
 * The closed vocabulary of the server-owned sources a resolved rule-based Segment can arrive from.
 *
 * <p>A kind is an <em>audit and routing</em> fact only. It is never part of a capability payload, a Proposal
 * hash, a Manifest, a Plan or a preview card: the three sources converge on one canonical payload before the
 * compiler runs, so an AI conversation, a Visual Builder panel and a stable-node refinement of equal typed
 * semantics and equal frozen pins produce byte-identical Proposal, Manifest and Plan material.
 */
public enum SegmentAuthoringKind {

    /** The AI half: a resolved Segment Agent run (T11), or the terminal outcome of one. */
    AI,

    /** A resolved Visual Builder typed DSL document, carrying the same frozen proofs. */
    VISUAL_BUILDER,

    /** A successful stable-node refinement (T12) that preserves the Segment's logical identity. */
    REFINEMENT
}