package com.company.campaign.readiness.postgres;

/**
 * The one bounded failure of the opt-in PostgreSQL configuration.
 *
 * <p>A missing setting, an out-of-bounds pool size, a reserved schema name, an unreachable server and a
 * failed migration all surface as this bounded exception carrying a stable code and a fixed message. The
 * message never contains a password, a JDBC URL, a driver exception text or a host name, so a startup failure
 * is diagnosable without disclosing the configuration that caused it.
 */
public final class ReadinessPostgresConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ReadinessPostgresConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Wraps an underlying failure without retaining its sensitive message or stack.
     *
     * <p>A driver failure routinely embeds the connection target in its message. Framework startup logging
     * walks the complete cause chain, so retaining the original exception would disclose that target even
     * though this exception's own message is bounded.
     */
    public ReadinessPostgresConfigurationException(String code, String message, Throwable cause) {
        super(message, bounded(cause));
        this.code = code;
    }

    /** The stable machine code of this failure. */
    public String code() {
        return code;
    }

    private static Throwable bounded(Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getName();
        return new BoundedPostgresFailure(type);
    }

    private static final class BoundedPostgresFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private BoundedPostgresFailure(String type) {
            super(type, null, false, false);
        }
    }
}
