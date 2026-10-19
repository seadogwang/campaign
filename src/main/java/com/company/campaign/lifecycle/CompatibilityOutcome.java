package com.company.campaign.lifecycle;

/**
 * Outcome of one frozen-input compatibility recheck.
 *
 * <p>Only {@link #COMPATIBLE} permits the operation. {@link #INCOMPATIBLE} is an explicit refusal — the
 * Plan's frozen catalog, policy, capability or contract pins no longer describe the inputs in force — and
 * {@link #UNAVAILABLE} means no trustworthy decision could be made. Both fail closed.
 */
public enum CompatibilityOutcome {

    COMPATIBLE,
    INCOMPATIBLE,
    UNAVAILABLE
}