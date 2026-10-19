package com.company.campaign.builder.compiler;

/**
 * The narrow, versioned port a frozen capability pack implements.
 *
 * <p>A contract declares its canonical capability code, its exact contract version, the composition slot it
 * occupies (including whether that slot is ordered and whether it is a singleton), frozen preview/runtime
 * compatibility facts, and one deterministic resolution step. Resolution receives an immutable canonical
 * payload plus explicit frozen catalog, policy and trusted-context facts and returns either one immutable
 * normalized component or an ordered bounded issue list.
 *
 * <p>A contract has no repository, network, provider, model, clock, random or mutation access. It must be a
 * pure function of its request: resolving the same request twice must produce the same payload bytes and the
 * same resolved references.
 *
 * <p>Concrete capability semantics — the rule-based Segment capability and every later capability pack —
 * are deliberately not implemented by T15. Production supplies this interface, its value objects and the
 * generic compiler only; tests supply fixtures.
 */
public interface CapabilityContract {

    /** The canonical capability code, for example a versioned message-template capability. */
    String capabilityCode();

    /** The exact contract version this implementation declares; a catalog pin is meaningless without it. */
    String contractVersion();

    /** The composition slot this contract version occupies, including its ordered/singleton facts. */
    CompositionSlot slot();

    /** True when the frozen catalog has this capability enabled for authoring. */
    boolean enabled();

    /** Frozen preview/runtime compatibility facts; never executed by T15. */
    RuntimeCompatibility runtimeCompatibility();

    /**
     * Deterministically validates and normalizes one payload, resolving allowed catalog references to stable
     * {@code logicalId}/{@code versionId} pairs.
     */
    CapabilityResolution resolve(CapabilityRequest request);
}