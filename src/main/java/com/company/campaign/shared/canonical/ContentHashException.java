package com.company.campaign.shared.canonical;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/** Raised when a value is not a well-formed {@code sha256:<64 lowercase hex>} content hash. */
public final class ContentHashException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ContentHashException(String rejectedValue) {
        super(
                ContractErrorCodes.CONTENT_HASH_INVALID,
                "Content hash must match sha256:<64 lowercase hex characters>",
                Map.of("value", rejectedValue == null ? "" : rejectedValue));
    }
}