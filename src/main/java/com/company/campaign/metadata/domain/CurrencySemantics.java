package com.company.campaign.metadata.domain;

/**
 * Closed currency semantics.
 *
 * <p>{@link #NOT_APPLICABLE} is the only value allowed for a non-money definition; a money definition must
 * declare one of the two explicit conversion policies. A money definition with no explicit policy is
 * rejected, so currency behavior can never be implied.
 */
public enum CurrencySemantics {

    /** The definition is not monetary and carries no currency behavior. */
    NOT_APPLICABLE,

    /** Amounts are converted into the trusted context's reporting currency before aggregation. */
    CONVERT_TO_REPORTING_CURRENCY,

    /** Amounts must all already be in one currency, which the definition pins. */
    SINGLE_CURRENCY_ONLY;

    /** True for an explicit monetary policy. */
    public boolean isExplicitPolicy() {
        return this != NOT_APPLICABLE;
    }
}