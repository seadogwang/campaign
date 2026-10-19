package com.company.campaign.builder.authoring;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when the T16 Segment authoring integration fails closed.
 *
 * <p>The message is fixed prose per code and the only structured detail is a closed-set {@code invariant} name
 * owned by this build, so a failure never echoes an envelope, a DSL document, a Metadata label, a member
 * identifier, a tenant/program/user value, a provider or model output or a thrown cause.
 */
public final class SegmentAuthoringException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** Builds a failure for one named invariant. */
    public SegmentAuthoringException(String code, String invariant, String message) {
        super(code, message, details(invariant));
    }

    /** Builds a failure with no invariant detail. */
    public SegmentAuthoringException(String code, String message) {
        this(code, "none", message);
    }

    private static Map<String, Object> details(String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}