package com.company.campaign.metadata.domain;

/** Closed empty-set behavior for an aggregate or a relation. */
public enum EmptySetBehavior {

    /** The aggregate yields a zero value of the declared result type. */
    ZERO,

    /** The aggregate/relation yields NULL. */
    NULL,

    /** The aggregate/relation has no defined value; it must not be evaluated as a number. */
    UNDEFINED
}