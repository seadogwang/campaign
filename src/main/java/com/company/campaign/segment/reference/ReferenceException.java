package com.company.campaign.segment.reference;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Base type for every Existing Audience Reference failure that is not an ordered validation issue.
 *
 * <p>It extends the T01 {@link ContractException}, so the host, the API error envelope and later tasks keep
 * one stable machine-code mechanism. Messages are human-readable only and never echo a pinned identifier, a
 * tenant or program ID, a DataScope identifier or a thrown cause.
 */
public class ReferenceException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ReferenceException(String code, String message) {
        super(code, message, Map.of());
    }

    public ReferenceException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    /** Convenience for the common "the caller supplied something unusable" construction. */
    public static ReferenceException invalid(String message) {
        return new ReferenceException(ReferenceErrorCodes.INPUT_INVALID, message);
    }
}