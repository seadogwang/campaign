package com.company.campaign.preview;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when the T09C read-only Segment Preview boundary rejects a request or a dependency fails.
 *
 * <p>It extends the T01 {@link ContractException} so the HTTP error envelope keeps one stable machine-code
 * mechanism across tasks. The message is fixed per {@link PreviewErrorCodes code} and the only detail is a
 * closed {@code invariant} name, so no identifier, operand, SQL fragment, bind parameter, member identifier,
 * fixture fact, authorization reason, exception message or thrown cause is ever echoed outward.
 */
public final class PreviewException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** Builds the stable bounded failure for one closed code and one closed invariant name. */
    public PreviewException(String code, String invariant) {
        super(code, PreviewErrorCodes.message(code), details(invariant));
    }

    /** Builds the stable bounded failure for one closed code and one closed invariant name. */
    public static PreviewException of(String code, String invariant) {
        return new PreviewException(code, invariant);
    }

    private static Map<String, Object> details(String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}