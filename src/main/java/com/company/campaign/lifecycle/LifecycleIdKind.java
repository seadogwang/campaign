package com.company.campaign.lifecycle;

/**
 * The closed set of server-side identities a host lifecycle operation allocates.
 *
 * <p>Identity is always allocated by the server, never chosen by a caller, a model or a prompt: a CREATE
 * Apply allocates a Campaign and its Draft, and every accepted Apply allocates its receipt. The set is
 * closed so a caller cannot ask for an identity of a kind this boundary does not own.
 */
public enum LifecycleIdKind {

    /** The Campaign a CREATE Apply allocates. */
    CAMPAIGN("campaign"),

    /** The editable Draft a CREATE Apply or a host-only Published fork allocates. */
    DRAFT("draft"),

    /** The immutable Apply receipt every accepted Apply allocates. */
    APPLY_RECEIPT("apply-receipt"),

    /** The expiring server-side publish preparation every accepted Prepare Publish allocates. */
    PREPARATION("publish-preparation"),

    /** The immutable Published Definition every accepted Publish allocates. */
    PUBLISHED_DEFINITION("published-definition"),

    /** The immutable Publish receipt every accepted Publish allocates. */
    PUBLISH_RECEIPT("publish-receipt"),

    /**
     * The expiring server-side full publication preparation every accepted Prepare Full Publish allocates.
     *
     * <p>It is appended after the accepted kinds rather than inserted, so every existing ordinal and every
     * already-generated identifier are preserved.
     */
    FULL_PREPARATION("full-publish-preparation"),

    /** The immutable full publication definition every accepted full publication allocates. */
    FULL_PUBLISHED_DEFINITION("full-published-definition"),

    /** The immutable full publication receipt every accepted full publication allocates. */
    FULL_PUBLISH_RECEIPT("full-publish-receipt"),

    /** The immutable scheduling-registration work event every accepted full publication allocates. */
    SCHEDULE_REGISTRATION_EVENT("schedule-registration-event");

    private final String prefix;

    LifecycleIdKind(String prefix) {
        this.prefix = prefix;
    }

    /** The bounded, server-owned identifier prefix for this kind. */
    public String prefix() {
        return prefix;
    }
}