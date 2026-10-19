package com.company.campaign.contracts;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.time.Instant;
import java.util.Map;

/** Raised when a server-stored Plan is used at or after its expiry instant. */
public final class PlanExpiredException extends ContractException {

    private static final long serialVersionUID = 1L;

    public PlanExpiredException(String planId, Instant expiresAt, Instant evaluatedAt) {
        super(
                ContractErrorCodes.PLAN_EXPIRED,
                "Campaign plan " + planId + " expired at " + expiresAt,
                Map.of(
                        "planId", String.valueOf(planId),
                        "expiresAt", String.valueOf(expiresAt),
                        "evaluatedAt", String.valueOf(evaluatedAt)));
    }
}