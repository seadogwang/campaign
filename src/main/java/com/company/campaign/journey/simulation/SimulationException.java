package com.company.campaign.journey.simulation;

import com.company.campaign.contracts.Issue;

import java.util.List;

/**
 * The bounded, value-free rejection of one Journey simulation input.
 *
 * <p>It carries exactly one closed machine code, a named invariant and fixed prose. It never carries a fact
 * value, a scenario value, a logical identifier, template or reward data, PII, an unrestricted exception or a
 * stack-trace-bearing cause, so a rejection cannot become an observability leak. It is thrown only while
 * constructing an input that cannot be represented at all or when a failed-closed report is asked for a
 * result it does not hold.
 */
public final class SimulationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String invariant;
    private final List<Issue> issues;

    public SimulationException(String code, String invariant, String message) {
        this(code, invariant, message, List.of());
    }

    public SimulationException(String code, String invariant, String message,
            List<Issue> issues) {
        super(message);
        this.code = code;
        this.invariant = invariant;
        this.issues = List.copyOf(issues);
    }

    /** The stable machine-readable failure code of this rejection. */
    public String code() {
        return code;
    }

    /** The named invariant this rejection proves; it carries no rejected value. */
    public String invariant() {
        return invariant;
    }

    /** The bounded issues that explain a failed-closed report, empty for an input rejection. */
    public List<Issue> issues() {
        return issues;
    }
}
