package com.company.campaign.query.fixture;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when the deterministic fixture loader, mapping or executor rejects an input.
 *
 * <p>The message is fixed prose and the only structured detail is a closed {@code invariant} name, so a
 * failure never echoes a payload row, a member or definition identifier, a tenant/program/scope value, a
 * currency, an operand or a thrown cause. Callers branch on {@link #code()}, one of {@link FixtureErrorCodes}.
 */
public final class FixtureException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** Builds the stable failure for one named invariant. */
    public FixtureException(String code, String invariant, String message) {
        super(code, message, details(invariant));
    }

    private static Map<String, Object> details(String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}