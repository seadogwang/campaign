package com.company.campaign.action;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, non-enumerating outward failure of the external-action boundary.
 *
 * <p>Every instance carries a stable {@link #code() code} plus an internal {@code invariant} name used for
 * tests and diagnostics. The message is deliberately terse and the details carry only those two bounded values:
 * no member identity, tenant value, recipient address, template body, reward description or secret, Provider
 * token or reference, SQL, credential or internal exception text is ever attached, so a rejection never leaks
 * what it refused.
 */
public final class ActionBoundaryException extends ContractException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String invariant;

    public ActionBoundaryException(String code, String invariant, String message) {
        this(code, invariant, message, null);
    }

    public ActionBoundaryException(String code, String invariant, String message, Throwable cause) {
        super(code, message, details(invariant), cause);
        this.code = code;
        this.invariant = invariant;
    }

    /** The stable machine-readable failure code. */
    public String code() {
        return code;
    }

    /** The internal invariant name that failed; bounded and safe to record. */
    public String invariant() {
        return invariant;
    }

    private static Map<String, Object> details(String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}