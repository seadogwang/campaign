package com.company.campaign.production.audit;

/**
 * The one bounded failure of the production durable audit ledger at runtime.
 *
 * <p>A changed-payload replay, an invalid batch, a rollback that does not match the staged batch and any JDBC,
 * codec or binding failure at the ledger boundary all surface as this bounded exception carrying a stable code
 * and a fixed message. The message never contains a JDBC URL, a credential, an event document, an event
 * identifier, a tenant, a program, a subject or a driver message, so an audit failure can be reported to a
 * caller without becoming a channel for the data the ledger refuses to persist.
 */
public final class ProductionAuditException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ProductionAuditException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Wraps an underlying failure without retaining its sensitive message, stack or nested causes. */
    public ProductionAuditException(String code, String message, Throwable cause) {
        super(message, ProductionAuditBoundedFailure.of(cause));
        this.code = code;
    }

    /** The stable machine code of this failure. */
    public String code() {
        return code;
    }

    /** The one non-disclosing code every JDBC, codec and binding failure maps to. */
    static ProductionAuditException unavailable(Throwable cause) {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_UNAVAILABLE,
                "The production durable audit ledger is unavailable.",
                cause);
    }

    /** The non-disclosing code every JDBC, codec and binding failure maps to, with no cause at all. */
    static ProductionAuditException unavailable() {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_UNAVAILABLE,
                "The production durable audit ledger is unavailable.");
    }

    /** One accepted source record whose own canonical hash does not match its material. */
    static ProductionAuditException sourceHashMismatch() {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_SOURCE_HASH_MISMATCH,
                "The durable audit adapter refused a source record whose canonical hash does not match it.");
    }
}