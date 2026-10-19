package com.company.campaign.metadata.domain;

/**
 * Closed case-comparison policy for a {@code STRING} Attribute or Metric.
 *
 * <p>It is part of the definition's semantic hash material, so two evaluators reading the same snapshot
 * cannot disagree about whether {@code "SG"} and {@code "sg"} are the same member.
 */
public enum StringCaseSensitivity {

    /** Text compares exactly; letter case is significant. */
    CASE_SENSITIVE,

    /** Text compares after locale-independent case folding; letter case is not significant. */
    CASE_INSENSITIVE
}