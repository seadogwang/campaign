package com.company.campaign.builder.segment;

/**
 * The closed set of provider call kinds a host may declare for one bounded model request.
 *
 * <p>The kind is host-owned: it is set by the caller that builds the request, never by the model, and it
 * carries no provider, endpoint, token or model identity. A {@link #SCHEMA_REPAIR} call is only ever reachable
 * after a captured first-pass strict-schema rejection, so the two kinds form a closed, bounded pair rather than
 * an open retry counter.
 */
public enum SegmentModelCallKind {

    /** The one first-pass call that every attempt performs. */
    FIRST_PASS,

    /** The single bounded schema-only repair call, possible only after a captured first-pass schema rejection. */
    SCHEMA_REPAIR
}