package com.company.campaign.segment.intent;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Failure inside the structured Intent boundary.
 *
 * <p>Every instance carries one of the stable {@link IntentErrorCodes} and an immutable detail map whose
 * values are themselves bounded codes. The human-readable message is a fixed sentence: the Intent boundary
 * parses untrusted model output, so neither a message nor a detail may echo the utterance, span text,
 * candidate label, Metadata content or a trusted identifier.
 */
public class IntentException extends ContractException {

    private static final long serialVersionUID = 1L;

    public IntentException(String code, String message) {
        super(code, message, Map.of());
    }

    public IntentException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    /** Convenience for the common "this value is not admissible" construction. */
    public static IntentException invalid(String code, String message) {
        return new IntentException(code, message);
    }
}