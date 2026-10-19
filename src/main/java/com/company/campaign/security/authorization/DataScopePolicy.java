package com.company.campaign.security.authorization;

/**
 * Port that later Metadata, Segment and query implementations use to decide DataScope membership.
 *
 * <p>The policy receives only validated host values. It must return
 * {@link DataScopeOutcome#UNAVAILABLE} rather than a permissive guess when it cannot reach a trustworthy
 * decision; the authorization service fails closed on an unavailable outcome, a null decision or a thrown
 * exception.
 */
@FunctionalInterface
public interface DataScopePolicy {

    DataScopeDecision evaluate(DataScopeQuery query);
}