package com.company.campaign.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory {@link LifecycleAuditSink} for deterministic tests and local use.
 *
 * <p>It is intentionally not thread-safe: the synchronized transactional adapter that owns it serializes
 * every write, so a test that shares one instance across threads goes through that adapter rather than
 * through this sink directly. Each test creates its own instance, so no mutable state is shared globally.
 */
public final class InMemoryLifecycleAuditSink implements LifecycleAuditSink {

    private final List<LifecycleAuditEvent> events = new ArrayList<>();

    @Override
    public void record(LifecycleAuditEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void recordAll(List<LifecycleAuditEvent> batch) {
        Objects.requireNonNull(batch, "batch");
        List<LifecycleAuditEvent> validated = new ArrayList<>(batch.size());
        for (LifecycleAuditEvent event : batch) {
            validated.add(Objects.requireNonNull(event, "event"));
        }
        events.addAll(validated);
    }

    /** Immutable snapshot of the recorded events, in insertion order. */
    public List<LifecycleAuditEvent> events() {
        return List.copyOf(events);
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
