package com.company.campaign.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Synchronized in-memory {@link RuntimeAuditSink} for tests and local use.
 *
 * <p>It retains the bounded runtime Outbox/Audit records a local store already keeps, so a test can read one
 * ordered log. A deterministic test can subclass it and fail on demand to prove that a failing audit rolls the
 * whole commit back.
 *
 * <p>This is a test/local adapter only: T22 implements no production audit transport.
 */
public class InMemoryRuntimeAuditSink implements RuntimeAuditSink {

    private final List<RuntimeAuditRecord> records = new ArrayList<>();

    @Override
    public synchronized void record(RuntimeAuditRecord record) {
        if (record == null) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "audit");
        }
        if (records.size() >= RuntimeVersions.MAX_AUDIT_RECORDS) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, "audit",
                    "The local runtime audit sink cannot accept another record.");
        }
        records.add(record);
    }

    @Override
    public synchronized void recordAll(List<RuntimeAuditRecord> batch) {
        if (batch == null || batch.isEmpty()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "audits");
        }
        for (RuntimeAuditRecord record : batch) {
            if (record == null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "audit");
            }
        }
        if (records.size() + batch.size() > RuntimeVersions.MAX_AUDIT_RECORDS) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, "audit",
                    "The local runtime audit sink cannot accept this batch.");
        }
        records.addAll(batch);
    }

    /** Every record this sink accepted, in record order. */
    public synchronized List<RuntimeAuditRecord> records() {
        return List.copyOf(records);
    }

    /** The number of records this sink accepted. */
    public synchronized int count() {
        return records.size();
    }

    /** Redacted diagnostics: the sink holds no owner value. */
    @Override
    public String toString() {
        return "InMemoryRuntimeAuditSink[records=" + count() + ']';
    }
}
