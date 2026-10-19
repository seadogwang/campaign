package com.company.campaign.runtime;

import java.util.Optional;

/**
 * The one host-owned port through which Runtime rechecks a Campaign's runtime/schedule activation state.
 *
 * <p>It is a lookup by exact owner rather than an enumeration or a caller-supplied fact: a worker names the
 * tenant and Campaign it persisted its work under, and the host answers with the immutable projection it holds.
 * There is no method that accepts a caller-declared readiness, version, pause flag or security context, and no
 * probe that would let a caller distinguish "this Campaign does not exist" from "you may not see it".
 *
 * <p>A host that cannot answer throws; a host that holds no state for the owner returns empty, and the worker
 * fails closed — neither an absent projection nor an unavailable one may start new work.
 */
public interface RuntimeCampaignStatePort {

    /** The host's current runtime/schedule projection of one exact tenant and Campaign, or empty. */
    Optional<RuntimeCampaignState> findCampaignState(String tenantId, String campaignId);
}