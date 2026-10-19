package com.company.campaign.builder.authoring;

import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.compiler.CapabilityInputs;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The host-owned frozen, typed, server-owned evidence the {@code segment.rule-based} contract is pinned to.
 *
 * <p>It is threaded into one compile through {@link CapabilityInputs} and is never read from a proposal, a
 * model output or a capability payload. It carries exactly the facts a capability contract cannot re-derive
 * on its own without reaching a repository, a network or a clock:
 *
 * <ul>
 *   <li>{@link #metadataSnapshot()} — the exact immutable T04 snapshot the document claims to bind. The
 *       contract re-proves every DSL binding pin and the snapshot owner against it, but never loads it;</li>
 *   <li>{@link #previewCard()} — the already-approved safe T09/T11 Preview card. The contract re-proves the
 *       card's DSL, canonical AST, plan, Metadata, Skill and trusted-time pins against the values it
 *       re-derives, but never executes Preview;</li>
 *   <li>{@link #skillHash()} — the frozen Skill content hash the resolved result was produced under;</li>
 *   <li>{@link #referencePins()} — the dedicated Existing Audience Reference readiness pins, present only
 *       when the Segment depends on referenced immutable Audience Versions. They are host input exactly like
 *       the snapshot and the Preview card, and they are restricted to the development-only reference states,
 *       so a reference-bearing compile can never claim production activation through this bundle.</li>
 *   <li>{@link #referenceContext()} — the host-owned trusted reference ports, present exactly when the Segment
 *       depends on referenced immutable Audience Versions and absent for every reference-free Segment. It is
 *       the one way a referenced version can be resolved, so it is never read from a proposal, a model output
 *       or a capability payload, and a reference-free compile never consults it.</li>
 * </ul>
 *
 * <p>A host that pins none of these cannot prove an accepted Segment artifact, so the contract fails closed
 * rather than manufacturing one. The bundle is immutable and its {@code toString} prints only bounded counts
 * and hashes — never a Metadata label, an operand, a member identifier, a tenant/program/user value or any
 * other trusted fact.
 */
public record SegmentAuthoringInputs(
        MetadataSnapshot metadataSnapshot,
        SegmentPreviewCard previewCard,
        ContentHash skillHash,
        SegmentAuthoringReferencePins referencePins,
        SegmentAuthoringReferenceContext referenceContext) implements CapabilityInputs {

    public SegmentAuthoringInputs {
        Objects.requireNonNull(metadataSnapshot, "metadataSnapshot");
        Objects.requireNonNull(previewCard, "previewCard");
        Objects.requireNonNull(skillHash, "skillHash");
        // The reference pins and the host reference context are both optional by design: a reference-free
        // Segment pins neither, and every artifact it produces is then byte-identical to the accepted
        // reference-free artifact. The contract requires the two to agree with the document's own references.
    }

    /**
     * The frozen input bundle of a Segment that pins its reference readiness but no host reference context.
     *
     * <p>A reference-free Segment is exactly this shape, and so is a reference-bearing compile that has not
     * been handed the host ports it would need — the latter fails closed rather than degrading to a
     * reference-free boundary.
     */
    public SegmentAuthoringInputs(
            MetadataSnapshot metadataSnapshot,
            SegmentPreviewCard previewCard,
            ContentHash skillHash,
            SegmentAuthoringReferencePins referencePins) {
        this(metadataSnapshot, previewCard, skillHash, referencePins, null);
    }

    /** The frozen input bundle of a reference-free Segment, which pins no reference readiness at all. */
    public SegmentAuthoringInputs(
            MetadataSnapshot metadataSnapshot, SegmentPreviewCard previewCard, ContentHash skillHash) {
        this(metadataSnapshot, previewCard, skillHash, null, null);
    }

    @Override
    public String capabilityCode() {
        return SegmentAuthoringVersions.CAPABILITY_CODE;
    }

    /** True when this compile was pinned to the dedicated reference readiness of a reference-bearing Segment. */
    public boolean hasReferencePins() {
        return referencePins != null;
    }

    /** True when this compile was handed the host-owned trusted reference ports of a reference-bearing Segment. */
    public boolean hasReferenceContext() {
        return referenceContext != null;
    }

    /** A bounded diagnostic: presence, the already-public Skill hash and a count, never a trusted value. */
    @Override
    public String toString() {
        return "SegmentAuthoringInputs[metadata=present"
                + ", skillHash=" + skillHash
                + ", matchedCount=" + previewCard.matchedCount()
                + ", sample=" + previewCard.hasSample()
                + ", referencePins=" + (referencePins == null ? "none" : referencePins.readinessState())
                + ", referenceContext=" + (referenceContext == null ? "none" : "present")
                + ']';
    }
}