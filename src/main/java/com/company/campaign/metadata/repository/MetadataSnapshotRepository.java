package com.company.campaign.metadata.repository;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Optional;

/**
 * Immutable registry of validated Semantic Metadata snapshots.
 *
 * <p>Published instances are deeply immutable, so retaining many historical snapshots costs nothing in
 * correctness: a later publication cannot alter an older snapshot's canonical bytes, hash or definitions.
 * Implementations must reject conflicting reuse of a snapshot ID and may accept an exactly idempotent
 * re-registration of the identical snapshot.
 */
public interface MetadataSnapshotRepository {

    /**
     * Registers a fully validated snapshot.
     *
     * <p>Registering the identical snapshot again is an idempotent no-op. Registering different content
     * under an ID that is already retained fails with
     * {@link MetadataErrorCodes#METADATA_SNAPSHOT_CONFLICT}.
     */
    void publish(MetadataSnapshot snapshot);

    /** Finds a snapshot by its stable ID. */
    Optional<MetadataSnapshot> findById(String snapshotId);

    /** Finds a snapshot only when ID, catalog version and content hash all match the requested pin. */
    Optional<MetadataSnapshot> findByPin(String snapshotId, String snapshotVersion, ContentHash contentHash);

    /** Finds a snapshot by exact content hash. */
    Optional<MetadataSnapshot> findByContentHash(ContentHash contentHash);

    /** Every retained snapshot, ordered by stable snapshot ID; the list itself is immutable. */
    List<MetadataSnapshot> retained();

    /** Same as {@link #findById} but fails with {@link MetadataErrorCodes#METADATA_SNAPSHOT_NOT_FOUND}. */
    default MetadataSnapshot requireById(String snapshotId) {
        return findById(snapshotId).orElseThrow(() -> new MetadataException(
                MetadataErrorCodes.METADATA_SNAPSHOT_NOT_FOUND, "No retained metadata snapshot for the requested id"));
    }
}