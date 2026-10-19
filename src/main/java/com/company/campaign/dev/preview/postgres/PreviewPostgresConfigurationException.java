package com.company.campaign.dev.preview.postgres;

/**
 * The one bounded failure of the opt-in development PostgreSQL Preview configuration.
 *
 * <p>A missing setting, an out-of-bounds pool or execution bound, a non-PostgreSQL URL, an unresolvable
 * storage catalog and an unreachable server all surface as this bounded exception carrying a stable code and a
 * fixed message. The message never contains a password, a sample key, a JDBC URL, a driver exception text or a
 * host name, so a startup failure stays diagnosable without disclosing the configuration that caused it.
 *
 * <p>The constructor that wraps an underlying failure deliberately does not retain its message: a driver
 * failure routinely embeds the connection target, and framework startup logging walks the complete cause
 * chain, so the original exception is replaced by a bounded type-only carrier.
 */
public final class PreviewPostgresConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public PreviewPostgresConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PreviewPostgresConfigurationException(String code, String message, Throwable cause) {
        super(message, bounded(cause));
        this.code = code;
    }

    /** The stable machine code of this failure. */
    public String code() {
        return code;
    }

    private static Throwable bounded(Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getName();
        return new BoundedPreviewPostgresFailure(type);
    }

    private static final class BoundedPreviewPostgresFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private BoundedPreviewPostgresFailure(String type) {
            super(type, null, false, false);
        }
    }
}