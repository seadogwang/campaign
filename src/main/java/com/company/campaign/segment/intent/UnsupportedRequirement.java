package com.company.campaign.segment.intent;

/**
 * Closed set of requirements the model must record as unsupported or forbidden rather than attempt.
 *
 * <p>Each value names a capability the v1.3 boundary does not implement, or an attempt to bypass a rule.
 * The resolver maps any recorded value to {@code UNSUPPORTED}; a bypass attempt can therefore never become
 * a Metadata candidate or an executable instruction.
 */
public enum UnsupportedRequirement {

    RAW_SQL,
    SCRIPT,
    ARBITRARY_JSON_QUERY,
    GEO,
    PREDICTIVE_SEGMENT,
    AI_CREATED_METADATA,
    CROSS_TENANT_LOOKUP,
    AUTOMATIC_PUBLISH,
    PRODUCTION_MUTATION,
    PERMISSION_BYPASS,
    DATASCOPE_BYPASS,
    TENANT_OVERRIDE,
    INSTRUCTION_INJECTION
}