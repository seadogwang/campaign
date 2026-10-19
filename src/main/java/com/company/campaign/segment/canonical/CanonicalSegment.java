package com.company.campaign.segment.canonical;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.MetadataBinding;

/**
 * The canonical semantic root: one normalized, hashable meaning for one validated Segment DSL document.
 *
 * <p>It carries the versioned canonicalizer discriminator, the DSL schema version it was produced from and
 * the exact Metadata binding the source pinned. Because the binding includes the snapshot content hash, any
 * change to the Metadata semantics a document was compiled against changes this root — so two canonical
 * ASTs can never compare equal across different Metadata content.
 *
 * <p>It deliberately excludes {@code editableDslHash}: that hash stays on the editable document, and the two
 * artifacts are never substituted for one another.
 */
public record CanonicalSegment(
        String canonicalizerVersion,
        SchemaVersion schemaVersion,
        SubjectKind subject,
        MetadataBinding metadata,
        CanonicalNode predicate
) {
    public CanonicalSegment {
        // The version checks are null-hostile, so a null version is a stable coded failure rather than an NPE.
        CanonicalInvariants.requireCanonicalizerVersion(canonicalizerVersion);
        CanonicalInvariants.requireSupportedSchemaVersion(schemaVersion);
        CanonicalInvariants.requirePresent(subject, "subject");
        CanonicalInvariants.requirePresent(metadata, "metadata");
        CanonicalInvariants.requirePresent(predicate, "predicate");
    }
}