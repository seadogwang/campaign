package com.company.campaign.builder.agent;

/**
 * The closed set of grounded explanation claim kinds.
 *
 * <p>Every claim is typed, so an explanation can never contain free model prose: there is no "free text" kind,
 * only claims that reference accepted clause/node ids, accepted definition ids, a semantic operator/value/window
 * summary and/or a safe Preview statistic.
 */
public enum ExplanationClaimKind {

    /** One accepted Intent clause resolved to one Metadata definition. */
    CLAUSE_DEFINITION,

    /** One attribute comparison in the accepted document. */
    ATTRIBUTE_CONDITION,

    /** One aggregate metric comparison in the accepted document. */
    METRIC_CONDITION,

    /** One relation-scoped existence condition in the accepted document. */
    RELATION_CONDITION,

    /** One logical grouping, negation or relation nesting in the accepted document. */
    LOGICAL_GROUP,

    /** One safe Preview condition statistic. */
    PREVIEW_STATISTIC
}