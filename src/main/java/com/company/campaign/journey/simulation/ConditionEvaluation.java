package com.company.campaign.journey.simulation;

import java.util.Objects;

/**
 * The bounded, immutable outcome of one pure Flow Condition evaluation.
 *
 * <p>It carries exactly one closed {@link ConditionVerdict} and, for an {@link ConditionVerdict#ERROR}
 * verdict, one bounded machine reason code. For the other verdicts the reason code is null, so no evaluation
 * outcome ever carries a fact value, a scenario value or unrestricted exception text.
 */
public record ConditionEvaluation(ConditionVerdict verdict, String reasonCode) {

    public ConditionEvaluation {
        Objects.requireNonNull(verdict, "verdict");
        if (verdict == ConditionVerdict.ERROR) {
            if (reasonCode == null) {
                throw new SimulationException(SimulationErrorCodes.NODE_CONFIG_INVALID, "reasonCode",
                        "An error evaluation must carry a bounded reason code.");
            }
        } else if (reasonCode != null) {
            throw new SimulationException(SimulationErrorCodes.NODE_CONFIG_INVALID, "reasonCode",
                    "A selectable evaluation must not carry a reason code.");
        }
    }

    /** A deterministic true verdict. */
    public static ConditionEvaluation trueVerdict() {
        return new ConditionEvaluation(ConditionVerdict.TRUE, null);
    }

    /** A deterministic false verdict. */
    public static ConditionEvaluation falseVerdict() {
        return new ConditionEvaluation(ConditionVerdict.FALSE, null);
    }

    /** An unresolved verdict that must never be treated as false. */
    public static ConditionEvaluation unknown() {
        return new ConditionEvaluation(ConditionVerdict.UNKNOWN, null);
    }

    /** A fail-closed verdict carrying a bounded machine reason code. */
    public static ConditionEvaluation error(String reasonCode) {
        return new ConditionEvaluation(ConditionVerdict.ERROR, reasonCode);
    }

    @Override
    public String toString() {
        return "ConditionEvaluation[verdict=" + verdict
                + (reasonCode == null ? "" : ", reasonCode=" + reasonCode) + ']';
    }
}
