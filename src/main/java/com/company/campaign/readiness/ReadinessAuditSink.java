package com.company.campaign.readiness;

import java.util.List;

/**
 * The port a readiness transaction emits its immutable audit data through.
 *
 * <p>It mirrors the accepted security, lifecycle and runtime audit sinks: a batch larger than one is refused
 * before any element is written, so a non-atomic sink cannot leave a partial trail behind a rolled-back
 * transaction.
 */
@FunctionalInterface
public interface ReadinessAuditSink {

    /** Records one audit event. */
    void record(ReadinessAuditEvent event);

    /**
     * Records a batch of audit events atomically.
     *
     * <p>The default refuses any batch larger than one: a sink that cannot write all of them or none of them
     * must not pretend it did.
     */
    default void recordAll(List<ReadinessAuditEvent> events) {
        if (events == null || events.size() != 1) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_AUDIT_INVALID,
                    "A non-atomic readiness audit sink accepts exactly one event per call.");
        }
        record(events.get(0));
    }
}