package com.company.campaign.builder.authoring;

import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CompileInstants;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.Objects;

/**
 * One complete shared Segment authoring submission: the proof-bound source plus every explicit frozen input.
 *
 * <p>The identity form <em>is</em> the mode, so a request can never contradict itself: a {@code clientRef}
 * target is a CREATE against a brand-new Segment and must carry no trusted Current, while a {@code logicalId}
 * target is an EDIT of the Segment that identity already names and must carry exactly one. There is no
 * delete/recreate path here at all — a replacement of an existing Segment is always an upsert of the same
 * stable identity, and an explicit T15 {@code DELETE} stays the only way to remove one.
 *
 * <p>Everything else is a frozen host input of the compile: the capability catalog, the authoring policy, the
 * compile instants, the accepted T02 trusted context and the host-owned capability compile context that pins
 * the readiness fact and the typed frozen evidence. None of it comes from a model, a client or the source.
 * The request carries no locale or presentation context either, because presentation must never change the
 * canonical Proposal bytes.
 */
public record SegmentAuthoringRequest(
        SegmentAuthoringSource source,
        ProposalIdentity target,
        TrustedCurrentCampaign current,
        FrozenCapabilityCatalog catalog,
        FrozenAuthoringPolicy policy,
        CompileInstants instants,
        TrustedExecutionContext context,
        CapabilityCompileContext capabilityContext) {

    public SegmentAuthoringRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(instants, "instants");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(capabilityContext, "capabilityContext");
        if (target.isLogicalId() != (current != null)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.REQUEST_INVALID,
                    "targetMode",
                    "A client-reference target is a CREATE with no trusted Current, and a logical "
                            + "identifier target is an EDIT of exactly one.");
        }
    }

    /** True when this submission creates a new Segment through a {@code clientRef}. */
    public boolean isCreate() {
        return !target.isLogicalId();
    }

    /** A bounded diagnostic: the source kind, the identity form and no trusted value. */
    @Override
    public String toString() {
        return "SegmentAuthoringRequest[kind=" + source.kind()
                + ", target=" + (target.isLogicalId() ? "logicalId" : "clientRef") + ']';
    }
}