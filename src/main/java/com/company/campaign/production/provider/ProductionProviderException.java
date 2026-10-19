package com.company.campaign.production.provider;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A fail-closed production Provider failure carrying exactly one stable {@link ProductionProviderErrorCodes}
 * code.
 *
 * <p>It is raised only for a configuration or evidence defect that is decided before any Provider call exists:
 * a rejected configuration aborts startup, and an invalid evidence record is refused at construction. A failure
 * observed <em>during</em> a Provider call is not this type: it is the accepted
 * {@link com.company.campaign.action.ActionProviderException}, so the Action machine records an unknown remote
 * outcome rather than a bounded local rejection.
 *
 * <p>It never carries the endpoint, the credential, the tenant, the recipient reference, the routing pin, a
 * Provider reference, the request payload or any raw provider-derived text: only the stable code and a fixed
 * caller-safe message that names at most a configuration field.
 */
public class ProductionProviderException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ProductionProviderException(String code, String message) {
        super(code, message, Map.of());
    }

    public ProductionProviderException(String code, String message, Throwable cause) {
        super(code, message, Map.of(), cause);
    }

    /** A bounded failure carrying one stable code and a value-free message. */
    public static ProductionProviderException of(String code, String message) {
        return new ProductionProviderException(code, message);
    }
}