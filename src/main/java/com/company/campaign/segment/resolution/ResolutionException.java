package com.company.campaign.segment.resolution;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Failure inside the deterministic resolver boundary.
 *
 * <p>Carries a stable {@link ResolutionErrorCodes} value and an immutable detail map of bounded codes. The
 * message is a fixed sentence: the resolver must never echo the utterance, a source span, a candidate label,
 * Metadata content or the existence of a denied definition.
 */
public class ResolutionException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ResolutionException(String code, String message) {
        super(code, message, Map.of());
    }

    public ResolutionException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    /** Convenience for the common "this input is not admissible" construction. */
    public static ResolutionException invalid(String code, String message) {
        return new ResolutionException(code, message);
    }
}