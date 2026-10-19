package com.company.campaign.shared.error;

import com.company.campaign.shared.ImmutableValues;

import java.util.Map;
import java.util.Objects;

/**
 * Base type for every contract failure.
 *
 * <p>Each failure carries a {@link #code() stable machine code} plus structured {@link #details()} so a
 * host, API error envelope or later task module can react without parsing prose. {@link #getMessage()}
 * stays human-readable and is never the machine interface.
 */
public abstract class ContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final Map<String, Object> details;

    protected ContractException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = ImmutableValues.freezeMap(details == null ? Map.of() : details);
    }

    protected ContractException(String code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    /** Stable machine code, one of {@link ContractErrorCodes}. */
    public String code() {
        return code;
    }

    /** Immutable structured context for this failure; never contains restricted data. */
    public Map<String, Object> details() {
        return details;
    }
}