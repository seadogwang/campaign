package com.company.campaign.builder.tool;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * One stable, non-disclosing failure of the closed T11 Tool boundary.
 *
 * <p>The message and details never contain a rejected Tool identifier, payload value, restricted fact or
 * implementation exception message.
 */
public final class SegmentToolException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SegmentToolException(String code, String message) {
        super(code, message, Map.of());
    }

    /** Builds one bounded Tool rejection. */
    public static SegmentToolException invalid(String code, String message) {
        return new SegmentToolException(code, message);
    }
}