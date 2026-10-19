package com.company.campaign.segment.dsl.metric;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;

/** Closed behavior when a metric-expression divisor evaluates to zero. */
public enum ZeroDenominatorPolicy {
    /** Exclude the member by producing SQL NULL for the division result. */
    EXCLUDED;

    public static ZeroDenominatorPolicy fromWire(String value) {
        for (ZeroDenominatorPolicy policy : values()) {
            if (policy.name().equals(value)) {
                return policy;
            }
        }
        throw new SegmentDslException(
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR,
                "Unknown metric-expression zero-denominator policy");
    }
}
