package com.company.campaign.segment.validation;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Base type for T07 failures that are programming or configuration errors rather than validation findings.
 *
 * <p>An invalid document never throws this: it produces an ordered {@code Issue} list. This exception is for
 * a structurally invalid configuration, such as a complexity policy outside its absolute bounds, so the
 * failure carries a stable machine code like every other contract failure.
 */
public class SegmentValidationException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SegmentValidationException(String code, String message) {
        super(code, message, Map.of());
    }

    public SegmentValidationException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }
}