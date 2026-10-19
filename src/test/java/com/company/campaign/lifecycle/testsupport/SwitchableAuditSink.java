package com.company.campaign.lifecycle.testsupport;

import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleAuditSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An audit sink a test can switch off and on again.
 *
 * <p>It exists so a rollback test can let a chain of real commands record their audit events and then fail
 * exactly one: the failing collaborator must leave no partial state, and the same command must still be
 * usable afterwards. It is deliberately not synchronized — the store that owns it serializes every write.
 */
public final class SwitchableAuditSink implements LifecycleAuditSink {

    private final List<LifecycleAuditEvent> events = new ArrayList<>();
    private boolean failing;

    @Override
    public void record(LifecycleAuditEvent event) {
        if (failing) {
            throw new IllegalStateException("audit sink unavailable");
        }
        events.add(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void recordAll(List<LifecycleAuditEvent> batch) {
        Objects.requireNonNull(batch, "batch");
        List<LifecycleAuditEvent> validated = new ArrayList<>(batch.size());
        for (LifecycleAuditEvent event : batch) {
            validated.add(Objects.requireNonNull(event, "event"));
        }
        if (failing) {
            throw new IllegalStateException("audit sink unavailable");
        }
        events.addAll(validated);
    }

    /** Makes every later {@code record} fail, as an unavailable audit collaborator would. */
    public void fail() {
        failing = true;
    }

    /** Restores the sink, so a test can prove the refused command left nothing behind. */
    public void recover() {
        failing = false;
    }

    /** Immutable snapshot of the recorded events, in insertion order. */
    public List<LifecycleAuditEvent> events() {
        return List.copyOf(events);
    }

    public int size() {
        return events.size();
    }
}
