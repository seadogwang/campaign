package com.company.campaign.security.audit;

/**
 * The security decision an audit event records.
 *
 * <p>Actions are coarse-grained on purpose: an audit reader needs to know which class of decision was made,
 * while the reason code carries the specific outcome.
 */
public enum SecurityAction {

    TRUSTED_CONTEXT_RESOLUTION,
    RESOURCE_AUTHORIZATION,
    DATA_PROJECTION,
    RETENTION_DECISION
}