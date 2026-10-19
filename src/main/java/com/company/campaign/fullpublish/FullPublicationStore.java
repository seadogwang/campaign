package com.company.campaign.fullpublish;

import java.util.Optional;

/**
 * Host-only transactional port for the full publication artifact set.
 *
 * <p>Every multi-write operation is expressed as one method, so an adapter can commit the resulting material
 * and its audit events as a single unit or roll all of it back. The port is reachable only from the host-only
 * full-publication boundary. There is no method that accepts a caller's Manifest, Journey, Schedule,
 * occurrence, readiness fact, runtime-availability pin or trusted context: the only write paths take an
 * already-validated commit, each of which carries server-produced material.
 *
 * <p>The active schedule-state read lives on this port rather than on a separate collaborator on purpose. The
 * full publication commit writes the active schedule projection, so the revision the boundary reads must be
 * the revision the very same transactional backend re-proves at commit time; a boundary wired with two
 * independent references could read a revision from one backend and commit it to another, and that split is
 * not representable here.
 */
public interface FullPublicationStore {

    /**
     * The active schedule projection of a Campaign, or empty when this boundary holds none.
     *
     * <p>It is the read half of the schedule-state contract the full-publication commit re-proves: an adapter
     * must implement this and {@link #commitFullPublish(FullPublishCommit)} over one transactional backend, so
     * the revision observed by the boundary is the revision the commit replaces.
     */
    Optional<CampaignScheduleState> findScheduleState(String campaignId);

    /** The server-stored full preparation with the given identifier, or empty when this boundary holds none. */
    Optional<FullPublishPreparation> findFullPreparation(String preparationId);

    /** True when the full preparation was already consumed by one successful full publication. */
    boolean isFullPreparationConsumed(String preparationId);

    /**
     * Stores the full preparation and records its audit event as one unit.
     *
     * <p>The transaction re-proves the whole compare-and-set rather than trusting the service's precheck: the
     * accepted T17 store's saved Draft must still carry the exact Campaign revision, Draft identity and
     * revision, Draft content hash, Manifest hash and source Plan binding the frozen base preparation carries.
     * A commit that no longer matches is refused as stale, so a full preparation can never freeze evidence for
     * a revision the Campaign has moved past. When the audit collaborator fails, no preparation is stored.
     */
    FullPublishPreparation commitFullPreparation(FullPreparationCommit commit);

    /**
     * The full receipt already bound to one {@code (owner, preparation, idempotencyKey)} triple, or empty.
     *
     * <p>This is the full idempotency index: the triple identifies a request, and the request hash carried by
     * {@link FullPublishCommit} tells an exact repeat from a changed request.
     */
    Optional<FullPublishReceipt> findFullReceipt(
            String tenantId, String programId, String userId, String preparationId, String idempotencyKey);

    /** The immutable full definition with the given identifier, or empty when none exists here. */
    Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId);

    /** The latest immutable full definition of a Campaign, or empty when it was never fully published. */
    Optional<FullPublishedDefinition> findLatestFullDefinition(String campaignId);

    /**
     * Freezes the full publication as one unit: the accepted T17 definition and receipt with their audit and
     * retention effects, the full definition and receipt, the active schedule projection and one durable
     * scheduling-registration outbox event.
     *
     * <p>An exact repeat of an already-recorded {@code (owner, preparation, idempotencyKey, requestHash)}
     * returns the original full receipt and writes nothing. The same key with a changed request hash is a
     * conflict. A full preparation that another confirmation consumed is refused, so concurrent confirmations
     * create at most one full definition.
     *
     * <p>The transaction also re-proves, rather than trusting the service's precheck, that the stored full
     * preparation is byte/hash-equal, still unconsumed and unexpired at the publication instant, that the
     * Campaign's saved Draft is still exactly the state the frozen base preparation carries, and that the
     * revision the service observed is still the Campaign's next published revision. Two concurrent full
     * publications for one Campaign therefore cannot both publish revision N.
     *
     * <p>When any collaborator fails — the T21 audit sink, the accepted T17 commit, the retention port or the
     * outbox append — nothing is published, the full preparation stays unconsumed and the previously active
     * schedule projection is unchanged.
     */
    FullPublishReceipt commitFullPublish(FullPublishCommit commit);
}