package com.company.campaign.readiness;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Every T25 readiness failure.
 *
 * <p>It carries a {@link #code() stable machine code} so a host, a store adapter or a test can react without
 * parsing prose, and never carries an evidence payload, a credential, a member identifier, a recipient or
 * Provider target, model prose or a raw failure.
 */
public final class ReadinessException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ReadinessException(String code, String message) {
        this(code, message, Map.of(), null);
    }

    public ReadinessException(String code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ReadinessException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(code, message, details, cause);
    }

    /** A bounded field is missing or outside its grammar; only the field name is disclosed. */
    public static ReadinessException invalidField(String field) {
        return new ReadinessException(
                ReadinessErrorCodes.READINESS_CONTEXT_INVALID,
                "Readiness field '" + field + "' is missing or outside its bounded grammar.",
                Map.of("field", field));
    }

    /** An evidence record or submission is not a bounded machine contract. */
    public static ReadinessException invalidEvidence(String field) {
        return new ReadinessException(
                ReadinessErrorCodes.READINESS_EVIDENCE_INVALID,
                "Readiness evidence field '" + field + "' is missing or outside its bounded grammar.",
                Map.of("field", field));
    }
}