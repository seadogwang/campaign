package com.company.campaign.segment.reference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A synchronized, in-memory {@link AudienceVersionCatalog} for tests and explicitly local use only.
 *
 * <p>It is not a production adapter: there is no persistence, no transaction and no replication here, and it
 * never becomes one by accident because the only construction path publishes one immutable snapshot at a time.
 *
 * <p>Two rules it does enforce, because they are properties of the contract rather than of storage:
 *
 * <ul>
 *   <li>a version is addressed by its full pin, so there is no method that answers a name or a "current"
 *       query — a moving target is unrepresentable here too; and</li>
 *   <li>publishing a second snapshot with the same audience and version identity but different bytes is
 *       refused with {@link ReferenceErrorCodes#VERSION_CONFLICT} rather than overwriting, which is what
 *       makes "same version ID, changed bytes" observable instead of silently mutating published material.</li>
 * </ul>
 *
 * <p>Re-publishing byte-identical material is idempotent, so a retried fixture load is harmless. Availability
 * can be advanced explicitly by a test or a host stand-in, which is how resolve-time versus execution-time
 * drift is exercised.
 */
public final class InMemoryAudienceVersionCatalog
        implements AudienceVersionCatalog, AudienceVersionAvailabilityPort {

    private final Map<String, AudienceVersionSnapshot> byIdentity = new LinkedHashMap<>();
    private final Map<String, AudienceVersionAvailability> availabilityByIdentity = new LinkedHashMap<>();

    /**
     * Publishes one immutable version snapshot.
     *
     * @throws ReferenceException with {@link ReferenceErrorCodes#VERSION_CONFLICT} when the same identity is
     *                            already published with different content
     */
    public synchronized AudienceVersionSnapshot publish(AudienceVersionSnapshot snapshot) {
        if (snapshot == null) {
            throw ReferenceException.invalid("A catalog publish requires a version snapshot");
        }
        String key = snapshot.version().identityKey();
        AudienceVersionSnapshot existing = byIdentity.get(key);
        if (existing != null) {
            if (!existing.equals(snapshot)) {
                throw new ReferenceException(
                        ReferenceErrorCodes.VERSION_CONFLICT,
                        "This audience version identity is already published with different content");
            }
            return existing;
        }
        byIdentity.put(key, snapshot);
        availabilityByIdentity.put(key, snapshot.availability());
        return snapshot;
    }

    /** Advances one version's availability; the host owns this transition, not a caller. */
    public synchronized void updateAvailability(
            AudienceVersionRef reference, AudienceVersionAvailability availability) {
        if (reference == null || availability == null) {
            throw ReferenceException.invalid("An availability update requires a pin and a state");
        }
        String key = reference.identityKey();
        if (!byIdentity.containsKey(key)) {
            throw new ReferenceException(
                    ReferenceErrorCodes.VERSION_NOT_FOUND,
                    "The exact version this availability update names is not published");
        }
        availabilityByIdentity.put(key, availability);
    }

    @Override
    public synchronized Optional<AudienceVersionSnapshot> findExact(AudienceVersionRef reference) {
        if (reference == null) {
            return Optional.empty();
        }
        AudienceVersionSnapshot snapshot = byIdentity.get(reference.identityKey());
        if (snapshot == null) {
            return Optional.empty();
        }
        // Availability is looked up separately from content, so a withdrawal is visible here immediately
        // without rewriting the published snapshot.
        return Optional.of(withAvailability(snapshot, availabilityByIdentity.get(reference.identityKey())));
    }

    @Override
    public synchronized AudienceVersionAvailability currentAvailability(AudienceVersionRef reference) {
        if (reference == null) {
            return AudienceVersionAvailability.UNAVAILABLE;
        }
        return availabilityByIdentity.getOrDefault(
                reference.identityKey(), AudienceVersionAvailability.UNAVAILABLE);
    }

    private static AudienceVersionSnapshot withAvailability(
            AudienceVersionSnapshot snapshot, AudienceVersionAvailability availability) {
        if (availability == null || availability == snapshot.availability()) {
            return snapshot;
        }
        return new AudienceVersionSnapshot(
                snapshot.tenantId(),
                snapshot.programId(),
                snapshot.version(),
                snapshot.subject(),
                snapshot.canonicalAstHash(),
                snapshot.semanticQueryPlanHash(),
                snapshot.effectiveDataScope(),
                snapshot.directDependencies(),
                snapshot.dependencyGraphHash(),
                snapshot.publishedAt(),
                availability,
                snapshot.expandedComplexity());
    }
}