package com.company.campaign.production.security;

/**
 * The one bounded failure of a production membership lookup.
 *
 * <p>A refused connection, a query timeout, a malformed row and a driver failure all surface as this bounded
 * exception carrying the stable {@link ProductionSecurityErrorCodes#PRODUCTION_MEMBERSHIP_UNAVAILABLE} code and a
 * fixed message. The message never contains a JDBC URL, a credential, a subject, a membership identifier, a
 * claimed permission or a driver exception text, and the original cause is replaced by a type-only carrier.
 */
public final class ProductionMembershipUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductionMembershipUnavailableException(Throwable cause) {
        super("The production membership lookup is unavailable.", bounded(cause));
    }

    public ProductionMembershipUnavailableException() {
        super("The production membership lookup is unavailable.");
    }

    /** The stable machine code of this failure. */
    public String code() {
        return ProductionSecurityErrorCodes.PRODUCTION_MEMBERSHIP_UNAVAILABLE;
    }

    private static Throwable bounded(Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getName();
        return new BoundedMembershipFailure(type);
    }

    private static final class BoundedMembershipFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private BoundedMembershipFailure(String type) {
            super(type, null, false, false);
        }
    }
}