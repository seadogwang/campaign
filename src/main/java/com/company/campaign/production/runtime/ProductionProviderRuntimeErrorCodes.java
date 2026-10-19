package com.company.campaign.production.runtime;

/**
 * The closed set of bounded, non-disclosing failure codes of the default-off production provider runtime host.
 *
 * <p>Every startup and cycle failure maps to exactly one of these codes. No code carries a credential, a JDBC
 * URL, a schema name, a tenant, a program, a member, a recipient, a Provider reference, an idempotency key, a
 * raw exception message or any payload.
 */
public final class ProductionProviderRuntimeErrorCodes {

    /** The opt-in production provider runtime configuration is missing or out of bounds. */
    public static final String CONFIGURATION_INVALID = "PRODUCTION_PROVIDER_RUNTIME_CONFIGURATION_INVALID";

    /** No accepted durable Runtime store is wired, or more than one is. */
    public static final String DURABLE_RUNTIME_STORE_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_DURABLE_RUNTIME_STORE_REQUIRED";

    /** More than one durable Runtime store is wired, so the wiring is ambiguous. */
    public static final String DURABLE_RUNTIME_STORE_AMBIGUOUS =
            "PRODUCTION_PROVIDER_RUNTIME_DURABLE_RUNTIME_STORE_AMBIGUOUS";

    /** No accepted durable Action store is wired, or more than one is. */
    public static final String DURABLE_ACTION_STORE_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_DURABLE_ACTION_STORE_REQUIRED";

    /** More than one durable Action store is wired, so the wiring is ambiguous. */
    public static final String DURABLE_ACTION_STORE_AMBIGUOUS =
            "PRODUCTION_PROVIDER_RUNTIME_DURABLE_ACTION_STORE_AMBIGUOUS";

    /** No accepted production durable audit adapter is wired for the Runtime or Action boundary. */
    public static final String DURABLE_AUDIT_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_DURABLE_AUDIT_REQUIRED";

    /** No production Runtime work-delivery port is wired, or more than one is. */
    public static final String WORK_DELIVERY_PORT_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_WORK_DELIVERY_PORT_REQUIRED";

    /** No production Campaign runtime/schedule state port is wired, or more than one is. */
    public static final String CAMPAIGN_STATE_PORT_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_CAMPAIGN_STATE_PORT_REQUIRED";

    /** No production Runtime fact port is wired, or more than one is. */
    public static final String FACT_PORT_REQUIRED = "PRODUCTION_PROVIDER_RUNTIME_FACT_PORT_REQUIRED";

    /** No production audience materialization port is wired, or more than one is. */
    public static final String AUDIENCE_PORT_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_AUDIENCE_PORT_REQUIRED";

    /** No production action recipient resolver port is wired, or more than one is. */
    public static final String RECIPIENT_RESOLVER_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_RECIPIENT_RESOLVER_REQUIRED";

    /** No production Message provider adapter is wired, or more than one is. */
    public static final String MESSAGE_PROVIDER_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_MESSAGE_PROVIDER_REQUIRED";

    /** No production Reward provider adapter is wired, or more than one is. */
    public static final String REWARD_PROVIDER_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_REWARD_PROVIDER_REQUIRED";

    /** The wired Message or Reward provider is a simulated or otherwise non-production implementation. */
    public static final String SIMULATED_PROVIDER_REJECTED =
            "PRODUCTION_PROVIDER_RUNTIME_SIMULATED_PROVIDER_REJECTED";

    /** No bounded tenant source is wired, or more than one is. */
    public static final String TENANT_SOURCE_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_TENANT_SOURCE_REQUIRED";

    /** The bounded tenant source returned duplicates, blanks or more tenants than the configured bound. */
    public static final String TENANT_SOURCE_INVALID =
            "PRODUCTION_PROVIDER_RUNTIME_TENANT_SOURCE_INVALID";

    /** No narrow host observability port is wired, or more than one is. */
    public static final String OBSERVABILITY_PORT_REQUIRED =
            "PRODUCTION_PROVIDER_RUNTIME_OBSERVABILITY_PORT_REQUIRED";

    /** The opt-in background scheduler configuration is missing or out of bounds. */
    public static final String SCHEDULER_INVALID = "PRODUCTION_PROVIDER_RUNTIME_SCHEDULER_INVALID";

    /** One cycle item failed with a non-contract failure, so only a bounded generic code is disclosed. */
    public static final String CYCLE_ITEM_FAILED = "PRODUCTION_PROVIDER_RUNTIME_CYCLE_ITEM_FAILED";

    private ProductionProviderRuntimeErrorCodes() {
    }
}