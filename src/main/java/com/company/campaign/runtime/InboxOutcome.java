package com.company.campaign.runtime;

/**
 * The closed disposition an accepted delivery receipt records.
 *
 * <p>It is deliberately a bounded business outcome and never a queue state: an Inbox receipt states what the
 * consumer <em>decided</em> about the work, while the queue's own failure and dead-letter state stays on the
 * task and outbox side. Both retain the source identity, so a dead-lettered work item and the receipt that
 * accepted it can always be reconciled.
 */
public enum InboxOutcome {

    /** A schedule-registration event was accepted and recorded; it produces no Execution of its own. */
    REGISTERED("REGISTERED"),

    /** A {@code SKIPPED} occurrence was accepted and recorded without creating any Execution. */
    SKIPPED("SKIPPED"),

    /** A {@code DUE} occurrence was accepted and created exactly one new Execution. */
    EXECUTION_CREATED("EXECUTION_CREATED");

    private final String code;

    InboxOutcome(String code) {
        this.code = code;
    }

    /** The bounded machine code persisted with a receipt of this outcome. */
    public String code() {
        return code;
    }

    /** True when this outcome created or reused an Execution. */
    public boolean hasExecution() {
        return this == EXECUTION_CREATED;
    }
}