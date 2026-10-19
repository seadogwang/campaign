package com.company.campaign.readiness;

import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic in-memory readiness audit sink used by local development and tests.
 *
 * <p>It validates the whole batch before appending any element, so a rejected batch leaves the trail exactly
 * as it was. It is not thread-safe on its own; {@link InMemoryReadinessStore} serializes every write through
 * it.
 */
public final class InMemoryReadinessAuditSink implements ReadinessAuditSink {

    private final List<ReadinessAuditEvent> events = new ArrayList<>();

    @Override
    public void record(ReadinessAuditEvent event) {
        recordAll(List.of(ReadinessSupport.requireNonNull(event, "event")));
    }

    @Override
    public void recordAll(List<ReadinessAuditEvent> batch) {
        if (batch == null || batch.isEmpty()) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_AUDIT_INVALID,
                    "A readiness audit batch must carry at least one event.");
        }
        for (ReadinessAuditEvent event : batch) {
            ReadinessSupport.requireNonNull(event, "event");
        }
        events.addAll(batch);
    }

    /** Every recorded event, in append order. */
    public List<ReadinessAuditEvent> events() {
        return List.copyOf(events);
    }

    /** The number of recorded events. */
    public int size() {
        return events.size();
    }

    /** Discards every recorded event. */
    public void clear() {
        events.clear();
    }
}