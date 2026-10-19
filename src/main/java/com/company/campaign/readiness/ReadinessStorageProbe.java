package com.company.campaign.readiness;

/**
 * The bounded, read-only probe the local health endpoint reports through.
 *
 * <p>It answers exactly two questions and carries no identifier, credential, URL or row: which storage mode is
 * wired, and whether real PostgreSQL was actually exercised. A probe must never throw for an unavailable
 * engine — an unreachable database is reported as {@link ReadinessPostgresVerification#FAILED} or
 * {@link ReadinessPostgresVerification#NOT_RUN} so the endpoint stays bounded and honest.
 */
public interface ReadinessStorageProbe {

    /** The storage mode currently wired in this process. */
    ReadinessStorageMode storageMode();

    /** Whether real PostgreSQL was exercised by this configured process. */
    ReadinessPostgresVerification postgresqlVerification();

    /** The probe used when no external engine is configured: in-memory storage, nothing verified. */
    static ReadinessStorageProbe inMemory() {
        return InMemoryReadinessStorageProbe.INSTANCE;
    }

    /** The one in-memory probe; it has no state and is safe to share. */
    enum InMemoryReadinessStorageProbe implements ReadinessStorageProbe {
        INSTANCE;

        @Override
        public ReadinessStorageMode storageMode() {
            return ReadinessStorageMode.IN_MEMORY;
        }

        @Override
        public ReadinessPostgresVerification postgresqlVerification() {
            return ReadinessPostgresVerification.NOT_RUN;
        }
    }
}