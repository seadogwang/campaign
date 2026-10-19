package com.company.campaign.query;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when the T08 Query AST or Semantic Query Plan boundary rejects an input.
 *
 * <p>It is the counterpart of a validation finding: validation reports an ordered {@code Issue} list for an
 * invalid document, while planning fails here with a stable code when a planning input is missing, forged,
 * mismatched or semantically unsupported. The message is fixed prose and the {@code invariant} detail is a
 * closed name, so no payload value, operand, identifier or thrown cause is ever echoed outward.
 */
public final class QueryException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** Builds the stable failure for one named invariant. */
    public QueryException(String code, String invariant, String message) {
        super(code, message, details(invariant));
    }

    private static Map<String, Object> details(String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}