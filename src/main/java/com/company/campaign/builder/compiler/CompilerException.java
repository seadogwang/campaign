package com.company.campaign.builder.compiler;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when the T15 Campaign compiler or Plan boundary fails closed.
 *
 * <p>The message is fixed prose per code and the only structured detail is a closed-set {@code invariant}
 * name owned by this build, so a failure never echoes a capability payload, a logical identifier, a
 * tenant/program/user value, an authorization reason or a thrown cause.
 */
public final class CompilerException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** Builds a failure for one named invariant. */
    public CompilerException(String code, String invariant, String message) {
        super(code, message, details(invariant));
    }

    /** Builds a failure with no invariant detail. */
    public CompilerException(String code, String message) {
        this(code, "none", message);
    }

    private static Map<String, Object> details(String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}