package com.company.campaign.segment.dsl.contract;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Base type for every T05 Segment DSL failure.
 *
 * <p>It extends the T01 {@link ContractException} so the host, the API error envelope and later tasks keep
 * one stable machine-code mechanism. Messages are human-readable only; details never carry full input JSON
 * or sensitive values.
 */
public class SegmentDslException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SegmentDslException(String code, String message) {
        super(code, message, Map.of());
    }

    public SegmentDslException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    public SegmentDslException(String code, String message, Throwable cause) {
        super(code, message, Map.of(), cause);
    }

    /** Convenience for the common "value is not valid" construction. */
    public static SegmentDslException invalid(String code, String message) {
        return new SegmentDslException(code, message);
    }
}