package com.company.campaign.evaluation.segment.reference;

/**
 * The closed set of re-checks a reference-corpus case may perform after a first successful resolution.
 *
 * <p>Each constant models one way authority can lapse between two steps: the referenced version can be
 * withdrawn, the caller's permission can be revoked, and the last retention hold protecting the version can
 * be released. A resolution is never permanent authority, and these are the three drifts the reference core
 * promises to observe on a re-check.
 */
public enum ReferenceRecheckKind {

    /** The referenced version is withdrawn, so a later exact lookup observes it as unavailable. */
    WITHDRAW_AVAILABILITY,

    /** The caller's permission is revoked, so a later re-authorization refuses the reference. */
    DENY_AUTHORIZATION,

    /** The retention hold protecting the version is released, so a later retention check observes no hold. */
    RELEASE_RETENTION_HOLD;

    /** True when this re-check needs a retention hold identifier to release. */
    public boolean requiresHoldId() {
        return this == RELEASE_RETENTION_HOLD;
    }
}