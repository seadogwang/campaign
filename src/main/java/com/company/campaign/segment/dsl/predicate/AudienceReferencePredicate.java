package com.company.campaign.segment.dsl.predicate;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.canonical.ContentHashException;

/**
 * A reference to one exact, immutable Audience Version.
 *
 * <p>It carries three pinned parts and nothing else: the audience identity, the exact immutable version
 * identity and the exact content hash of that version. There is deliberately no name, query, SQL text,
 * tenant, program, DataScope, permission, canonical byte or dependency field, so a reference can never
 * describe <em>why</em> a member belongs — only <em>which immutable version</em> decides it.
 *
 * <p>A mutable pointer is unrepresentable: neither {@code current} nor {@code latest} exists as a field, and
 * the version identity is mandatory, so "the newest audience" cannot be expressed at all. Every trusted fact
 * — owner, effective DataScope, availability, dependencies — is resolved server-side by T07 from the
 * host-owned version catalog and is never read from this node.
 *
 * <p>{@code nodeId} stays a stable edit identity so a T12 refinement can add, remove, move or replace the
 * reference by identity rather than by array position.
 */
public record AudienceReferencePredicate(
        String nodeId,
        String audienceId,
        String versionId,
        ContentHash versionHash
) implements Predicate {

    public AudienceReferencePredicate {
        nodeId = PredicateNodeIds.requireNodeId(nodeId);
        audienceId = PredicateNodeIds.requireReferenceId(audienceId, "audienceId");
        versionId = PredicateNodeIds.requireReferenceId(versionId, "versionId");
        if (versionHash == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.AUDIENCE_REFERENCE_INVALID,
                    "An audience reference requires the exact content hash of the referenced version");
        }
    }

    /** Builds a node from wire-encoded pin text, mapping a malformed hash onto the stable reference code. */
    public static AudienceReferencePredicate of(
            String nodeId, String audienceId, String versionId, String versionHash) {
        ContentHash hash;
        try {
            hash = new ContentHash(versionHash);
        } catch (ContentHashException exception) {
            // The hash failure carries the rejected text, so it is never attached as a cause.
            throw new SegmentDslException(
                    SegmentDslErrorCodes.AUDIENCE_REFERENCE_INVALID,
                    "The referenced version hash is malformed");
        }
        return new AudienceReferencePredicate(nodeId, audienceId, versionId, hash);
    }

    @Override
    public PredicateType type() {
        return PredicateType.AUDIENCE_REFERENCE;
    }
}