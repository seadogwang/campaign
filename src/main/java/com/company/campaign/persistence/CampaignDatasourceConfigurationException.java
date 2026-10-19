package com.company.campaign.persistence;

/**
 * The one bounded failure of the opt-in unified campaign business datasource configuration.
 *
 * <p>A missing setting, an out-of-bounds pool size, a reserved schema name, a legacy conflict, an unreachable
 * server and a failed migration all surface as this bounded exception carrying a stable code and a fixed
 * message. The message never contains a password, a JDBC URL, a driver exception text or a host name, so a
 * startup failure is diagnosable without disclosing the configuration that caused it.
 */
public final class CampaignDatasourceConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public CampaignDatasourceConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Wraps an underlying failure without retaining its sensitive message or stack.
     *
     * <p>A driver failure routinely embeds the connection target in its message, and framework startup logging
     * walks the complete cause chain, so the original exception is replaced by a bounded type-only carrier.
     */
    public CampaignDatasourceConfigurationException(String code, String message, Throwable cause) {
        super(message, bounded(cause));
        this.code = code;
    }

    /** The stable machine code of this failure. */
    public String code() {
        return code;
    }

    private static Throwable bounded(Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getName();
        return new BoundedDatasourceFailure(type);
    }

    private static final class BoundedDatasourceFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private BoundedDatasourceFailure(String type) {
            super(type, null, false, false);
        }
    }
}