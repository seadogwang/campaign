package com.company.campaign.security.audit;

/**
 * Outward classification of a recorded security decision.
 *
 * <p>{@link #NOT_FOUND} collapses absent and cross-boundary resources, matching the non-disclosing
 * behaviour of {@code ResourceNotFoundException}.
 */
public enum SecurityDisposition {

    ALLOWED,
    DENIED,
    NOT_FOUND,
    UNAVAILABLE,
    INVALID
}