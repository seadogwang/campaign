package com.company.campaign.lifecycle;

/**
 * Version discriminators stamped into every host-only lifecycle artifact.
 *
 * <p>The lifecycle boundary is a versioned transformation: a change to a hashing, ownership or
 * idempotency rule is a new version, so an artifact produced under one rule set can never silently compare
 * with an artifact produced under another. Each value is part of its artifact's canonical hash material
 * and is reported verbatim on the artifact.
 */
public final class LifecycleVersions {

    /** The saved editable Campaign Draft document format version. */
    public static final String DRAFT_FORMAT_VERSION = "campaign-draft/1.0";

    /** The immutable Apply receipt document format version. */
    public static final String APPLY_RECEIPT_FORMAT_VERSION = "campaign-apply-receipt/1.0";

    /** The server-stored publish preparation document format version. */
    public static final String PUBLISH_PREPARATION_FORMAT_VERSION = "campaign-publish-preparation/1.0";

    /** The immutable Published Campaign Definition document format version. */
    public static final String PUBLISHED_DEFINITION_FORMAT_VERSION = "campaign-published-definition/1.0";

    /** The immutable Publish receipt document format version. */
    public static final String PUBLISH_RECEIPT_FORMAT_VERSION = "campaign-publish-receipt/1.0";

    private LifecycleVersions() {
    }
}