package com.company.campaign.builder.tool;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.ToolFailure;
import com.company.campaign.contracts.ToolResultEnvelope;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * One admitted Tool call: the model-visible envelope plus the host-private typed artifact of the operation the
 * admitted handler actually executed.
 *
 * <p>The two halves are bound together. The admission records the exact call identifier, Tool identifier and
 * Skill/catalog hash the catalog admitted, and {@link #claim} refuses to hand out the artifact unless the
 * caller asks for exactly that call, Tool and Skill and the artifact still hashes to the public outcome the
 * envelope published. A host-private artifact therefore cannot be swapped across calls, across tools or across
 * runs.
 *
 * <p>Only {@link SegmentToolInvoker} constructs an admission; there is no public factory, so a model payload,
 * a handler or any other caller cannot fabricate one.
 */
public final class SegmentToolAdmission {

    private final String callId;
    private final String toolId;
    private final ContentHash skillHash;
    private final ToolResultEnvelope<SegmentToolOutcome> envelope;
    private final SegmentToolArtifact artifact;

    SegmentToolAdmission(
            String callId,
            String toolId,
            ContentHash skillHash,
            ToolResultEnvelope<SegmentToolOutcome> envelope,
            SegmentToolArtifact artifact) {
        this.callId = callId;
        this.toolId = toolId;
        this.skillHash = skillHash;
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.artifact = artifact;
    }

    /** The bounded, redacted, hash-based result a caller may publish or project. */
    public ToolResultEnvelope<SegmentToolOutcome> envelope() {
        return envelope;
    }

    /** The terminal disposition of the call. */
    public Disposition disposition() {
        return envelope.disposition();
    }

    /** The bounded failure of a non-resolved call, or null for an admitted one. */
    public ToolFailure failure() {
        return envelope.failure();
    }

    /** The bounded public outcome of an admitted call, or null for a refused one. */
    public SegmentToolOutcome result() {
        return envelope.result();
    }

    /** The canonical hash of the exact model-supplied payload the call answered. */
    public ContentHash inputHash() {
        return envelope.inputHash();
    }

    /** The bounded issues of a non-resolved call; empty for an admitted one. */
    public java.util.List<com.company.campaign.contracts.Issue> issues() {
        return envelope.issues();
    }

    /** True only for an admitted call. */
    public boolean resolved() {
        return envelope.disposition().isResolved();
    }

    /** The exact Tool identifier this admission was created for. */
    public String toolId() {
        return toolId;
    }

    /** The exact call identifier this admission was created for. */
    public String callId() {
        return callId;
    }

    /** True when the admitted handler produced a host-private typed artifact for this exact call. */
    public boolean hasArtifact() {
        return artifact != null;
    }

    /**
     * Claims the bound host-private artifact of this call.
     *
     * <p>The claim is refused with one bounded code when the caller asks for a different call, a different Tool
     * or a different Skill/catalog, or when a resolved artifact no longer hashes to the published public
     * outcome. That is what makes a swapped or replayed artifact impossible to consume.
     */
    public SegmentToolArtifact claim(String expectedCallId, String expectedToolId, ContentHash expectedSkillHash) {
        if (expectedCallId == null || !callId.equals(expectedCallId)
                || expectedToolId == null || !toolId.equals(expectedToolId)
                || expectedSkillHash == null || !skillHash.equals(expectedSkillHash)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_ARTIFACT_MISMATCH,
                    "A host-private Tool artifact may only be claimed by its exact admitted call");
        }
        if (artifact == null) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_ARTIFACT_MISSING,
                    "The admitted call published no host-private artifact");
        }
        if (resolved()) {
            ContentHash published = envelope.result().evidenceHash();
            if (!published.equals(artifact.publicEvidenceHash())) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_ARTIFACT_MISMATCH,
                        "A host-private Tool artifact must hash to the public outcome it was admitted with");
            }
        }
        return artifact;
    }
}