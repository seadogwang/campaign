package com.company.campaign.production.security;

/**
 * The one bounded failure of the production identity configuration.
 *
 * <p>A missing setting, an out-of-bounds bound, a reserved schema, an unreachable JWK Set, an unreachable
 * database and a failed migration all surface as this bounded exception carrying a stable code and a fixed
 * message. The message never contains an issuer URL, a JWK Set URL, a credential, a JDBC URL, a driver
 * exception text or a host name, so a startup failure is diagnosable without disclosing the configuration that
 * caused it.
 */
public final class ProductionSecurityConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ProductionSecurityConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Wraps an underlying failure without retaining its sensitive message or stack.
     *
     * <p>A decoder or driver failure routinely embeds the configured URI or connection target in its message, and
     * framework startup logging walks the complete cause chain, so the original exception is replaced by a
     * bounded type-only carrier.
     */
    public ProductionSecurityConfigurationException(String code, String message, Throwable cause) {
        super(message, bounded(cause));
        this.code = code;
    }

    /** The stable machine code of this failure. */
    public String code() {
        return code;
    }

    private static Throwable bounded(Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getName();
        return new BoundedProductionFailure(type);
    }

    private static final class BoundedProductionFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private BoundedProductionFailure(String type) {
            super(type, null, false, false);
        }
    }
}