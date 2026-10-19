package com.company.campaign.runtime;

import java.util.Objects;

/**
 * The bounded, immutable outcome of one Runtime Flow Condition evaluation.
 *
 * <p>It carries exactly one closed {@link RuntimeConditionVerdict} and, for an
 * {@link RuntimeConditionVerdict#ERROR} verdict, one bounded machine reason code. For the other verdicts the
 * reason code is absent, so an evaluation outcome never carries a fact value, a scenario value or unrestricted
 * exception text.
 */
public record RuntimeConditionEvaluation(RuntimeConditionVerdict verdict, String reasonCode) {

    public RuntimeConditionEvaluation {
        Objects.requireNonNull(verdict, "verdict");
        if (verdict == RuntimeConditionVerdict.ERROR) {
            RuntimeSupport.requireMachineCode(reasonCode, "reasonCode");
        } else if (reasonCode != null) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "reasonCode");
        }
    }

    /** A deterministic true verdict. */
    public static RuntimeConditionEvaluation trueVerdict() {
        return new RuntimeConditionEvaluation(RuntimeConditionVerdict.TRUE, null);
    }

    /** A deterministic false verdict. */
    public static RuntimeConditionEvaluation falseVerdict() {
        return new RuntimeConditionEvaluation(RuntimeConditionVerdict.FALSE, null);
    }

    /** An unresolved verdict that must never be treated as false. */
    public static RuntimeConditionEvaluation unknown() {
        return new RuntimeConditionEvaluation(RuntimeConditionVerdict.UNKNOWN, null);
    }

    /** A fail-closed verdict carrying a bounded machine reason code. */
    public static RuntimeConditionEvaluation error(String reasonCode) {
        return new RuntimeConditionEvaluation(RuntimeConditionVerdict.ERROR, reasonCode);
    }

    /** True when this evaluation selects neither arm and therefore needs a human decision. */
    public boolean needsReview() {
        return verdict == RuntimeConditionVerdict.UNKNOWN
                || verdict == RuntimeConditionVerdict.ERROR;
    }

    @Override
    public String toString() {
        return "RuntimeConditionEvaluation[verdict=" + verdict
                + (reasonCode == null ? "" : ", reasonCode=" + reasonCode) + ']';
    }
}