package com.company.campaign.security.context;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Fail-closed failure raised while deriving a trusted execution context.
 *
 * <p>The message and details never carry credentials, tokens or foreign identifiers; only the field that
 * failed and, for an inactive membership, the membership's own identifier are exposed.
 */
public final class TrustedContextException extends ContractException {

    private static final long serialVersionUID = 1L;

    private TrustedContextException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    /** An input to the trusted context is missing or malformed. */
    public static TrustedContextException invalid(String field) {
        return new TrustedContextException(
                ContractErrorCodes.TRUSTED_CONTEXT_INVALID,
                "Trusted context is invalid",
                Map.of("field", String.valueOf(field)));
    }

    /** The verified identity and the membership name different users. */
    public static TrustedContextException identityMismatch() {
        return new TrustedContextException(
                ContractErrorCodes.TRUSTED_IDENTITY_MISMATCH,
                "Verified identity does not match the membership",
                Map.of());
    }

    /** The membership is not active. */
    public static TrustedContextException inactiveMembership(String membershipId) {
        return new TrustedContextException(
                ContractErrorCodes.MEMBERSHIP_INACTIVE,
                "Membership is not active",
                Map.of("membershipId", String.valueOf(membershipId)));
    }

    /** The membership and the execution-context snapshot disagree. */
    public static TrustedContextException contextMismatch(String field) {
        return new TrustedContextException(
                ContractErrorCodes.TRUSTED_CONTEXT_MISMATCH,
                "Membership and execution context disagree",
                Map.of("field", String.valueOf(field)));
    }
}