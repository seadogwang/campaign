package com.company.campaign.action;

/** The closed kinds of durable work one ActionTask addresses. */
public enum ActionTaskKind {

    /** Submit the exact immutable request to the Provider, or re-submit it after proven non-application. */
    SUBMISSION("SUBMISSION", "ACTION_SUBMISSION"),

    /** Reconcile one possibly-applied remote effect by the original business key; never resubmits. */
    VERIFICATION("VERIFICATION", "ACTION_VERIFICATION");

    private final String code;
    private final String reasonCode;

    ActionTaskKind(String code, String reasonCode) {
        this.code = code;
        this.reasonCode = reasonCode;
    }

    /** The bounded machine code this task kind is recorded and hashed under. */
    public String code() {
        return code;
    }

    /** The bounded machine reason code a freshly enqueued task of this kind carries. */
    public String reasonCode() {
        return reasonCode;
    }

    /** The bounded machine reason code a completed task of this kind records. */
    public String completionReasonCode() {
        return this == SUBMISSION ? "ACTION_SUBMISSION_COMPLETED" : "ACTION_VERIFICATION_COMPLETED";
    }
}