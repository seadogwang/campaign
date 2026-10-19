package com.company.campaign.action;

/** The closed kinds of actual Provider call this layer ever makes. */
public enum ActionAttemptKind {

    /** One actual {@code send} or {@code grant} submission. */
    SUBMISSION("SUBMISSION"),

    /** One actual {@code queryByKey} reconciliation query. */
    VERIFICATION("VERIFICATION");

    private final String code;

    ActionAttemptKind(String code) {
        this.code = code;
    }

    /** The bounded machine code this attempt kind is recorded under. */
    public String code() {
        return code;
    }
}