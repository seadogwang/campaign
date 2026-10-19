package com.company.campaign.builder.authoring;

import com.company.campaign.segment.reference.ReferenceExecutionContext;
import com.company.campaign.segment.reference.ReferenceValidationContext;

import java.util.Objects;

/**
 * The host-owned trusted reference ports one reference-bearing Segment authoring compile resolves against.
 *
 * <p>It is the reference counterpart of {@link SegmentAuthoringInputs}: exactly like the Metadata Snapshot and
 * the already-approved Preview card, the ports that can resolve an Existing Audience Reference version are
 * <em>host</em> input. They are threaded through the compile context, are never read from a proposal, a model
 * output or a capability payload, and a client or a model that smuggles one in under a host-owned name is
 * refused by the closed envelope before any payload exists.
 *
 * <p>It carries the one accepted {@link ReferenceExecutionContext} — the host version catalog, the program
 * relation policy, the DataScope intersection port, the availability and retention ports and the bounded
 * reference policy — and it derives both trusted reference contexts the accepted boundaries require:
 *
 * <ul>
 *   <li>the validation context of the five-argument T07 entry point, whose candidate root version is
 *       deliberately <em>null</em>: the compile re-derives an already-resolved document rather than editing a
 *       version identity into existence;</li>
 *   <li>the execution context of the four-argument T08 entry point, which re-observes availability and
 *       retention because a resolution is not permanent authority.</li>
 * </ul>
 *
 * <p>Nothing here is a second traversal: both contexts are projected from the one host context, so validation
 * and planning cannot disagree about the catalog, the program relation, the DataScope intersection or the
 * policy bounds.
 *
 * <p>The type is opaque on purpose. It exposes no catalog, no port and no policy to a caller, and its
 * diagnostic states presence only — never a port, a catalog, a referenced audience or a version identity.
 */
public final class SegmentAuthoringReferenceContext {

    private final ReferenceExecutionContext executionContext;

    private SegmentAuthoringReferenceContext(ReferenceExecutionContext executionContext) {
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
    }

    /**
     * Wraps the one host-owned reference execution context a reference-bearing compile resolves against.
     *
     * @param executionContext the host reference ports; never reachable from a payload, a model or a client
     */
    public static SegmentAuthoringReferenceContext of(ReferenceExecutionContext executionContext) {
        return new SegmentAuthoringReferenceContext(executionContext);
    }

    /**
     * The trusted reference context of the accepted five-argument validation entry point.
     *
     * <p>The candidate root version is absent because this compile re-proves an already-resolved document: the
     * host, not the authoring path, decides which version identity a document under edit will become.
     */
    ReferenceValidationContext validationContext() {
        return executionContext.asValidationContext(null);
    }

    /** The trusted reference ports of the accepted four-argument planning entry point. */
    ReferenceExecutionContext executionContext() {
        return executionContext;
    }

    /** A bounded diagnostic: presence only, never a port, a catalog, a referenced audience or a version. */
    @Override
    public String toString() {
        return "SegmentAuthoringReferenceContext[referencePorts=present]";
    }
}