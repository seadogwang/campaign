package com.company.campaign.action;

import java.util.ArrayList;
import java.util.List;

/**
 * Synchronized in-memory {@link ActionAuditSink} for tests and local use.
 *
 * <p>It retains the bounded action Outbox/Audit records a local store already keeps, so a test can read one
 * ordered log. A deterministic test can subclass it and fail on demand to prove that a failing audit rolls the
 * whole action commit back.
 *
 * <p>This is a test/local adapter only: T23 implements no production audit transport.
 */
public class InMemoryActionAuditSink implements ActionAuditSink {

    private final List<ActionAuditRecord> records = new ArrayList<>();

    @Override
    public synchronized void recordAll(List<ActionAuditRecord> batch) {
        if (batch == null || batch.isEmpty()) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "audits");
        }
        for (ActionAuditRecord record : batch) {
            if (record == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "audit");
            }
        }
        if (records.size() + batch.size() > ActionVersions.MAX_ACTION_RECORDS) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BOUND_EXCEEDED, "audit",
                    "The local action audit sink cannot accept this batch.");
        }
        records.addAll(batch);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This sink becomes durable at {@link #recordAll(List)}, so the accepted boundary only validates that the
     * argument is the batch the store recorded and confirms it; it never changes the externally observed
     * records. A {@link #rollbackAll(List)} removes the exact batch instead.
     */
    @Override
    public synchronized void commitAll(List<ActionAuditRecord> batch) {
        ActionAuditSink.super.commitAll(batch);
    }

    @Override
    public synchronized void rollbackAll(List<ActionAuditRecord> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > records.size()) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_STORE_CONFLICT, "audits");
        }
        int start = records.size() - batch.size();
        if (!records.subList(start, records.size()).equals(batch)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_STORE_CONFLICT, "audits");
        }
        records.subList(start, records.size()).clear();
    }

    /** Every record this sink accepted, in record order. */
    public synchronized List<ActionAuditRecord> records() {
        return List.copyOf(records);
    }

    /** The number of records this sink accepted. */
    public synchronized int count() {
        return records.size();
    }

    /** Redacted diagnostics: the sink holds no owner value. */
    @Override
    public String toString() {
        return "InMemoryActionAuditSink[records=" + count() + ']';
    }
}
