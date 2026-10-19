package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.PlanRef;

import java.util.Optional;

/**
 * Server-only port that stores compiler-produced Campaign Plans.
 *
 * <p>{@link #store} accepts only a genuine {@link CampaignPlan}; because that type is compiler-produced and
 * non-forgeable, no client, model or serializer can hand the store a plan of its own making. Storing the
 * same plan again is idempotent — the same identifier and hash succeed and return the same reference —
 * while an identifier already bound to a different plan is a conflict rather than a silent overwrite.
 *
 * <p>{@link #find} is the host-only lookup a later explicit Save/Apply job uses. It is deliberately not
 * reachable from an external caller, and an external caller only ever receives a {@link PlanRef}.
 */
public interface CampaignPlanStore {

    /** Stores a compiler-produced Plan and returns the reference a host may present later. */
    PlanRef store(CampaignPlan plan);

    /** Host-only lookup by plan identifier. */
    Optional<CampaignPlan> find(String planId);
}