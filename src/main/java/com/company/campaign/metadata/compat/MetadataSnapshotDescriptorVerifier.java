package com.company.campaign.metadata.compat;

import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.domain.MetadataSnapshot;

import java.util.Objects;

/**
 * Verifies a T03 {@link MetadataSnapshotDescriptor} against a retained T04 {@link MetadataSnapshot}.
 *
 * <p>T03 keeps the descriptor as its reference/manifest contract for an opaque metadata payload; T04 owns
 * the payload and its semantics. This adapter is the only place the two meet, and it is directional: T03
 * does not import any T04 implementation class, and T04 only reads the descriptor's identity fields. The
 * check confirms the descriptor's snapshot ID, catalog version and content hash equal the snapshot's
 * exact values, so a manifest cannot point at drifted or re-published metadata.
 */
public final class MetadataSnapshotDescriptorVerifier {

    private MetadataSnapshotDescriptorVerifier() {
    }

    /** True when the descriptor and snapshot agree on ID, catalog version and content hash. */
    public static boolean matches(MetadataSnapshotDescriptor descriptor, MetadataSnapshot snapshot) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(snapshot, "snapshot");
        return descriptor.snapshotId().equals(snapshot.snapshotId())
                && descriptor.snapshotVersion().equals(snapshot.snapshotVersion())
                && descriptor.contentHash().equals(snapshot.contentHash());
    }

    /**
     * Fails with {@link MetadataErrorCodes#METADATA_DESCRIPTOR_MISMATCH} when the descriptor and snapshot
     * do not agree. The failure carries no identifier or hash value, so it cannot be used to probe foreign
     * metadata identity.
     */
    public static void verify(MetadataSnapshotDescriptor descriptor, MetadataSnapshot snapshot) {
        if (!matches(descriptor, snapshot)) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_DESCRIPTOR_MISMATCH,
                    "Metadata descriptor does not match the retained snapshot identity");
        }
    }
}