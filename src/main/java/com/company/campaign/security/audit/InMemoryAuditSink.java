package com.company.campaign.security.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory {@link AuditSink} for deterministic tests only.
 *
 * <p>It is intentionally not thread-safe and must not be used as a production sink. Each test creates its
 * own instance, so no mutable state is shared globally.
 */
public final class InMemoryAuditSink implements AuditSink {

    private final List<SecurityAuditEvent> events = new ArrayList<>();

    @Override
    public void record(SecurityAuditEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /** Immutable snapshot of the recorded events, in insertion order. */
    public List<SecurityAuditEvent> events() {
        return List.copyOf(events);
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}