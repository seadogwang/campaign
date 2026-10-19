package com.company.campaign.builder.segment;

/** Resolution state carried by every field in a staged semantic draft. */
public enum SemanticSlotStatus {
    RESOLVED,
    AMBIGUOUS,
    PROPOSED,
    MISSING,
    UNSUPPORTED
}
