package com.company.campaign.production.audit;

/**
 * The bounded, type-only carrier both production audit failures use as their cause.
 *
 * <p>A JDBC driver failure routinely embeds the JDBC URL, the host, the database name and sometimes the
 * credential in its message, and framework startup logging walks the complete cause chain. Wrapping the original
 * failure in this carrier keeps the exception <em>type</em> — the only part that is safe and useful — and
 * discards the message, the stack and every nested cause.
 */
final class ProductionAuditBoundedFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private ProductionAuditBoundedFailure(String type) {
        super(type, null, false, false);
    }

    /** A bounded carrier naming only the original failure type. */
    static ProductionAuditBoundedFailure of(Throwable cause) {
        return new ProductionAuditBoundedFailure(cause == null ? "unknown" : cause.getClass().getName());
    }
}