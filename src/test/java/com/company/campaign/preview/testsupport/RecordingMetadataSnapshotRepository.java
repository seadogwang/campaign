package com.company.campaign.preview.testsupport;

import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test-only Metadata registry that delegates to a real repository and records every lookup.
 *
 * <p>It exists so a test can prove the Preview service performs exactly one exact-pin lookup with the complete
 * DSL binding and never falls back to an identifier-only, latest or content-only lookup. It adds no cache,
 * default or behaviour of its own: every answer comes from the wrapped repository.
 */
public final class RecordingMetadataSnapshotRepository implements MetadataSnapshotRepository {

    /** One recorded exact-pin lookup: the complete immutable binding the service requested. */
    public record Pin(String snapshotId, String snapshotVersion, ContentHash contentHash) {
    }

    private final MetadataSnapshotRepository delegate;
    private final List<Pin> findByPinCalls = new ArrayList<>();
    private final AtomicInteger findByIdCalls = new AtomicInteger();
    private final AtomicInteger findByContentHashCalls = new AtomicInteger();
    private final AtomicInteger retainedCalls = new AtomicInteger();

    public RecordingMetadataSnapshotRepository(MetadataSnapshotRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(MetadataSnapshot snapshot) {
        delegate.publish(snapshot);
    }

    @Override
    public Optional<MetadataSnapshot> findById(String snapshotId) {
        findByIdCalls.incrementAndGet();
        return delegate.findById(snapshotId);
    }

    @Override
    public Optional<MetadataSnapshot> findByPin(
            String snapshotId, String snapshotVersion, ContentHash contentHash) {
        findByPinCalls.add(new Pin(snapshotId, snapshotVersion, contentHash));
        return delegate.findByPin(snapshotId, snapshotVersion, contentHash);
    }

    @Override
    public Optional<MetadataSnapshot> findByContentHash(ContentHash contentHash) {
        findByContentHashCalls.incrementAndGet();
        return delegate.findByContentHash(contentHash);
    }

    @Override
    public List<MetadataSnapshot> retained() {
        retainedCalls.incrementAndGet();
        return delegate.retained();
    }

    /** Every exact-pin lookup the service requested, in order. */
    public List<Pin> findByPinCalls() {
        return List.copyOf(findByPinCalls);
    }

    /** How many identifier-only lookups reached this registry. */
    public int findByIdCallCount() {
        return findByIdCalls.get();
    }

    /** How many content-only lookups reached this registry. */
    public int findByContentHashCallCount() {
        return findByContentHashCalls.get();
    }

    /** How many latest-style enumerations reached this registry. */
    public int retainedCallCount() {
        return retainedCalls.get();
    }
}