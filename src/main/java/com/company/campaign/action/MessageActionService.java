package com.company.campaign.action;

import com.company.campaign.runtime.ActionHandoff;

import java.util.Objects;

/**
 * The Message application service: the one host entry point that executes a frozen {@code flow.message} node's
 * external action.
 *
 * <p>It is a deliberate sibling of {@link RewardActionService} rather than a shared generic entry point, so a
 * caller can never reach a reward through a message-shaped API or the reverse. It owns no state and adds no
 * behaviour: it refuses any handoff that is not exactly a MESSAGE node and then delegates to the one
 * {@link ActionApplicationService} that owns the reservation, submission, reconciliation and budget lifecycle.
 */
public final class MessageActionService {

    private final ActionApplicationService service;

    public MessageActionService(ActionApplicationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** Reserves the exact MESSAGE handoff, atomically, and returns the original action on a duplicate key. */
    public ActionPreparation prepare(ActionHandoff handoff) {
        requireMessage(handoff);
        return service.prepare(handoff);
    }

    /** Claims the next due MESSAGE or reward action task; the queue is shared, the decisions are not. */
    public ActionClaimResult claimNext(String tenantId, String workerId) {
        return service.claimNext(tenantId, workerId);
    }

    /** Processes one already-leased action task under this service's exact semantics. */
    public ActionOutcome process(ActionTask task) {
        return service.process(task);
    }

    /** The underlying service, so a host can wire one worker over both facades. */
    public ActionApplicationService applicationService() {
        return service;
    }

    private static void requireMessage(ActionHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        if (ActionKind.from(handoff.actionKind()) != ActionKind.MESSAGE) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "actionKind");
        }
    }

    /** Redacted diagnostics: the service holds no owner value. */
    @Override
    public String toString() {
        return "MessageActionService[]";
    }
}