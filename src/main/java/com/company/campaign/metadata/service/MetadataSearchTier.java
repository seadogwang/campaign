package com.company.campaign.metadata.service;

/**
 * Deterministic lexical discovery tiers for T04 search, in match-priority order.
 *
 * <p>These are catalog-discovery categories, not semantic confidence: a later resolver (T10) owns
 * evidence, scores and acceptance, and this task must not produce any of those.
 */
public enum MetadataSearchTier {

    /** The normalized stable definition ID equals the normalized query. */
    EXACT_ID,

    /** The normalized display name or an alias equals the normalized query. */
    EXACT_NAME,

    /** The normalized ID, display name or alias starts with the normalized query. */
    PREFIX,

    /** The normalized ID, display name or alias contains the normalized query. */
    CONTAINS
}