package com.company.campaign.builder.segment;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A fail-closed model-adapter failure carrying one stable {@link SegmentAdapterErrorCodes} code.
 *
 * <p>It never carries the provider payload, endpoint, credential, model name or any other provider-derived
 * text: only the stable code and a fixed caller-safe message. It is the only exception type the adapter
 * boundary raises.
 */
public class SegmentAdapterException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SegmentAdapterException(String code, String message) {
        super(code, message, Map.of());
    }

    public static SegmentAdapterException invalid(String code, String message) {
        return new SegmentAdapterException(code, message);
    }
}