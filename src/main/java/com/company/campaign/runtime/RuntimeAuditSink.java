package com.company.campaign.runtime;

import java.util.List;

/**
 * The host collaborator a runtime transaction records its Outbox/Audit records through.
 *
 * <p>It is a separate collaborator on purpose: a store completes every other fallible pre-commit check, then
 * records one atomic audit batch, then performs only infallible state writes. A failing audit sink therefore
 * leaves no Inbox receipt, Execution, snapshot member, enrollment, Participant or task behind it.
 */
public interface RuntimeAuditSink {

    /** Records exactly one bounded runtime Outbox/Audit record. */
    void record(RuntimeAuditRecord record);

    /**
     * Records one complete transaction's bounded audit batch atomically.
     *
     * <p>A legacy sink that only supports one record fails closed before writing a multi-record batch. An
     * adapter that can provide atomic batching overrides this method and validates the whole batch before
     * appending any record.
     */
    default void recordAll(List<RuntimeAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "audits");
        }
        if (records.size() != 1) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_AUDIT_BATCH_UNSUPPORTED, "audits",
                    "The runtime audit sink cannot atomically record this batch.");
        }
        record(records.get(0));
    }
}
