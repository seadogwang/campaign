package com.company.campaign.metadata.domain;

/** Closed boundary semantics for bounded (`ROLLING`/`FIXED`) windows. */
public enum WindowBoundarySemantics {

    /** The window includes its start instant and excludes its end instant. */
    INCLUSIVE_START_EXCLUSIVE_END,

    /** The window includes both boundary instants. */
    INCLUSIVE_BOTH,

    /** The metric supports only unbounded windows and declares no boundary behavior. */
    NOT_APPLICABLE
}