package com.company.campaign.segment.canonical;

/**
 * The closed discriminator set for the canonical semantic value union.
 *
 * <p>It mirrors the T05 {@code ValueKind} names one-for-one, so a canonical value can always be traced back
 * to the editable kind it came from, but the canonical union is a separate type: it stores only normalized
 * semantics and carries no declared lexical scale or offset.
 */
public enum CanonicalValueKind {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATETIME,
    ENUM,
    MONEY,
    LIST,
    RANGE
}