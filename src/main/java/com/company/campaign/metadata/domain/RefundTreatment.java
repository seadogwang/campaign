package com.company.campaign.metadata.domain;

/** Closed refund treatment for a metric's amount semantics. */
public enum RefundTreatment {

    /** Refunded amounts are subtracted from the aggregate. */
    SUBTRACT,

    /** Refunds are ignored entirely. */
    IGNORE,

    /** Refunded amounts remain included in the aggregate. */
    INCLUDE
}