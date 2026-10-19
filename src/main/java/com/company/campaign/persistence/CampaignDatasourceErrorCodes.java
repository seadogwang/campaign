package com.company.campaign.persistence;

/**
 * The stable machine codes of the opt-in unified campaign business datasource.
 *
 * <p>They are a separate, additive vocabulary from the readiness, conversation, Preview, production audit,
 * production membership and production Campaign Agent codes: enabling the unified business datasource is host
 * configuration, and a missing pool setting must be distinguishable from a readiness probe failure, a
 * conversation store failure, a membership lookup failure, an audit write failure and a Preview failure. No
 * existing code is renamed, removed or reinterpreted.
 *
 * <p>Every code is bounded and non-disclosing: it never contains a JDBC URL, a credential, a schema name, a
 * tenant, a program, a subject, a member identifier, an event document or a driver message.
 */
public final class CampaignDatasourceErrorCodes {

    /** The unified business datasource was enabled but a required setting is absent, out of bounds or malformed. */
    public static final String CAMPAIGN_DATASOURCE_CONFIGURATION_INVALID =
            "CAMPAIGN_DATASOURCE_CONFIGURATION_INVALID";

    /** The bounded PostgreSQL business pool or its ordered migration run could not be established. */
    public static final String CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE =
            "CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE";

    /** A legacy property conflicts with the unified business datasource property; only one source must be set. */
    public static final String CAMPAIGN_DATASOURCE_LEGACY_CONFLICT =
            "CAMPAIGN_DATASOURCE_LEGACY_CONFLICT";

    /** The configured schema is reserved or does not match the required schema pattern. */
    public static final String CAMPAIGN_DATASOURCE_SCHEMA_INVALID =
            "CAMPAIGN_DATASOURCE_SCHEMA_INVALID";

    private CampaignDatasourceErrorCodes() {
    }
}