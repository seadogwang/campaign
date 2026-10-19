package com.company.campaign.security.data;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Raised when a payload exceeds a configured size, depth or collection limit.
 *
 * <p>The failure is reported with a stable code and the observed/limit pair; the payload is never silently
 * truncated, because a truncated payload could drop the very field a redaction decision depended on.
 */
public final class PayloadLimitExceededException extends ContractException {

    private static final long serialVersionUID = 1L;

    private PayloadLimitExceededException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    public static PayloadLimitExceededException size(int actualBytes, int limitBytes) {
        return new PayloadLimitExceededException(
                ContractErrorCodes.PAYLOAD_SIZE_LIMIT_EXCEEDED,
                "Payload exceeds the byte limit",
                Map.of("actualBytes", actualBytes, "limitBytes", limitBytes));
    }

    public static PayloadLimitExceededException depth(int actualDepth, int limitDepth) {
        return new PayloadLimitExceededException(
                ContractErrorCodes.PAYLOAD_DEPTH_LIMIT_EXCEEDED,
                "Payload exceeds the nesting depth limit",
                Map.of("actualDepth", actualDepth, "limitDepth", limitDepth));
    }

    public static PayloadLimitExceededException collection(int actualSize, int limitSize) {
        return new PayloadLimitExceededException(
                ContractErrorCodes.PAYLOAD_COLLECTION_LIMIT_EXCEEDED,
                "Payload exceeds the collection size limit",
                Map.of("actualSize", actualSize, "limitSize", limitSize));
    }
}