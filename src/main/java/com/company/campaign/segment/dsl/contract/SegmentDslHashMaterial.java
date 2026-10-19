package com.company.campaign.segment.dsl.contract;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.predicate.Predicate;

/**
 * The exact material the {@code editableDslHash} covers.
 *
 * <p>It mirrors every editable root field except {@code editableDslHash} itself, which is the only thing
 * excluded. Keeping the material in a separate value is what prevents recursive self-hashing: the hash is
 * computed over this record, never over the document that stores it.
 *
 * <p>Because this type exposes no hash field and no other derived artifact, a later {@code canonicalAstHash}
 * cannot accidentally leak into the editable contract.
 */
public record SegmentDslHashMaterial(
        SchemaVersion schemaVersion,
        SubjectKind subject,
        MetadataBinding metadata,
        Predicate predicate
) {

    public SegmentDslHashMaterial {
        SegmentDslSchemaVersions.requireSupported(schemaVersion);
        if (subject == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SUBJECT_INVALID, "A Segment DSL hash material requires a subject");
        }
        if (metadata == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID,
                    "A Segment DSL hash material requires a Metadata binding");
        }
        if (predicate == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A Segment DSL hash material requires a root predicate");
        }
    }
}