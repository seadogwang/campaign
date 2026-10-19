package com.company.campaign.builder.agent;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * One stable, non-disclosing failure of the formal T11 Agent adapter.
 *
 * <p>It is thrown only for a malformed host input or an internal invariant violation; an adapter run reports a
 * refused request as a terminal result rather than as an exception.
 */
public final class SegmentAgentException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SegmentAgentException(String code, String message) {
        super(code, message, Map.of());
    }

    /** Builds one bounded Agent rejection. */
    public static SegmentAgentException invalid(String code, String message) {
        return new SegmentAgentException(code, message);
    }
}