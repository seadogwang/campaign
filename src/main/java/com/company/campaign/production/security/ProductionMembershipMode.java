package com.company.campaign.production.security;

/**
 * Where the production membership facts come from.
 *
 * <p>The two, and only two, sources the production host may select. The set is closed so a new mode cannot be
 * invented in configuration and silently treated as one of the accepted ones.
 */
public enum ProductionMembershipMode {

    /** The durable server-owned PostgreSQL membership store, over the dedicated qualified pool and migration. */
    POSTGRESQL,

    /** A host-supplied {@link ProductionMembershipRepository}; no production pool or migration is created. */
    HOST
}