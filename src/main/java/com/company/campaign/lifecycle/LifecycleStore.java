package com.company.campaign.lifecycle;

import java.util.Optional;

/**
 * Host-only transactional port for the editable Draft, the expiring publish preparation, the immutable
 * Published Definition and its receipt, the immutable Apply receipt and the lifecycle audit.
 *
 * <p>Every multi-write operation is expressed as one method, so an adapter can commit the resulting
 * material and its audit event as a single unit or roll all of it back. A failing validation, audit or
 * persistence collaborator must leave no partial state.
 *
 * <p>The port is reachable only from the host-only lifecycle boundary. There is no method that accepts a
 * caller's Manifest, ChangeSet, component, Published Definition or readiness fact: the only write paths
 * take an already-validated commit, each of which carries server-produced material.
 */
public interface LifecycleStore {

    /** The current saved Draft of a Campaign, or empty when no such Campaign exists in this boundary. */
    Optional<SavedCampaignDraft> findDraft(String campaignId);

    /** The receipt already recorded for a Plan identifier, or empty when that Plan was never applied. */
    Optional<ApplyReceipt> findApplyReceipt(String planId);

    /**
     * Commits the resulting Draft, its receipt and the audit event as one unit.
     *
     * <p>An exact repeat of an already-applied Plan returns the original receipt and writes nothing. A
     * reused Plan identifier bound to different material is a conflict. A commit whose expected base state
     * no longer matches the stored Draft is refused as stale. When the audit collaborator fails, no Draft
     * and no receipt are published.
     */
    ApplyReceipt commitApply(ApplyCommit commit);

    // ------------------------------------------------------------------ publish preparation

    /** The server-stored preparation with the given identifier, or empty when this boundary holds none. */
    Optional<PublishPreparation> findPreparation(String preparationId);

    /** True when the preparation was already consumed by one successful Publish. */
    boolean isPreparationConsumed(String preparationId);

    /**
     * Stores the preparation and records its audit event as one unit.
     *
     * <p>The transaction re-proves the whole compare-and-set rather than trusting the service's precheck: the
     * stored Draft must still carry the exact Campaign revision, Draft identity and revision, Draft content
     * hash, Manifest hash and source Plan binding the preparation froze. A commit that no longer matches is
     * refused as stale, so a preparation can never freeze evidence for a revision the Campaign has moved
     * past. When the audit collaborator fails, no preparation is stored.
     */
    PublishPreparation commitPreparation(PublishPreparationCommit commit);

    // ------------------------------------------------------------------ publication

    /**
     * The receipt already bound to one {@code (owner, preparation, idempotencyKey)} triple, or empty.
     *
     * <p>This is the idempotency index: the triple identifies a request, and the request hash carried by
     * {@link PublishCommit} tells an exact repeat from a changed request.
     */
    Optional<PublishReceipt> findPublishReceipt(
            String tenantId, String programId, String userId, String preparationId, String idempotencyKey);

    /** The immutable Published Definition with the given identifier, or empty when none exists here. */
    Optional<PublishedCampaignDefinition> findPublished(String publishedId);

    /** The latest immutable Published Definition of a Campaign, or empty when it was never published. */
    Optional<PublishedCampaignDefinition> findLatestPublished(String campaignId);

    /** The published revision the next successful Publish of a Campaign would allocate; {@code 1} when none. */
    long nextPublishedRevision(String campaignId);

    /**
     * Freezes the Published Definition, publishes its receipt, consumes the preparation and records the
     * audit event as one unit.
     *
     * <p>An exact repeat of an already-recorded {@code (owner, preparation, idempotencyKey, requestHash)}
     * returns the original receipt and writes nothing. The same key with a changed request hash is a
     * conflict. A preparation that another confirmation consumed is refused, so concurrent confirmations
     * create at most one Published Definition.
     *
     * <p>The transaction also re-proves, rather than trusting the service's precheck, that the stored
     * preparation is byte/hash-equal, still unconsumed, and was not expired at the publication instant; that
     * the Campaign's editable Draft is still exactly the identity, revisions, hashes and source Plan the
     * preparation froze; and that the revision the service observed is still the Campaign's next revision and
     * the definition is exactly it. Two concurrent preparations for one Campaign therefore cannot both
     * publish revision N, and a concurrent Apply cannot be silently overwritten. When the audit collaborator
     * fails, nothing is published and the preparation stays unconsumed.
     *
     * <p>The commit's own audit event is recorded first and every
     * {@link PublishCommit#additionalAuditEvents()} event after it, all before any state is published and all
     * inside the same rollback unit: an additional event therefore witnesses exactly the publication this
     * commit wrote, and an additional event that cannot be recorded leaves no publication and no consumed
     * preparation at all.
     */
    PublishReceipt commitPublish(PublishCommit commit);

    // ------------------------------------------------------------------ fork

    /**
     * Replaces the Campaign's editable Draft with the forked Draft and records the audit event as one unit.
     *
     * <p>The commit is compared inside the transaction against the stored Draft's exact Campaign revision,
     * Draft identity and revision and content hash, so a fork that lost a race is refused as stale rather
     * than replacing a Draft nobody read. No Published Definition is ever written: published bytes only
     * change through a later Publish.
     */
    SavedCampaignDraft commitFork(ForkCommit commit);

    /** Records exactly one audit event for a command that produced no mutation. */
    void recordCommandAudit(LifecycleAuditEvent event);
}