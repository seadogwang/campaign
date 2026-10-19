package com.company.campaign.builder.agent;

/**
 * The narrow optional audit sink of the Agent adapter.
 *
 * <p>The adapter performs no persistence: a host may inject one sink to receive the immutable audit projection.
 * If a sink is present and refuses or fails, the run follows one explicit fail-closed policy — it returns
 * {@code INVALID} with {@link SegmentAgentErrorCodes#AUDIT_UNAVAILABLE} and no Preview card, explanation or
 * success artifact — rather than silently dropping required security evidence.
 */
@FunctionalInterface
public interface SegmentAuditSink {

    /** Records one immutable audit projection. */
    void record(SegmentAuditRecord record);
}