package com.company.campaign.runtime;

import java.util.Objects;

/**
 * The safe outward result of one accepted Runtime delivery.
 *
 * <p>It carries the Inbox receipt the work item is now bound to, the Execution that delivery created or
 * reused, and the closed disposition. The Execution is absent for a registration delivery and for a skipped
 * occurrence, because those produce no Execution at all.
 *
 * <p>It carries no member value, no payload body, no SQL and no trusted security fact, so it is safe to return
 * to a host dispatcher and safe to log.
 */
public record RuntimeDeliveryResult(
        InboxRecord inbox,
        CampaignExecution execution,
        RuntimeDeliveryDisposition disposition
) {

    public RuntimeDeliveryResult {
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(disposition, "disposition");
        boolean expectsExecution = disposition == RuntimeDeliveryDisposition.EXECUTION_CREATED
                || disposition == RuntimeDeliveryDisposition.DUPLICATE && inbox.outcome().hasExecution();
        if (expectsExecution != (execution != null)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "deliveryResult");
        }
    }

    /** True when nothing was written because this exact work item was already accepted. */
    public boolean duplicate() {
        return disposition == RuntimeDeliveryDisposition.DUPLICATE;
    }

    /** True when this delivery created a previous-accepted duplicate. */
    public boolean replayOfExistingWork() {
        return duplicate();
    }

    /** Redacted diagnostics: no tenant, Campaign or source identity is printed. */
    @Override
    public String toString() {
        return "RuntimeDeliveryResult[disposition=" + disposition
                + ", outcome=" + inbox.outcome()
                + ", inboxHash=" + inbox.recordHash()
                + ", execution=" + execution + ']';
    }
}