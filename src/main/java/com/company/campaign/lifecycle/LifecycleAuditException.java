package com.company.campaign.lifecycle;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Fail-closed failure raised when a lifecycle audit event field is outside its bounded grammar.
 *
 * <p>It reuses the accepted {@code AUDIT_FIELD_INVALID} code because the violated rule is the same one the
 * T02 audit contract enforces: a bounded reason code, bounded trusted identifiers and a resource
 * fingerprint that a non-disclosing disposition must not carry. The message and details name only the
 * field, never the rejected text, which may itself be a foreign identifier or a payload fragment.
 */
public final class LifecycleAuditException extends ContractException {

    private static final long serialVersionUID = 1L;

    private LifecycleAuditException(String message, Map<String, Object> details) {
        super(ContractErrorCodes.AUDIT_FIELD_INVALID, message, details);
    }

    /** A lifecycle audit reason code is missing or is not a bounded uppercase machine code. */
    public static LifecycleAuditException invalidReasonCode() {
        return new LifecycleAuditException(
                "Lifecycle audit reasonCode must be a bounded uppercase machine code",
                Map.of("field", "reasonCode"));
    }

    /** A lifecycle audit trusted identifier is missing or is outside the shared reference-ID grammar. */
    public static LifecycleAuditException invalidTrustedIdentifier(String field) {
        return new LifecycleAuditException(
                "Lifecycle audit trusted identifier must match the shared reference-ID grammar",
                Map.of("field", String.valueOf(field)));
    }

    /** A non-disclosing disposition was given a resource fingerprint. */
    public static LifecycleAuditException fingerprintForbiddenForNotFound() {
        return new LifecycleAuditException(
                "A NOT_FOUND lifecycle audit event must not carry a resource fingerprint",
                Map.of("disposition", LifecycleDisposition.NOT_FOUND.name()));
    }
}