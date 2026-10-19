package com.company.campaign.journey.graph;

/**
 * The bounded, value-free rejection of one Journey topology or graph input.
 *
 * <p>It carries exactly one closed machine code and fixed prose. It never carries a rejected logical
 * identifier, an edge endpoint, a payload value or a cause, so a rejection cannot become an observability
 * leak. It is thrown only while constructing an input that cannot be represented at all; validation of a
 * representable but invalid graph is reported as an ordered, bounded {@code Issue} list instead.
 */
public final class JourneyGraphException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String invariant;

    JourneyGraphException(String code, String invariant) {
        super("The journey graph input failed closed.");
        this.code = code;
        this.invariant = invariant;
    }

    /** The stable machine-readable failure code of this rejection. */
    public String code() {
        return code;
    }

    /** The named invariant this rejection proves; it carries no rejected value. */
    public String invariant() {
        return invariant;
    }
}
