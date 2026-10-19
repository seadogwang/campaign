package com.company.campaign.lifecycle;

/**
 * Port that allocates the server-side identities a host lifecycle operation owns.
 *
 * <p>It exists so the lifecycle boundary never reads a clock, a random source or an ambient counter
 * directly: a host supplies the generator, and a test supplies a deterministic one, so an identical Apply
 * can produce identical identities and therefore identical receipt hashes.
 *
 * <p>A generated identifier must satisfy the shared reference-ID grammar. A generator that returns null,
 * blank or out-of-grammar text fails the operation closed.
 */
@FunctionalInterface
public interface LifecycleIdGenerator {

    /**
     * The next identifier for one kind.
     *
     * @throws IllegalStateException when no bounded identity of that kind can be produced
     */
    String nextId(LifecycleIdKind kind);
}