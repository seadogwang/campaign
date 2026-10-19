package com.company.campaign.metadata.domain;

/** Closed relation cardinality, sufficient for a future {@code EXISTS} planner to reason about multiplicity. */
public enum RelationCardinality {

    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_ONE,
    MANY_TO_MANY;

    /** True when the source may match more than one target row. */
    public boolean isToMany() {
        return this == ONE_TO_MANY || this == MANY_TO_MANY;
    }
}