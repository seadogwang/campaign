package com.company.campaign.builder.compiler;

import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.Objects;

/**
 * The complete, frozen input of one capability-contract invocation.
 *
 * <p>It carries the resolved {@code logicalId} of the component, the already-canonical payload, the frozen
 * catalog and policy, the exact trusted execution context from T02, and — as a host-owned input — the
 * readiness facts the compile was pinned to. A contract therefore has everything it needs to validate and
 * normalize deterministically, and nothing it could use to reach a repository, a network, a provider, a model
 * or a mutable registry.
 *
 * <p>The readiness input is deliberately <em>not</em> part of the payload: a model or client can never declare
 * its own Gate/readiness evidence, and a compile that pins none gets the honest empty default.
 */
public record CapabilityRequest(
        String logicalId,
        CapabilityPayload payload,
        FrozenCapabilityCatalog catalog,
        FrozenAuthoringPolicy policy,
        TrustedExecutionContext context,
        CapabilityCompileContext compileContext,
        ClientRefBindingView bindings) {

    public CapabilityRequest {
        Objects.requireNonNull(logicalId, "logicalId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(compileContext, "compileContext");
        Objects.requireNonNull(bindings, "bindings");
    }

    /**
     * The T19 shape: a request with no pinned readiness evidence and no same-proposal reference view.
     *
     * <p>The empty view is the honest default: a compile that declares no new component resolves no
     * {@code clientRef}, so a reference that names one is left exactly as it was and fails closed downstream.
     */
    public CapabilityRequest(
            String logicalId,
            CapabilityPayload payload,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context) {
        this(logicalId, payload, catalog, policy, context, CapabilityCompileContext.empty(),
                ClientRefBindingView.empty());
    }

    /** A request with pinned readiness evidence but no same-proposal reference view. */
    public CapabilityRequest(
            String logicalId,
            CapabilityPayload payload,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context,
            CapabilityCompileContext compileContext) {
        this(logicalId, payload, catalog, policy, context, compileContext, ClientRefBindingView.empty());
    }
}