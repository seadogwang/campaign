package com.company.campaign.metadata.repository;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory immutable snapshot registry.
 *
 * <p>This is sufficient for T04: it has no database, cache, clock or random identifier, and because the
 * stored snapshots are immutable it retains historical snapshots without any copy-on-write risk. A
 * snapshot that is already retained is returned as the same instance, so a caller can never observe a
 * mutated index.
 */
public final class InMemoryMetadataSnapshotRepository implements MetadataSnapshotRepository {

    private final ConcurrentMap<String, MetadataSnapshot> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<ContentHash, MetadataSnapshot> byHash = new ConcurrentHashMap<>();

    @Override
    public void publish(MetadataSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        MetadataSnapshot existing = byId.putIfAbsent(snapshot.snapshotId(), snapshot);
        if (existing == null) {
            byHash.putIfAbsent(snapshot.contentHash(), snapshot);
            return;
        }
        if (existing.equals(snapshot)) {
            return;
        }
        throw new MetadataException(
                MetadataErrorCodes.METADATA_SNAPSHOT_CONFLICT,
                "A snapshot id is already retained with different content");
    }

    @Override
    public Optional<MetadataSnapshot> findById(String snapshotId) {
        if (snapshotId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(snapshotId));
    }

    @Override
    public Optional<MetadataSnapshot> findByPin(
            String snapshotId, String snapshotVersion, ContentHash contentHash) {
        if (snapshotVersion == null || contentHash == null) {
            return Optional.empty();
        }
        return findById(snapshotId)
                .filter(snapshot -> snapshot.snapshotVersion().equals(snapshotVersion))
                .filter(snapshot -> snapshot.contentHash().equals(contentHash));
    }

    @Override
    public Optional<MetadataSnapshot> findByContentHash(ContentHash contentHash) {
        if (contentHash == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHash.get(contentHash));
    }

    @Override
    public List<MetadataSnapshot> retained() {
        List<MetadataSnapshot> snapshots = new ArrayList<>(byId.values());
        snapshots.sort(Comparator.comparing(MetadataSnapshot::snapshotId));
        return List.copyOf(snapshots);
    }
}