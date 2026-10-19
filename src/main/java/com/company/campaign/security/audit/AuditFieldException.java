package com.company.campaign.security.audit;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Fail-closed failure raised when an audit event field is outside its bounded grammar.
 *
 * <p>It covers the non-reference fields of {@link SecurityAuditEvent}: the reason code, the trusted context
 * identifiers and the duration. The message and details carry only the field name, never the rejected text:
 * the text that failed may itself be a credential, a foreign identifier or a payload fragment.
 */
public final class AuditFieldException extends ContractException {

    private static final long serialVersionUID = 1L;

    private AuditFieldException(String message, Map<String, Object> details) {
        super(ContractErrorCodes.AUDIT_FIELD_INVALID, message, details);
    }

    /** A reason code is missing or is not a bounded uppercase machine code. */
    public static AuditFieldException invalidReasonCode() {
        return new AuditFieldException(
                "Audit reasonCode must be a bounded uppercase machine code",
                Map.of("field", "reasonCode"));
    }

    /** A trusted context identifier is missing or is outside the shared reference-ID grammar. */
    public static AuditFieldException invalidTrustedIdentifier(String field) {
        return new AuditFieldException(
                "Audit trusted identifier must match the shared reference-ID grammar",
                Map.of("field", String.valueOf(field)));
    }

    /** A duration is present but negative. */
    public static AuditFieldException invalidDuration() {
        return new AuditFieldException(
                "Audit durationNanos must be null or non-negative",
                Map.of("field", "durationNanos"));
    }
}