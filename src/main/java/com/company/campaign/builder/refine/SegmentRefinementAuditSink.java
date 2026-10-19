package com.company.campaign.builder.refine;

/**
 * The narrow optional audit sink of the refinement orchestrator.
 *
 * <p>The orchestrator performs no persistence: a host may inject one sink to receive the immutable audit
 * projection. If a sink is present and refuses or fails, the run follows one explicit fail-closed policy — it
 * returns {@code INVALID} with
 * {@link SegmentRefinementAdapterErrorCodes#AUDIT_UNAVAILABLE} and no Preview card, explanation or success
 * artifact — rather than silently dropping required security evidence, and it never attempts a second
 * recursive record.
 */
@FunctionalInterface
public interface SegmentRefinementAuditSink {

    /** Records one immutable audit projection. */
    void record(SegmentRefinementAuditRecord record);
}