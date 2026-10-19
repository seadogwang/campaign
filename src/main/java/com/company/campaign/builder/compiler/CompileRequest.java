package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.Objects;

/**
 * One complete compile: the strict proposal plus every explicit frozen input.
 *
 * <p>The compiler reads nothing else. The trusted Current campaign is present for an EDIT and absent for a
 * CREATE; the catalog, policy and instants are frozen inputs; the execution context is the accepted T02
 * trusted context; and the optional T19 pack index is the frozen evidence a Journey-topology replacement is
 * validated against. No mutable registry, system clock, random source, default locale or ambient thread-bound
 * value is ever consulted.
 *
 * <p>The pack index is deliberately optional and is a <em>typed compile input</em> rather than a live
 * registry: a compile that replaces a Journey topology must be handed one and fails closed without it, whereas
 * a compile that says nothing about a topology needs none and keeps the accepted T15 behaviour exactly.
 */
public record CompileRequest(
        CampaignProposalPatch patch,
        TrustedCurrentCampaign current,
        FrozenCapabilityCatalog catalog,
        FrozenAuthoringPolicy policy,
        CompileInstants instants,
        TrustedExecutionContext context,
        CapabilityCompileContext capabilityContext,
        T19PackIndex journeyPackIndex) {

    public CompileRequest {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(instants, "instants");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(capabilityContext, "capabilityContext");
    }

    /** The T15 shape: a compile pinned to no capability readiness evidence and no Journey pack index. */
    public CompileRequest(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context) {
        this(patch, current, catalog, policy, instants, context, CapabilityCompileContext.empty(), null);
    }

    /** A compile with pinned readiness evidence but no Journey pack index. */
    public CompileRequest(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context,
            CapabilityCompileContext capabilityContext) {
        this(patch, current, catalog, policy, instants, context, capabilityContext, null);
    }

    /** True when this compile was handed the frozen pack index a topology replacement is decided against. */
    public boolean hasJourneyPackIndex() {
        return journeyPackIndex != null;
    }

    /** A CREATE compile: no trusted Current campaign exists. */
    public static CompileRequest create(
            CampaignProposalPatch patch,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context) {
        return new CompileRequest(patch, null, catalog, policy, instants, context);
    }

    /** A CREATE compile pinned to explicit host-owned readiness facts. */
    public static CompileRequest create(
            CampaignProposalPatch patch,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context,
            CapabilityCompileContext capabilityContext) {
        return new CompileRequest(patch, null, catalog, policy, instants, context, capabilityContext);
    }

    /** A CREATE compile pinned to an explicit frozen Journey pack index. */
    public static CompileRequest create(
            CampaignProposalPatch patch,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context,
            CapabilityCompileContext capabilityContext,
            T19PackIndex journeyPackIndex) {
        return new CompileRequest(
                patch, null, catalog, policy, instants, context, capabilityContext, journeyPackIndex);
    }

    /** An EDIT compile against one trusted Current campaign. */
    public static CompileRequest edit(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context) {
        return new CompileRequest(patch, Objects.requireNonNull(current, "current"), catalog, policy, instants,
                context);
    }

    /** An EDIT compile pinned to explicit host-owned readiness facts. */
    public static CompileRequest edit(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context,
            CapabilityCompileContext capabilityContext) {
        return new CompileRequest(patch, Objects.requireNonNull(current, "current"), catalog, policy, instants,
                context, capabilityContext);
    }

    /** An EDIT compile pinned to an explicit frozen Journey pack index. */
    public static CompileRequest edit(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            CompileInstants instants,
            TrustedExecutionContext context,
            CapabilityCompileContext capabilityContext,
            T19PackIndex journeyPackIndex) {
        return new CompileRequest(patch, Objects.requireNonNull(current, "current"), catalog, policy, instants,
                context, capabilityContext, journeyPackIndex);
    }
}