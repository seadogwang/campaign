package com.company.campaign.segment.reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A synchronized, in-memory {@link AudienceRetentionPort} for tests and explicitly local use only.
 *
 * <p>It enforces the one retention invariant the contract cares about: a referenced immutable version is
 * preserved for exactly as long as a published dependent version holds it. A hard deletion with any active
 * hold is refused and changes nothing, so "a dependent still needs this" can never be raced into "the bytes
 * are gone".
 *
 * <p>Releasing a hold never deletes anything by itself. It removes that one hold and, when it was the last
 * one, makes the version eligible for an <em>explicit</em> hard deletion according to the configured
 * {@link RetentionReleasePolicy}; under {@link RetentionReleasePolicy#RETAIN_INDEFINITELY} even an unheld
 * version stays delete-free.
 *
 * <p>Nothing here is reachable from a model, a Tool or a client payload: the only callers are a host
 * publication or release transaction, and there is deliberately no method that retains, releases or deletes
 * "whatever the request named".
 *
 * <p>Each operation is atomic with respect to the others, so acquiring a hold and deleting the same version
 * can never both appear to succeed.
 */
public final class InMemoryAudienceRetentionStore implements AudienceRetentionPort {

    private final Map<String, AudienceVersionRef> retainedByKey = new LinkedHashMap<>();
    private final Map<String, AudienceRetentionHold> holdsById = new LinkedHashMap<>();
    private final Set<String> deletedKeys = new TreeSet<>();
    private final RetentionReleasePolicy releasePolicy;

    public InMemoryAudienceRetentionStore(RetentionReleasePolicy releasePolicy) {
        if (releasePolicy == null) {
            throw ReferenceException.invalid("A retention store requires its release policy");
        }
        this.releasePolicy = releasePolicy;
    }

    /** A store that retains referenced versions indefinitely once the last hold is released. */
    public static InMemoryAudienceRetentionStore retaining() {
        return new InMemoryAudienceRetentionStore(RetentionReleasePolicy.RETAIN_INDEFINITELY);
    }

    /** A store that makes a version deletable once its last hold is released; deletion stays explicit. */
    public static InMemoryAudienceRetentionStore deletableWhenUnheld() {
        return new InMemoryAudienceRetentionStore(RetentionReleasePolicy.ALLOW_DELETION_WHEN_UNHELD);
    }

    /** Registers one version as retained material, which is what makes it known to this store. */
    public synchronized void retain(AudienceVersionRef reference) {
        if (reference == null) {
            throw ReferenceException.invalid("A retention registration requires an exact version pin");
        }
        retainedByKey.putIfAbsent(reference.identityKey(), reference);
    }

    /** True when this store still holds the exact version's immutable bytes. */
    public synchronized boolean isRetained(AudienceVersionRef reference) {
        return reference != null
                && retainedByKey.containsKey(reference.identityKey())
                && !deletedKeys.contains(reference.identityKey());
    }

    /** True when the exact version was hard-deleted through this store. */
    public synchronized boolean isDeleted(AudienceVersionRef reference) {
        return reference != null && deletedKeys.contains(reference.identityKey());
    }

    /** The number of active holds this store currently carries across every version; test/local use only. */
    public synchronized int activeHoldCount() {
        return holdsById.size();
    }

    @Override
    public synchronized AudienceRetentionHold acquire(AudienceRetentionHold hold) {
        if (hold == null) {
            throw ReferenceException.invalid("A hold acquisition requires a hold");
        }
        AudienceRetentionHold existing = holdsById.get(hold.holdId());
        if (existing != null) {
            if (!existing.heldReference().equals(hold.heldReference())) {
                throw ReferenceException.invalid("One hold identity cannot hold two different versions");
            }
            // Idempotent: a retried transaction re-acquires the same hold rather than double-counting it.
            return existing;
        }
        if (deletedKeys.contains(hold.heldReference().identityKey())) {
            throw new ReferenceException(
                    ReferenceErrorCodes.RETENTION_TARGET_UNKNOWN,
                    "A hold cannot be taken on a version that has been hard-deleted");
        }
        retainedByKey.putIfAbsent(hold.heldReference().identityKey(), hold.heldReference());
        holdsById.put(hold.holdId(), hold);
        return hold;
    }

    @Override
    public synchronized void release(String holdId) {
        if (holdId == null) {
            return;
        }
        AudienceRetentionHold removed = holdsById.remove(holdId);
        if (removed == null) {
            // Releasing an unknown or already released hold is a no-op rather than an error: the desired
            // state is "this hold is not active", and it already holds.
            return;
        }
        if (!releasePolicy.permitsDeletionWhenUnheld() && activeHolds(removed.heldReference()).isEmpty()) {
            // The host policy keeps the version alive with no hold at all; nothing further happens here.
            return;
        }
    }

    @Override
    public synchronized List<AudienceRetentionHold> activeHolds(AudienceVersionRef reference) {
        if (reference == null) {
            return List.of();
        }
        List<AudienceRetentionHold> matches = new ArrayList<>();
        for (AudienceRetentionHold hold : holdsById.values()) {
            if (hold.heldReference().equals(reference)) {
                matches.add(hold);
            }
        }
        matches.sort(Comparator.comparing(AudienceRetentionHold::holdId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized HardDeletionOutcome hardDelete(AudienceVersionRef reference) {
        if (reference == null) {
            throw ReferenceException.invalid("A hard deletion requires an exact version pin");
        }
        String key = reference.identityKey();
        if (!retainedByKey.containsKey(key) || deletedKeys.contains(key)) {
            return HardDeletionOutcome.UNKNOWN_TARGET;
        }
        if (!activeHolds(reference).isEmpty()) {
            // A published dependent version still holds this material; nothing is removed and nothing changes.
            return HardDeletionOutcome.REJECTED_ACTIVE_HOLD;
        }
        if (!releasePolicy.permitsDeletionWhenUnheld()) {
            // The host retains the material indefinitely, so an unheld version still may not be deleted.
            return HardDeletionOutcome.RETAINED_BY_POLICY;
        }
        deletedKeys.add(key);
        retainedByKey.remove(key);
        return HardDeletionOutcome.DELETED;
    }

    @Override
    public RetentionReleasePolicy releasePolicy() {
        return releasePolicy;
    }
}