package com.company.campaign.readiness;

/** The two readiness operations that emit immutable audit data. */
public enum ReadinessAuditAction {

    /** One evidence record was offered to the store. */
    RECORD_EVIDENCE,

    /** One readiness level was evaluated and its assessment offered to the store. */
    EVALUATE_READINESS
}