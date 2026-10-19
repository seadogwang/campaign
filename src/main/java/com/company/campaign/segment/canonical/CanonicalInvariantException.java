package com.company.campaign.segment.canonical;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when a public canonical artifact would be built in a state its own contract forbids.
 *
 * <p>It is the counterpart of a validation finding on the construction side: an invalid <em>document</em>
 * produces an ordered {@code Issue} list, while an attempt to forge an invalid <em>canonical artifact</em>
 * fails here with a stable code. The message is fixed prose and the {@code invariant} detail is a closed
 * name, so no payload value, identifier, owner field or thrown cause is ever echoed outward.
 */
public final class CanonicalInvariantException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** Builds the stable failure for one named invariant. */
    public CanonicalInvariantException(String code, String invariant, String message) {
        super(code, message, details(invariant));
    }

    private static Map<String, Object> details(String invariant) {
        // LinkedHashMap keeps the single detail stable if it is ever serialized.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}