package com.company.campaign.lifecycle;

/**
 * Port that rechecks whether a Plan's frozen catalog, policy, capability and contract pins still describe
 * the inputs in force.
 *
 * <p>The boundary receives only validated host values. The port must return
 * {@link CompatibilityOutcome#UNAVAILABLE} rather than a permissive guess when it cannot reach a
 * trustworthy decision; the boundary fails closed on an unavailable outcome, a null decision or a thrown
 * exception, exactly as the accepted T02 authorization core treats a missing DataScope decision.
 */
@FunctionalInterface
public interface FrozenCompatibilityPort {

    CompatibilityDecision recheck(FrozenCompatibilityQuery query);
}