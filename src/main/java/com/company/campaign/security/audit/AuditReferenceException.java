package com.company.campaign.security.audit;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Fail-closed failure raised when an audit resource fingerprint cannot be represented safely.
 *
 * <p>The message and details never echo a resource identifier: the fingerprint is the only resource value
 * the audit contract accepts, and the failure describes the violated disposition without restating anything
 * a caller supplied.
 */
public final class AuditReferenceException extends ContractException {

    private static final long serialVersionUID = 1L;

    private AuditReferenceException(String message, Map<String, Object> details) {
        super(ContractErrorCodes.AUDIT_REFERENCE_INVALID, message, details);
    }

    /** A decision that must not disclose a resource was given a fingerprint. */
    public static AuditReferenceException resourceForbiddenForNotFound() {
        return new AuditReferenceException(
                "A NOT_FOUND audit event must not carry a resource fingerprint",
                Map.of("disposition", SecurityDisposition.NOT_FOUND.name()));
    }

    /** A fingerprint was supplied for a decision whose resource kind does not match it. */
    public static AuditReferenceException fingerprintKindMismatch() {
        return new AuditReferenceException(
                "A resource fingerprint kind must equal the authorization decision resource kind",
                Map.of("field", "resourceFingerprint"));
    }
}