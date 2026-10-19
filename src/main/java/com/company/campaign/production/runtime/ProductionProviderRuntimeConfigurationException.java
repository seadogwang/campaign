package com.company.campaign.production.runtime;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A fail-closed production provider runtime failure carrying exactly one stable
 * {@link ProductionProviderRuntimeErrorCodes} code.
 *
 * <p>It is raised only for a configuration or wiring defect decided before any worker or Provider call exists.
 * It never carries a credential, a URL, a schema name, a tenant, a member, a recipient, a Provider reference, a
 * key, a payload or any raw collaborator-derived text: only the stable code and a fixed caller-safe message.
 */
public class ProductionProviderRuntimeConfigurationException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ProductionProviderRuntimeConfigurationException(String code, String message) {
        super(code, message, Map.of());
    }

    public ProductionProviderRuntimeConfigurationException(String code, String message, Throwable cause) {
        super(code, message, Map.of(), cause);
    }
}