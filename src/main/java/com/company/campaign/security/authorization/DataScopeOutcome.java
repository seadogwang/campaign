package com.company.campaign.security.authorization;

/**
 * Outcome of a DataScope policy evaluation.
 *
 * <p>Only {@link #ALLOWED} permits access. {@link #DENIED} is an explicit policy refusal and
 * {@link #UNAVAILABLE} means no trustworthy decision could be made; both fail closed.
 */
public enum DataScopeOutcome {
    ALLOWED,
    DENIED,
    UNAVAILABLE
}