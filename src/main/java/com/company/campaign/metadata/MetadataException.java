package com.company.campaign.metadata;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Failure raised by the Semantic Metadata contracts and service.
 *
 * <p>Carries a stable {@link #code() code} from {@link MetadataErrorCodes} plus structured details. The
 * message and details must never contain tenant/program identifiers, denied definition text or any value
 * that would let a caller probe for metadata outside its boundary.
 */
public class MetadataException extends ContractException {

    private static final long serialVersionUID = 1L;

    public MetadataException(String code, String message) {
        this(code, message, Map.of());
    }

    public MetadataException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    public MetadataException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(code, message, details, cause);
    }
}