package com.company.campaign.runtime;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, non-enumerating outward failure of the Runtime boundary.
 *
 * <p>Every instance carries a stable {@link #code() code} plus an internal {@code invariant} name used for
 * tests and diagnostics. The message is deliberately terse and the details carry only those two bounded
 * values: no member identity, tenant value, payload body, template text, reward description, SQL, credential
 * or internal exception text is ever attached, so a rejection never leaks what it refused.
 *
 * <p>The type is named for the boundary rather than for the Java package on purpose: a class called
 * {@code RuntimeException} inside this package would shadow {@link java.lang.RuntimeException} for every file
 * that imports the package, and a catch clause that silently meant the other type is exactly the kind of
 * ambiguity this layer refuses everywhere else.
 */
public final class RuntimeBoundaryException extends ContractException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String invariant;

    public RuntimeBoundaryException(String code, String invariant, String message) {
        this(code, invariant, message, null);
    }

    public RuntimeBoundaryException(String code, String invariant, String message, Throwable cause) {
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