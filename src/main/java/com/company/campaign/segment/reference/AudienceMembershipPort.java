package com.company.campaign.segment.reference;

import java.util.Optional;

/**
 * Host-only port that returns the exact member set of one immutable Audience Version at one effective scope.
 *
 * <p>It exists for local and fixture execution only. A real deployment lowers a reference to a parameterized
 * membership lookup against server-owned storage; this port is the deterministic substitute used by tests and
 * by an explicitly local preview, and it is never a way for a caller to read raw members from a request.
 *
 * <p>An empty result means the exact version's membership is not available locally, which fails closed rather
 * than degrading to "no members" — an empty set and an unknown set are different facts and must never be
 * conflated, because treating an unknown set as empty would silently return the wrong answer.
 */
@FunctionalInterface
public interface AudienceMembershipPort {

    /** The exact membership of one version at one effective scope, or empty when it is unavailable. */
    Optional<AudienceMembershipSet> lookup(AudienceVersionRef reference, EffectiveDataScope effectiveDataScope);
}