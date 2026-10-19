package com.company.campaign.security.data;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Raised by {@link RetentionPolicy#requireRetainable} when retention is refused.
 *
 * <p>The stable code is the decision's own reason code, so an unsafe raw {@code RESTRICTED} request is
 * reported as {@code UNSAFE_RETENTION_REQUEST} while a structurally invalid request keeps its own code.
 */
public final class UnsafeRetentionException extends ContractException {

    private static final long serialVersionUID = 1L;

    public UnsafeRetentionException(String code, String reasonCode) {
        super(code, "Retention is not permitted", Map.of("reason", String.valueOf(reasonCode)));
    }
}