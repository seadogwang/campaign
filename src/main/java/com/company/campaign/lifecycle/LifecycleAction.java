package com.company.campaign.lifecycle;

/**
 * The host-only lifecycle command an audit event records.
 *
 * <p>Actions are coarse-grained on purpose: an audit reader needs to know which class of host decision was
 * made, while the audit reason code carries the specific outcome. The set is closed, so a caller cannot
 * invent a new host action and have it audited as one this boundary understands.
 *
 * <p>Phase A introduced {@link #APPLY}. The publish phase <em>appends</em> its own actions after it rather
 * than inserting one, so an already-recorded value and every existing ordinal are preserved.
 */
public enum LifecycleAction {

    /** One explicit host Apply of a server-stored Plan to the editable Draft. */
    APPLY("CAMPAIGN_APPLY"),

    /** One explicit host Prepare Publish of a saved Draft into an expiring server-side preparation. */
    PREPARE_PUBLISH("CAMPAIGN_PREPARE_PUBLISH"),

    /** One explicit host Publish confirmation of a server-stored preparation. */
    PUBLISH("CAMPAIGN_PUBLISH"),

    /** One explicit host Fork that turns an immutable Published Definition into a new editable Draft. */
    FORK_PUBLISHED("CAMPAIGN_FORK_PUBLISHED"),

    /**
     * One explicit host full publication that freezes an accepted Published Definition together with its
     * compiled graph artifact and its bounded time artifact, and emits durable scheduling work.
     *
     * <p>It is appended after {@link #FORK_PUBLISHED} rather than inserted, so an already-recorded value and
     * every existing ordinal are preserved. It is a distinct action from {@link #PUBLISH}: a full publication
     * is the T21 boundary that additionally freezes the compiled graph and the bounded time artifact.
     */
    FULL_PUBLISH("CAMPAIGN_FULL_PUBLISH"),

    /**
     * One explicit host scheduling operation — an idempotent due-occurrence commit, a pause or a resume.
     *
     * <p>It is appended last for the same reason: it is a scheduling operation on an already-published
     * version, not a publication of one.
     */
    SCHEDULE_OPERATE("CAMPAIGN_SCHEDULE_OPERATE");

    private final String wireName;

    LifecycleAction(String wireName) {
        this.wireName = wireName;
    }

    /** Stable wire and audit spelling of this action. */
    public String wireName() {
        return wireName;
    }
}