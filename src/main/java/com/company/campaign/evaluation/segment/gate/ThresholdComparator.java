package com.company.campaign.evaluation.segment.gate;

import java.math.BigDecimal;

/**
 * The exact comparator of one Segment specification threshold.
 *
 * <p>Only the two comparators the specification actually states exist. A minimum rate is {@link #AT_LEAST}; a
 * requirement stated as an exact value — 100% recall or a 0% silent-assumption rate — is {@link #EXACTLY}, so
 * a value above an exact requirement is a failure rather than a silent pass.
 */
public enum ThresholdComparator {

    /** The observed value must be greater than or equal to the required value. */
    AT_LEAST(">=") {
        @Override
        public boolean satisfies(BigDecimal observed, BigDecimal required) {
            return observed.compareTo(required) >= 0;
        }
    },

    /** The observed value must be exactly equal to the required value. */
    EXACTLY("==") {
        @Override
        public boolean satisfies(BigDecimal observed, BigDecimal required) {
            return observed.compareTo(required) == 0;
        }
    };

    private final String symbol;

    ThresholdComparator(String symbol) {
        this.symbol = symbol;
    }

    /** The stable symbol of this comparator, for reporting. */
    public String symbol() {
        return symbol;
    }

    /** True when the observed exact decimal satisfies this comparator against the required value. */
    public abstract boolean satisfies(BigDecimal observed, BigDecimal required);
}