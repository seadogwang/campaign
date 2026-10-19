package com.company.campaign.journey.compiler;

import com.company.campaign.contracts.Issue;

import java.util.List;

/**
 * The bounded, value-free rejection of one Journey compile.
 *
 * <p>It carries exactly one closed machine code, a named invariant, fixed prose and, when the rejection is a
 * graph or node validation failure, the deterministic bounded issue list. It never carries a payload value, a
 * template body, reward data, PII, an unrestricted exception or a stack-trace-bearing cause.
 */
public final class JourneyCompileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String invariant;
    private final List<Issue> issues;

    JourneyCompileException(String code, String invariant, String message) {
        this(code, invariant, message, List.of());
    }

    JourneyCompileException(String code, String invariant, String message, List<Issue> issues) {
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

    /** The deterministic, bounded validation issues, empty when the rejection is not a graph validation. */
    public List<Issue> issues() {
        return issues;
    }
}
