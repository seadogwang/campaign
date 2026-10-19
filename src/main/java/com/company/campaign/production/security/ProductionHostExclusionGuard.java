package com.company.campaign.production.security;

import org.springframework.core.env.Environment;

import java.util.Objects;

/**
 * Fails startup when the development Campaign Agent host and the production identity boundary are both enabled.
 *
 * <p>The two hosts own the same request path with different identity sources, so enabling both would leave the
 * application with two, contradictory answers to "who is the caller". Rather than silently preferring one, the
 * start aborts with exactly one bounded, redacted configuration code.
 */
public final class ProductionHostExclusionGuard {

    /** The property that opts into the local development Campaign Agent host. */
    public static final String DEVELOPMENT_ENABLED_PROPERTY = "campaign.agent.development.enabled";

    private final boolean developmentHostDisabled;

    public ProductionHostExclusionGuard(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        boolean developmentEnabled =
                environment.getProperty(DEVELOPMENT_ENABLED_PROPERTY, Boolean.class, Boolean.FALSE);
        if (developmentEnabled) {
            throw new ProductionSecurityConfigurationException(
                    ProductionSecurityErrorCodes.PRODUCTION_DEVELOPMENT_HOST_CONFLICT,
                    "The development Campaign Agent host and the production identity boundary cannot be enabled "
                            + "together.");
        }
        this.developmentHostDisabled = true;
    }

    /** True when the guard was created, i.e. the development host is not enabled. */
    public boolean developmentHostDisabled() {
        return developmentHostDisabled;
    }
}