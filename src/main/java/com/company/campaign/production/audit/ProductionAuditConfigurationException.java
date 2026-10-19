package com.company.campaign.production.audit;

/**
 * The one bounded failure of the production durable audit configuration.
 *
 * <p>A missing setting, an out-of-bounds bound, a reserved schema, an unreachable database and a failed
 * migration all surface as this bounded exception carrying a stable code and a fixed message. The message never
 * contains a JDBC URL, a credential, a schema name, a driver exception text or a host name, so a startup failure
 * is diagnosable without disclosing the configuration that caused it.
 */
public final class ProductionAuditConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ProductionAuditConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Wraps an underlying failure without retaining its sensitive message, stack or nested causes.
     *
     * <p>A driver failure routinely embeds the connection target in its message, and framework startup logging
     * walks the complete cause chain, so the original exception is replaced by a bounded type-only carrier.
     */
    public ProductionAuditConfigurationException(String code, String message, Throwable cause) {
        super(message, ProductionAuditBoundedFailure.of(cause));
        this.code = code;
    }

    /** The stable machine code of this failure. */
    public String code() {
        return code;
    }
}