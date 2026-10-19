package com.company.campaign.shared.canonical;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Failure raised while reading or writing canonical JSON.
 *
 * <p>The {@link #code() code} identifies the failure class: {@code JSON_MALFORMED},
 * {@code JSON_DUPLICATE_KEY}, {@code JSON_NON_CANONICAL_VALUE}, {@code CONTRACT_UNKNOWN_FIELD} or
 * {@code CONTRACT_INVALID_VALUE}.
 */
public final class CanonicalJsonException extends ContractException {

    private static final long serialVersionUID = 1L;

    public CanonicalJsonException(String code, String message) {
        this(code, message, Map.of(), null);
    }

    public CanonicalJsonException(String code, String message, Throwable cause) {
        this(code, message, Map.of(), cause);
    }

    public CanonicalJsonException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(code, message, details, cause);
    }
}