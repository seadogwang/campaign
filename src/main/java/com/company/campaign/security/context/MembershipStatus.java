package com.company.campaign.security.context;

/**
 * Lifecycle state of a principal's tenant/program membership.
 *
 * <p>Only {@link #ACTIVE} may produce a trusted execution context. The set is closed so a new state cannot
 * be invented in a payload and silently treated as active.
 */
public enum MembershipStatus {
    ACTIVE,
    INACTIVE
}