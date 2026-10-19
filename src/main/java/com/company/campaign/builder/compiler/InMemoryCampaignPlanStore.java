package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.PlanRef;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable-plan, in-memory {@link CampaignPlanStore} for tests and local use.
 *
 * <p>It holds plans by identifier for the lifetime of the instance and never mutates a stored plan. A
 * repeated store of the same plan is idempotent; a reused identifier with different plan material is a
 * conflict. This is a test/local implementation only: T15 deliberately does not implement persistence,
 * Apply, Save or Publish.
 */
public final class InMemoryCampaignPlanStore implements CampaignPlanStore {

    private final Map<String, CampaignPlan> plans = new LinkedHashMap<>();

    @Override
    public synchronized PlanRef store(CampaignPlan plan) {
        if (plan == null) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID,
                    "plan",
                    "A Plan store accepts only a compiler-produced plan.");
        }
        CampaignPlan existing = plans.get(plan.planId());
        if (existing != null) {
            requireCompatible(existing, plan);
            return existing.planRef();
        }
        plans.put(plan.planId(), plan);
        return plan.planRef();
    }

    @Override
    public synchronized Optional<CampaignPlan> find(String planId) {
        Objects.requireNonNull(planId, "planId");
        return Optional.ofNullable(plans.get(planId));
    }

    /** The number of distinct plans currently stored. */
    public synchronized int size() {
        return plans.size();
    }

    /**
     * The store's conflict rule: an identifier may only ever name one plan.
     *
     * <p>Package-private so the rule itself is directly testable against the production code path rather
     * than against a stand-in.
     */
    static void requireCompatible(CampaignPlan existing, CampaignPlan incoming) {
        if (!existing.equals(incoming)) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_CONFLICT,
                    "planIdReuse",
                    "A Plan identifier is already bound to a different plan.");
        }
    }
}