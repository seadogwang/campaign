package com.company.campaign.action;

import com.company.campaign.runtime.ActionHandoff;

import java.util.Objects;

/**
 * The Reward application service: the one host entry point that executes a frozen {@code flow.reward} node's
 * external action.
 *
 * <p>It is a deliberate sibling of {@link MessageActionService} rather than a shared generic entry point, so a
 * caller can never reach a message through a reward-shaped API or the reverse. It owns no state and adds no
 * behaviour beyond the kind check: it refuses any handoff that is not exactly a REWARD node and then delegates
 * to the one {@link ActionApplicationService} that owns the conservative exposure reservation, the Provider
 * grant, the reconciliation and the finalize/release lifecycle.
 */
public final class RewardActionService {

    private final ActionApplicationService service;

    public RewardActionService(ActionApplicationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** Reserves the exact REWARD handoff and its conservative budget exposure, atomically. */
    public ActionPreparation prepare(ActionHandoff handoff) {
        requireReward(handoff);
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

    private static void requireReward(ActionHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        if (ActionKind.from(handoff.actionKind()) != ActionKind.REWARD) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BINDING_MISMATCH, "actionKind");
        }
    }

    /** Redacted diagnostics: the service holds no owner value. */
    @Override
    public String toString() {
        return "RewardActionService[]";
    }
}