package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;

import java.util.Optional;

/**
 * Host-only port that re-resolves the dedicated reference facts of one compiled Plan.
 *
 * <p>It is the reference counterpart of the accepted {@code FrozenCompatibilityPort}: a production path asks
 * the host to re-resolve what a Plan depends on rather than reading a caller's, a model's or a Tool's claim
 * about it. The host owns the version catalog, the program relation policy, the DataScope intersection and the
 * retention and availability answers, so it is the only thing that can honestly answer.
 *
 * <p>There is no permissive default. A host that cannot resolve the Plan returns {@link Optional#empty()},
 * which refuses the preparation; a thrown failure is treated exactly like malformed evidence. Neither may ever
 * be read as "no reference dependencies", because a Plan whose own trusted evidence carries a reference is
 * never reference-free.
 *
 * <p>The port is consulted only for a reference-bearing Plan: a Plan whose trusted reference evidence is
 * absent supplies no reference facts and never asks this port at all, so a reference-free preparation keeps
 * the accepted behavior and bytes.
 */
@FunctionalInterface
public interface ReferencePreparationLookupPort {

    /** The exact host reference facts of one reference-bearing Plan, or empty when the host holds none. */
    Optional<ReferencePreparationFacts> factsFor(CampaignPlan plan);
}