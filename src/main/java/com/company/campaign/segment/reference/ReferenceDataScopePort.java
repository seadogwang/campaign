package com.company.campaign.segment.reference;

import java.util.Optional;

/**
 * Host-only port that computes the trusted effective DataScope of a reference.
 *
 * <p>It answers two questions and computes nothing else:
 *
 * <ol>
 *   <li>what is the immutable effective scope of the caller identified by the trusted context? and</li>
 *   <li>what is the intersection of the caller scope, the parent effective scope and the referenced
 *       version's published scope?</li>
 * </ol>
 *
 * <p>The three-input shape is deliberate. Computing an intersection is not choosing one input and it is not
 * concatenating identifiers: an implementation must compute the members common to all three scopes and
 * describe the result with an immutable identity and content hash of its own, or return empty. Handing back
 * one of the inputs unchanged whenever that happens to be correct is fine — that is what a genuine subset
 * intersection looks like — but the port is never allowed to decide <em>which</em> input to prefer.
 *
 * <p>An empty result means "no members can be in scope" or "no trustworthy decision is available", and both
 * fail closed. The port receives only validated host values, never DSL, canonical or model input.
 */
public interface ReferenceDataScopePort {

    /**
     * The immutable effective scope of the trusted caller, or empty when it cannot be proven.
     *
     * <p>The resolved identity must equal the trusted context's own DataScope identifier; the resolver
     * re-proves this rather than trusting the port blindly.
     */
    Optional<EffectiveDataScope> callerScope(
            String tenantId, String programId, String userId, String dataScopeId);

    /**
     * The trusted intersection of the caller, parent and referenced scopes, or empty when it is empty or
     * cannot be computed.
     *
     * <p>A non-empty result must be genuinely narrower: it must not equal any single input scope.
     */
    Optional<EffectiveDataScope> intersect(
            EffectiveDataScope callerScope, EffectiveDataScope parentScope, EffectiveDataScope referencedScope);
}