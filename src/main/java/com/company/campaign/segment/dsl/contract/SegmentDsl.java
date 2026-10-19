package com.company.campaign.segment.dsl.contract;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.codec.SegmentDslHasher;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateNodeIds;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * An immutable, hash-verified, versioned editable Segment DSL document.
 *
 * <p>The root pins the exact Segment DSL schema version, the root subject and the complete Metadata Snapshot
 * binding. It is an <em>editable</em> document: it keeps stable node IDs and the exact declared child order,
 * lexical decimal scale and window structure so a user edit round-trips unchanged. Producing the separate
 * {@code canonicalAstHash} from normalized semantics is a T07 obligation, and no such field exists here.
 *
 * <p>Construction verifies the declared {@code editableDslHash}; {@link #publish} is the factory that
 * computes it. Subject/predicate semantic compatibility is deferred to T07; T05 requires only that the
 * subject is present and the predicate tree is structurally valid with unique node IDs.
 */
public record SegmentDsl(
        SchemaVersion schemaVersion,
        SubjectKind subject,
        MetadataBinding metadata,
        Predicate predicate,
        ContentHash editableDslHash
) {

    public SegmentDsl {
        SegmentDslSchemaVersions.requireSupported(schemaVersion);
        if (subject == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SUBJECT_INVALID, "A Segment DSL document requires a subject");
        }
        if (metadata == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID, "A Segment DSL document requires a Metadata binding");
        }
        if (predicate == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A Segment DSL document requires a root predicate");
        }
        // Whole-tree pass: duplicate node IDs anywhere, including across branches and inside EXISTS, fail.
        PredicateNodeIds.requireUnique(predicate);

        ContentHash expected = SegmentDslHasher.hash(new SegmentDslHashMaterial(schemaVersion, subject, metadata, predicate));
        if (editableDslHash == null || !editableDslHash.equals(expected)) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.EDITABLE_HASH_MISMATCH,
                    "Declared editableDslHash is not the canonical hash of the editable document");
        }
    }

    /** Publishes a document: computes the editable hash from explicit material, then validates and freezes it. */
    public static SegmentDsl publish(
            SchemaVersion schemaVersion,
            SubjectKind subject,
            MetadataBinding metadata,
            Predicate predicate) {
        SegmentDslSchemaVersions.requireSupported(schemaVersion);
        SegmentDslHashMaterial material = new SegmentDslHashMaterial(schemaVersion, subject, metadata, predicate);
        PredicateNodeIds.requireUnique(predicate);
        ContentHash hash = SegmentDslHasher.hash(material);
        return new SegmentDsl(schemaVersion, subject, metadata, predicate, hash);
    }

    /** The exact material this document's {@link #editableDslHash()} covers. */
    public SegmentDslHashMaterial hashMaterial() {
        return new SegmentDslHashMaterial(schemaVersion, subject, metadata, predicate);
    }
}