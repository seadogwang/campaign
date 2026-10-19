package com.company.campaign.metadata.domain;

/** Closed NULL semantics for attributes, metrics and relation targets. */
public enum NullSemantics {

    /** A NULL is an unknown value and never equals another value. */
    UNKNOWN_VALUE,

    /** Records whose field is NULL are excluded from the definition's domain. */
    EXCLUDED,

    /** A NULL is treated as an explicit value that can match an IS_NULL predicate. */
    MATCHES_NULL
}