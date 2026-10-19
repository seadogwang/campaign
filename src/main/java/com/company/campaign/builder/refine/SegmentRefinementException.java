package com.company.campaign.builder.refine;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * One stable, non-disclosing failure of the refinement patch contract or its deterministic tree editor.
 *
 * <p>It extends the T01 {@link ContractException} so the host, the API error envelope and later tasks keep one
 * stable machine-code mechanism. Messages are human-readable only; details never carry a patch value, a DSL
 * subtree, a trusted identifier or an implementation exception message.
 */
public final class SegmentRefinementException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SegmentRefinementException(String code, String message) {
        super(code, message, Map.of());
    }

    /** Builds one bounded refusal with the given stable code. */
    public static SegmentRefinementException of(String code, String message) {
        return new SegmentRefinementException(code, message);
    }
}