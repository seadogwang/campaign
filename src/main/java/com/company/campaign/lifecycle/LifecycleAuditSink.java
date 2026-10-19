package com.company.campaign.lifecycle;

import java.util.List;
import java.util.Objects;

/**
 * Port that receives safe lifecycle audit events.
 *
 * <p>Production persistence belongs to a later integration task; this port exists so the lifecycle boundary
 * can emit its audit event without knowing where it goes. A sink that fails must fail the operation closed:
 * the boundary commits no lifecycle state when its audit event cannot be recorded.
 */
@FunctionalInterface
public interface LifecycleAuditSink {

    void record(LifecycleAuditEvent event);

    /**
     * Records one bounded batch atomically, or records none of it.
     *
     * <p>A legacy single-event sink remains compatible and may accept a batch of one. It fails closed before
     * invoking {@link #record(LifecycleAuditEvent)} for a larger batch, because sequential default writes would
     * leave a partial audit trail when a later event failed. Transactional adapters that support larger batches
     * must override this method with an atomic implementation.
     */
    default void recordAll(List<LifecycleAuditEvent> events) {
        Objects.requireNonNull(events, "events");
        for (LifecycleAuditEvent event : events) {
            Objects.requireNonNull(event, "event");
        }
        if (events.isEmpty()) {
            return;
        }
        if (events.size() != 1) {
            throw new IllegalStateException("audit sink does not support atomic batches");
        }
        record(events.get(0));
    }
}
