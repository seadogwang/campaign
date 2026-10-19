package com.company.campaign.builder.compiler;

/**
 * One closed, host-owned frozen input bundle a capability contract needs beyond the canonical payload.
 *
 * <p>A capability contract is a frozen artifact of the catalog; its per-compile evidence cannot be baked
 * into it. This port is how a host hands one concrete capability contract the immutable, typed, server-owned
 * facts that a generic {@link CapabilityContract} request deliberately never carries — for example the exact
 * Metadata Snapshot a document claims to bind, or already-approved Preview evidence.
 *
 * <p>An implementation is <em>host</em> input, exactly like {@link CapabilityReadiness}: it is threaded
 * untouched from {@link CompileRequest} through the compiler into {@link CapabilityRequest} and is never
 * derived from a proposal, a model output or a capability payload. The compiler package itself interprets
 * nothing here — it only validates the bounded capability code and orders the bundles deterministically — so
 * builder.compiler keeps its dependency boundary and no concrete capability semantics leak into it.
 *
 * <p>It carries no repository, network, provider, model, clock or mutable-registry access: it is an immutable
 * value, and a contract that consumes it must stay a pure function of its request.
 */
public interface CapabilityInputs {

    /** The one canonical capability code this frozen bundle belongs to. */
    String capabilityCode();
}