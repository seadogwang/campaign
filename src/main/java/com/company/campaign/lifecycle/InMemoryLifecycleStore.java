package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.segment.reference.AudienceRetentionPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Synchronized in-memory {@link LifecycleStore} for tests and local use.
 *
 * <p>Every method is serialized on one monitor, so an idempotency lookup, a compare-and-set of an expected
 * revision and the writes of one commit happen as one indivisible step: two concurrent Applies of the same
 * Plan publish exactly one Draft revision and one receipt, two concurrent Applies that read the same base
 * revision cannot both advance it, and two concurrent Publish confirmations create exactly one immutable
 * Published Definition.
 *
 * <p>Atomicity is deliberately ordered audit-first: every retention hold the commit names is acquired, then
 * the audit event is recorded, and only then is state published, so a failing hold acquisition or a failing
 * audit collaborator leaves no state <em>and</em> no hold. A reference-free commit names no hold and never
 * reaches the retention port. This is a test/local adapter only: T17 implements no persistence, no JPA and no
 * Flyway.
 */
public final class InMemoryLifecycleStore implements LifecycleStore {

    private final LifecycleAuditSink auditSink;
    private final AudienceRetentionPort retentionPort;

    private final Map<String, SavedCampaignDraft> draftsByCampaign = new LinkedHashMap<>();
    private final Map<String, ApplyReceipt> receiptsByPlan = new LinkedHashMap<>();
    private final Set<String> receiptIds = new LinkedHashSet<>();

    private final Map<String, PublishPreparation> preparationsById = new LinkedHashMap<>();
    private final Set<String> consumedPreparationIds = new LinkedHashSet<>();

    private final Map<String, PublishedCampaignDefinition> publishedById = new LinkedHashMap<>();
    private final Map<String, String> latestPublishedIdByCampaign = new LinkedHashMap<>();

    private final Map<String, PublishReceipt> publishReceiptsByRequest = new LinkedHashMap<>();
    private final Set<String> publishReceiptIds = new LinkedHashSet<>();

    /** Creates a store with its own in-memory audit sink and no retention port. */
    public InMemoryLifecycleStore() {
        this(new InMemoryLifecycleAuditSink());
    }

    /** Creates a store with the supplied audit sink and no retention port. */
    public InMemoryLifecycleStore(LifecycleAuditSink auditSink) {
        this(auditSink, null);
    }

    /**
     * Creates a store whose publication transaction acquires retention holds through the supplied host port.
     *
     * <p>The port is the one collaborator a reference-bearing publication needs outside this store, and it may
     * be absent: a store without one keeps every reference-free publication exactly as it was, and refuses a
     * reference-bearing commit that names a hold rather than publishing without the hold.
     */
    public InMemoryLifecycleStore(LifecycleAuditSink auditSink, AudienceRetentionPort retentionPort) {
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.retentionPort = retentionPort;
    }

    @Override
    public synchronized Optional<SavedCampaignDraft> findDraft(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return Optional.ofNullable(draftsByCampaign.get(campaignId));
    }

    @Override
    public synchronized Optional<ApplyReceipt> findApplyReceipt(String planId) {
        Objects.requireNonNull(planId, "planId");
        return Optional.ofNullable(receiptsByPlan.get(planId));
    }

    @Override
    public synchronized ApplyReceipt commitApply(ApplyCommit commit) {
        Objects.requireNonNull(commit, "commit");

        ApplyReceipt existing = receiptsByPlan.get(commit.planId());
        if (existing != null) {
            // An exact repeat returns the original receipt without another revision or audit mutation. A
            // reused identifier bound to different material is a conflict rather than a silent overwrite.
            if (!existing.planHash().equals(commit.planHash())) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PLAN_MISMATCH,
                        "planIdReuse",
                        "A Plan identifier is already bound to a different Plan.");
            }
            return existing;
        }

        if (commit.creates()) {
            if (draftsByCampaign.containsKey(commit.draft().campaignId())) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_DRAFT_CONFLICT,
                        "campaignIdReuse",
                        "A Campaign identity is already bound to an editable Draft.");
            }
        } else {
            SavedCampaignDraft current = draftsByCampaign.get(commit.expectedCampaignId());
            if (current == null
                    || !current.draftId().equals(commit.expectedDraftId())
                    || current.draftRevision() != commit.expectedDraftRevision()) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE,
                        "expectedRevision",
                        "The editable Draft no longer matches the Plan's base revision.");
            }
        }

        if (receiptIds.contains(commit.receipt().receiptId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_RECEIPT_CONFLICT,
                    "receiptIdReuse",
                    "A receipt identifier is already bound to a different Apply receipt.");
        }

        // Audit first: a failing sink must abort the whole commit before any state is published.
        auditSink.record(commit.auditEvent());

        draftsByCampaign.put(commit.draft().campaignId(), commit.draft());
        receiptsByPlan.put(commit.planId(), commit.receipt());
        receiptIds.add(commit.receipt().receiptId());
        return commit.receipt();
    }

    @Override
    public synchronized Optional<PublishPreparation> findPreparation(String preparationId) {
        Objects.requireNonNull(preparationId, "preparationId");
        return Optional.ofNullable(preparationsById.get(preparationId));
    }

    @Override
    public synchronized boolean isPreparationConsumed(String preparationId) {
        Objects.requireNonNull(preparationId, "preparationId");
        return consumedPreparationIds.contains(preparationId);
    }

    @Override
    public synchronized PublishPreparation commitPreparation(PublishPreparationCommit commit) {
        Objects.requireNonNull(commit, "commit");

        PublishPreparation preparation = commit.preparation();
        SavedCampaignDraft current = draftsByCampaign.get(commit.expectedCampaignId());

        // The compare-and-set is re-proved here, inside the one commit, not merely in the service's
        // precheck: the exact current Campaign revision, the Draft identity and revision, and both the Draft
        // content hash and the Manifest hash the preparation froze, together with the source Plan the Draft
        // still descends from. A concurrent Apply or fork that moved any of those is refused as stale rather
        // than freezing evidence for a revision this boundary no longer holds.
        if (current == null
                || current.campaignRevision() != preparation.campaignRevision()
                || !current.draftId().equals(preparation.draftId())
                || current.draftRevision() != preparation.draftRevision()
                || !current.draftHash().equals(preparation.draftHash())
                || !current.manifestHash().equals(preparation.manifestHash())
                || !current.sourcePlanId().equals(preparation.planId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE,
                    "expectedRevision",
                    "The saved Draft no longer matches the revision this preparation froze.");
        }

        PublishPreparation existing = preparationsById.get(preparation.preparationId());
        if (existing != null) {
            // A reuse of a preparation identifier is only an exact repeat; anything else is a conflict.
            if (!existing.equals(preparation)) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                        "preparationIdReuse",
                        "A preparation identifier is already bound to a different preparation.");
            }
            return existing;
        }

        auditSink.record(commit.auditEvent());
        preparationsById.put(preparation.preparationId(), preparation);
        return preparation;
    }

    @Override
    public synchronized Optional<PublishReceipt> findPublishReceipt(
            String tenantId, String programId, String userId, String preparationId, String idempotencyKey) {
        return Optional.ofNullable(publishReceiptsByRequest.get(
                requestKey(tenantId, programId, userId, preparationId, idempotencyKey)));
    }

    @Override
    public synchronized Optional<PublishedCampaignDefinition> findPublished(String publishedId) {
        Objects.requireNonNull(publishedId, "publishedId");
        return Optional.ofNullable(publishedById.get(publishedId));
    }

    @Override
    public synchronized Optional<PublishedCampaignDefinition> findLatestPublished(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return Optional.ofNullable(latestPublishedIdByCampaign.get(campaignId)).map(publishedById::get);
    }

    @Override
    public synchronized long nextPublishedRevision(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        String latestId = latestPublishedIdByCampaign.get(campaignId);
        PublishedCampaignDefinition latest = latestId == null ? null : publishedById.get(latestId);
        return latest == null ? 1L : latest.publishedRevision() + 1L;
    }

    @Override
    public synchronized PublishReceipt commitPublish(PublishCommit commit) {
        Objects.requireNonNull(commit, "commit");

        PublishedCampaignDefinition definition = commit.definition();
        String key = requestKey(
                definition.tenantId(), definition.programId(), definition.userId(),
                commit.expectedPreparationId(), commit.idempotencyKey());

        // The idempotency lookup is deliberately first: an exact repeat must still return its original
        // receipt after the Draft, the inputs or the preparation itself have moved on, while the same key
        // with a changed request stays a conflict forever instead of decaying into "consumed".
        PublishReceipt existing = publishReceiptsByRequest.get(key);
        if (existing != null) {
            if (!existing.requestHash().equals(commit.requestHash())) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PUBLISH_CONFLICT,
                        "idempotencyKeyReuse",
                        "An idempotency key is already bound to a different publish request.");
            }
            return existing;
        }

        PublishPreparation preparation = preparationsById.get(commit.expectedPreparationId());
        if (preparation == null) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE,
                    "preparationMissing",
                    "The preparation this commit consumes is no longer stored.");
        }
        if (!preparation.preparationHash().equals(commit.expectedPreparationHash())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH,
                    "preparationHash",
                    "The commit's preparation hash is not the stored preparation's own hash.");
        }
        if (consumedPreparationIds.contains(commit.expectedPreparationId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_CONSUMED,
                    "preparationConsumed",
                    "A preparation may be consumed by at most one successful Publish.");
        }
        if (!definition.tenantId().equals(preparation.tenantId())
                || !definition.programId().equals(preparation.programId())
                || !definition.userId().equals(preparation.userId())
                || !definition.dataScopeId().equals(preparation.dataScopeId())
                || !definition.campaignId().equals(preparation.campaignId())
                || definition.campaignRevision() != preparation.campaignRevision()
                || !definition.sourceDraftId().equals(preparation.draftId())
                || definition.sourceDraftRevision() != preparation.draftRevision()
                || !definition.planId().equals(preparation.planId())
                || !definition.planHash().equals(preparation.planHash())
                || !definition.manifestHash().equals(preparation.manifestHash())
                || !definition.riskCodes().equals(preparation.requiredRiskCodes())
                || !Objects.equals(definition.forkBase(), preparation.forkBase())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH,
                    "preparationBinding",
                    "The definition does not freeze the preparation's own owner, Draft, plan or fork base.");
        }

        // The publication instant is the instant stamped on the definition, so expiring the preparation
        // exactly there is re-proved inside the transaction rather than only in the service's precheck.
        if (!preparation.expiresAt().isAfter(definition.publishedAt())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_EXPIRED,
                    "preparationExpiry",
                    "The preparation was already expired at the publication instant.");
        }

        // The Campaign's editable Draft must still be exactly the state the preparation froze: the identity,
        // both revisions and both hashes. A concurrent Apply between the service's precheck and this commit
        // therefore makes the publication stale with zero published mutation.
        SavedCampaignDraft current = draftsByCampaign.get(preparation.campaignId());
        if (current == null
                || !current.draftId().equals(preparation.draftId())
                || current.draftRevision() != preparation.draftRevision()
                || current.campaignRevision() != preparation.campaignRevision()
                || !current.draftHash().equals(preparation.draftHash())
                || !current.manifestHash().equals(preparation.manifestHash())
                || !current.sourcePlanId().equals(preparation.planId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE,
                    "savedDraft",
                    "The saved Draft moved before this publication committed.");
        }

        // The published revision is allocated inside this one synchronized commit. The revision the service
        // observed must still be the Campaign's next revision and the definition must be exactly it, so two
        // concurrent preparations can never both publish revision N and no stale result can replace the
        // latest pointer.
        long nextPublishedRevision = nextPublishedRevision(definition.campaignId());
        if (definition.publishedRevision() != nextPublishedRevision
                || commit.expectedPublishedRevision() != nextPublishedRevision) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                    "publishedRevision",
                    "The Campaign's published revision moved before this publication committed.");
        }
        if (publishedById.containsKey(definition.publishedId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                    "publishedIdReuse",
                    "A Published Definition identifier is already bound to a different definition.");
        }
        if (publishReceiptIds.contains(commit.receipt().receiptId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                    "receiptIdReuse",
                    "A receipt identifier is already bound to a different publish receipt.");
        }

        // T18: the retention holds the publication depends on are acquired inside this same commit, before the
        // audit event and before any state, so a publication either keeps every referenced version it needs
        // alive or writes nothing at all. A reference-free commit names no hold and never reaches the port.
        List<String> acquiredHoldIds = acquireRetentionHolds(commit);
        try {
            List<LifecycleAuditEvent> auditEvents = new ArrayList<>(
                    1 + commit.additionalAuditEvents().size());
            auditEvents.add(commit.auditEvent());
            auditEvents.addAll(commit.additionalAuditEvents());
            auditSink.recordAll(auditEvents);
        } catch (RuntimeException auditFailure) {
            // The audit collaborator failing rolls the holds back: the commit produced nothing, so it must
            // leave nothing behind, not even a hold on a version no publication depends on.
            releaseRetentionHolds(acquiredHoldIds);
            throw auditFailure;
        }

        publishedById.put(definition.publishedId(), definition);
        latestPublishedIdByCampaign.put(definition.campaignId(), definition.publishedId());
        publishReceiptsByRequest.put(key, commit.receipt());
        publishReceiptIds.add(commit.receipt().receiptId());
        consumedPreparationIds.add(commit.expectedPreparationId());
        return commit.receipt();
    }

    /**
     * Acquires every retention hold one commit names, rolling back the ones it already took on any failure.
     *
     * <p>Acquisition is idempotent per hold identity, and a hold identity is derived from the exact publication
     * that holds it, so re-acquiring a hold this commit already owns is a no-op rather than a second hold.
     */
    private List<String> acquireRetentionHolds(PublishCommit commit) {
        List<AudienceRetentionHold> holds = commit.retentionHolds();
        if (holds.isEmpty()) {
            return List.of();
        }
        if (retentionPort == null) {
            // A reference-bearing publication without a host retention port has nowhere to take its holds.
            // Refusing is the only honest answer: publishing without them would break the retention invariant.
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                    "referenceRetentionWiring",
                    "This lifecycle store holds no retention port for a reference-bearing publication.");
        }
        List<String> acquired = new ArrayList<>(holds.size());
        try {
            for (AudienceRetentionHold hold : holds) {
                retentionPort.acquire(hold);
                acquired.add(hold.holdId());
            }
        } catch (RuntimeException acquisitionFailure) {
            releaseRetentionHolds(acquired);
            throw acquisitionFailure;
        }
        return acquired;
    }

    /** Releases the holds one aborted commit acquired, so nothing half-acquired survives it. */
    private void releaseRetentionHolds(List<String> holdIds) {
        for (String holdId : holdIds) {
            retentionPort.release(holdId);
        }
    }

    @Override
    public synchronized SavedCampaignDraft commitFork(ForkCommit commit) {
        Objects.requireNonNull(commit, "commit");

        // The transaction re-proves that the Draft it replaces is byte-for-byte the one the service read:
        // the exact Campaign revision, Draft identity and revision and content hash all still match. A
        // concurrent Apply or a second fork that moved any of them is refused rather than silently replaced.
        SavedCampaignDraft current = draftsByCampaign.get(commit.expectedCampaignId());
        if (current == null
                || current.campaignRevision() != commit.expectedCampaignRevision()
                || !current.draftId().equals(commit.expectedDraft().draftId())
                || current.draftRevision() != commit.expectedDraft().revision()
                || !current.draftHash().equals(commit.expectedDraftHash())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FORK_CONFLICT,
                    "expectedRevision",
                    "The Campaign's editable Draft moved before this fork committed.");
        }
        PublishedIdentity forkBase = commit.draft().forkBase();
        PublishedCampaignDefinition forkSource = publishedById.get(forkBase.publishedId());
        if (forkSource == null
                || forkSource.publishedRevision() != forkBase.revision()
                || !forkSource.contentChecksum().equals(forkBase.contentHash())) {
            // The fork base must name the immutable version actually stored here: a Draft cannot claim to
            // descend from bytes this boundary never published.
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FORK_CONFLICT,
                    "forkBase",
                    "A fork base must name a stored Published Definition and its exact content.");
        }

        auditSink.record(commit.auditEvent());

        draftsByCampaign.put(commit.draft().campaignId(), commit.draft());
        return commit.draft();
    }

    @Override
    public synchronized void recordCommandAudit(LifecycleAuditEvent event) {
        auditSink.record(Objects.requireNonNull(event, "event"));
    }

    /** The number of Campaigns with a saved Draft. */
    public synchronized int draftCount() {
        return draftsByCampaign.size();
    }

    /** The number of immutable Apply receipts. */
    public synchronized int receiptCount() {
        return receiptsByPlan.size();
    }

    /** The number of server-stored publish preparations, consumed ones included. */
    public synchronized int preparationCount() {
        return preparationsById.size();
    }

    /** The number of immutable Published Definitions. */
    public synchronized int publishedCount() {
        return publishedById.size();
    }

    /** The number of immutable publish receipts. */
    public synchronized int publishReceiptCount() {
        return publishReceiptsByRequest.size();
    }

    /** The audit sink this store writes through; a test keeps its own reference to assert on it. */
    public LifecycleAuditSink auditSink() {
        return auditSink;
    }

    private static String requestKey(
            String tenantId, String programId, String userId, String preparationId, String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(preparationId, "preparationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return tenantId + '|' + programId + '|' + userId + '|' + preparationId + '|' + idempotencyKey;
    }
}
