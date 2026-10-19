package com.company.campaign.security.authorization;

import java.util.Objects;

/**
 * Result of a DataScope policy evaluation, with a stable internal reason.
 *
 * <p>A null outcome is not representable; a policy that cannot decide must return
 * {@link DataScopeDecision#unavailable}, which the authorization service treats as a denial.
 */
public record DataScopeDecision(DataScopeOutcome outcome, String reasonCode) {

    public DataScopeDecision {
        Objects.requireNonNull(outcome, "outcome");
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    public static DataScopeDecision allowed() {
        return new DataScopeDecision(DataScopeOutcome.ALLOWED, "DATASCOPE_ALLOWED");
    }

    public static DataScopeDecision denied(String reasonCode) {
        return new DataScopeDecision(DataScopeOutcome.DENIED, reasonCode);
    }

    public static DataScopeDecision unavailable(String reasonCode) {
        return new DataScopeDecision(DataScopeOutcome.UNAVAILABLE, reasonCode);
    }
}