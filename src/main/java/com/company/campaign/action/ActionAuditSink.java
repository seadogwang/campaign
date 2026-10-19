package com.company.campaign.action;

import java.util.List;

/**
 * The one collaborator every action transaction fans its Outbox/Audit batch out through.
 *
 * <p>It is deliberately batch-shaped rather than record-shaped: one transaction's audit records are recorded
 * together or not at all, exactly as one transaction's state is written together or not at all. A sink that
 * cannot accept the whole batch reports {@link ActionErrorCodes#ACTION_AUDIT_BATCH_UNSUPPORTED} rather than
 * silently dropping a record, because a dropped audit record is exactly the kind of silent loss this layer
 * refuses.
 *
 * <h2>The accepted commit protocol</h2>
 *
 * <p>A sink takes part in one action commit through exactly three calls, always on the thread of control that
 * owns the store's monitor:
 *
 * <ol>
 *   <li>{@link #recordAll(List)} stages or records the whole batch of one action transaction;</li>
 *   <li>the owning store calls {@link #commitAll(List)} <strong>exactly once</strong>, with the exact same batch,
 *       only at the final accepted boundary — that is, once every remaining fallible participant of the same
 *       commit (the store's own commit seam and any other collaborator such as Runtime completion) has
 *       succeeded;</li>
 *   <li>if any fallible participant after {@link #recordAll(List)} fails, the store calls
 *       {@link #rollbackAll(List)} for that exact batch instead and never {@link #commitAll(List)}.</li>
 * </ol>
 *
 * <p>A batch that was staged and never committed is discarded, never silently committed: a later
 * {@link #recordAll(List)} while one batch is still open fails closed rather than committing the earlier one.
 * An idle transaction timeout is not a substitute for an accepted commit.
 */
public interface ActionAuditSink {

    /** Records one entire action transaction's audit batch, or refuses the whole batch. */
    void recordAll(List<ActionAuditRecord> records);

    /**
     * The accepted commit boundary of one action transaction's audit batch.
     *
     * <p>The owning store calls this exactly once, with the exact batch {@link #recordAll(List)} accepted, once
     * every remaining fallible participant of the same commit has succeeded. A sink that stages an uncommitted
     * batch must override it and make durable exactly the batch named by this argument: a missing, mismatched or
     * repeated commit must fail closed and never commit anything else.
     *
     * <p>The default implementation only validates that this is a present batch of present records and does
     * nothing else. It is the accepted behavior of a sink that becomes durable at {@link #recordAll(List)} — an
     * in-memory or fixture sink — so every existing sink stays compatible without changing the records it
     * already exposes.
     */
    default void commitAll(List<ActionAuditRecord> records) {
        requireCommittable(records);
    }

    /** Rolls back the exact most recently recorded batch when a later participant in the same commit fails. */
    void rollbackAll(List<ActionAuditRecord> records);

    private static void requireCommittable(List<ActionAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "audits");
        }
        for (ActionAuditRecord record : records) {
            if (record == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "audit");
            }
        }
    }
}