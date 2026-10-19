package com.company.campaign.lifecycle;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, non-enumerating outward failure of the host-only lifecycle boundary.
 *
 * <p>Every instance carries a stable {@link #code() code} plus an internal {@code invariant} name used for
 * tests and diagnostics. The message is deliberately terse and the details carry only those two bounded
 * values: no Plan content, Manifest, ChangeSet, Draft bytes, trusted identifier or internal exception text
 * is ever attached, so the same failure is produced for a stale Plan, a mismatched hash and any other
 * rejection of an operation the caller was otherwise allowed to attempt.
 */
public final class LifecycleException extends ContractException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String invariant;

    public LifecycleException(String code, String invariant, String message) {
        super(code, message, details(invariant));
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