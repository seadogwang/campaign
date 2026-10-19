package com.company.campaign.segment.dsl.contract;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The immutable Metadata Snapshot binding pinned into every editable Segment DSL document.
 *
 * <p>It records the exact snapshot ID, catalog version, Metadata schema version and content hash, so a replay
 * never resolves a drifting "current" snapshot. All four pins participate in the editable hash. This type
 * reads a T04 {@link MetadataSnapshot} and verifies against one, but T04 never depends on this type.
 */
public record MetadataBinding(
        String snapshotId,
        String snapshotVersion,
        SchemaVersion metadataSchemaVersion,
        ContentHash contentHash
) {

    public MetadataBinding {
        snapshotId = requireId(snapshotId, "metadataSnapshotId");
        snapshotVersion = requireId(snapshotVersion, "metadataSnapshotVersion");
        if (!MetadataSchemaVersions.SUPPORTED.equals(metadataSchemaVersion)) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID,
                    "Unsupported Metadata schema version in the snapshot binding");
        }
        if (contentHash == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID, "A snapshot binding requires a content hash");
        }
    }

    /** Derives the binding that pins a retained T04 snapshot. */
    public static MetadataBinding fromSnapshot(MetadataSnapshot snapshot) {
        if (snapshot == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID, "A snapshot binding requires a snapshot");
        }
        return new MetadataBinding(
                snapshot.snapshotId(),
                snapshot.snapshotVersion(),
                snapshot.schemaVersion(),
                snapshot.contentHash());
    }

    private static String requireId(String value, String field) {
        try {
            return SegmentDslReferences.requireReferenceId(value, field);
        } catch (SegmentDslException exception) {
            // A binding identifier is part of the binding contract, so its failure is reported as such.
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID, "Invalid identifier in the snapshot binding");
        }
    }

    /**
     * Verifies that this binding pins the supplied T04 snapshot on every field.
     *
     * <p>A mismatch on any one of ID, catalog version, schema version or content hash fails closed; a document
     * must never be resolved against a snapshot it did not name.
     */
    public void verify(MetadataSnapshot snapshot) {
        if (snapshot == null
                || !Objects.equals(snapshotId, snapshot.snapshotId())
                || !Objects.equals(snapshotVersion, snapshot.snapshotVersion())
                || !Objects.equals(metadataSchemaVersion, snapshot.schemaVersion())
                || !Objects.equals(contentHash, snapshot.contentHash())) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID,
                    "Segment DSL Metadata binding does not match the supplied Metadata Snapshot");
        }
    }
}