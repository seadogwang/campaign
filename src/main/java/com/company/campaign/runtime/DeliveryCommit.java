package com.company.campaign.runtime;

import java.util.Objects;

/**
 * One transactional delivery unit: an Inbox receipt, the Execution it may create, and its audit record.
 *
 * <p>All three commit together or none of them does. The Execution is absent for a schedule-registration
 * delivery and for a {@code SKIPPED} occurrence, because those produce a receipt and nothing else; it is
 * present exactly once for a {@code DUE} occurrence, because one occurrence produces exactly one Execution per
 * {@code (tenantId, executionKey)}.
 */
public record DeliveryCommit(
        InboxRecord inbox,
        CampaignExecution execution,
        RuntimeAuditRecord audit
) {

    public DeliveryCommit {
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(audit, "audit");
        if (execution != null
                && (!execution.tenantId().equals(inbox.tenantId())
                || !execution.executionKey().equals(inbox.executionKey()))) {
            // An Execution committed with a receipt must witness exactly the receipt's own tenant and key.
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "deliveryBinding");
        }
        if (inbox.outcome().hasExecution() != (execution != null)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "deliveryOutcome");
        }
        if (!audit.tenantId().equals(inbox.tenantId())) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "auditTenant");
        }
    }
}