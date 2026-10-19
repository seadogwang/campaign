package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.InMemoryLifecycleStore;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleAuditSink;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.PublishCommit;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.lifecycle.SavedCampaignDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Synchronized in-memory {@link FullPublicationStore} and {@link ScheduleStore} for tests and local use.
 *
 * <p>Every method is serialized on one monitor, so an idempotency lookup, a compare-and-set of an expected
 * revision and the writes of one commit happen as one indivisible step: two concurrent full publications of
 * one Campaign create exactly one full definition and one base revision, and two concurrent scheduling
 * operations that read the same state revision cannot both advance it.
 *
 * <p>The full publication transaction composes the accepted T17 store rather than reimplementing it: the
 * accepted T17 definition, receipt, published-pointer move, preparation consumption, retention holds and audit
 * event are committed by the accepted {@link LifecycleStore#commitPublish} primitive, and the T21 full
 * definition, full receipt, active schedule projection and outbox event are written by this adapter. The
 * applied full-publication audit travels <em>inside</em> that accepted commit as an additional audit event
 * rather than being recorded by this adapter: the accepted transaction records the base audit and then the full
 * audit before it publishes any state, so an <code>APPLIED/CAMPAIGN_FULL_PUBLISH</code> event can never exist
 * without the publication it describes, and a failing accepted commit can never leave one behind.
 *
 * <p>Atomicity is ordered so the accepted T17 commit is the last step that can fail and everything after it is
 * an infallible in-memory map operation: a failing full audit, outbox append, retention acquisition, schedule
 * compare-and-set or accepted T17 commit therefore leaves <em>no</em> full publication, <em>no</em> consumed
 * preparation, <em>no</em> moved pointer and <em>no</em> moved schedule projection.
 *
 * <p>This is a test/local adapter only: T21 implements no persistence, no JPA and no Flyway.
 */
public class InMemoryFullPublicationStore
        implements FullPublicationStore, ScheduleStore, RuntimeWorkDeliveryPort {

    private final LifecycleStore base;
    private final LifecycleAuditSink auditSink;

    private final Map<String, FullPublishPreparation> preparationsById = new LinkedHashMap<>();
    private final Set<String> consumedPreparationIds = new LinkedHashSet<>();

    private final Map<String, FullPublishReceipt> receiptsByRequest = new LinkedHashMap<>();
    private final Set<String> receiptIds = new LinkedHashSet<>();

    private final Map<String, FullPublishedDefinition> fullDefinitionsById = new LinkedHashMap<>();
    private final Map<String, String> latestFullIdByCampaign = new LinkedHashMap<>();

    private final Map<String, CampaignScheduleState> scheduleStatesByCampaign = new LinkedHashMap<>();
    private final Map<String, Map<String, ScheduledOccurrenceRecord>> occurrencesByCampaign =
            new LinkedHashMap<>();

    private final List<ScheduleRegistrationOutboxEvent> outbox = new ArrayList<>();
    private final Set<String> outboxEventIds = new LinkedHashSet<>();

    /** Creates a store over the accepted T17 store, writing audit events through its own sink. */
    public InMemoryFullPublicationStore(InMemoryLifecycleStore base) {
        this(base, base.auditSink());
    }

    /**
     * Creates a store over an accepted T17 store, writing T21 audit events through the supplied sink.
     *
     * <p>A host wires the same sink the accepted store uses, so one command's T21 audit event and its accepted
     * T17 audit event land in one ordered log.
     */
    public InMemoryFullPublicationStore(LifecycleStore base, LifecycleAuditSink auditSink) {
        this.base = Objects.requireNonNull(base, "base");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    // ------------------------------------------------------------------ full preparation

    @Override
    public synchronized Optional<FullPublishPreparation> findFullPreparation(String preparationId) {
        Objects.requireNonNull(preparationId, "preparationId");
        return Optional.ofNullable(preparationsById.get(preparationId));
    }

    @Override
    public synchronized boolean isFullPreparationConsumed(String preparationId) {
        Objects.requireNonNull(preparationId, "preparationId");
        return consumedPreparationIds.contains(preparationId);
    }

    @Override
    public synchronized FullPublishPreparation commitFullPreparation(FullPreparationCommit commit) {
        Objects.requireNonNull(commit, "commit");
        FullPublishPreparation preparation = commit.preparation();

        // The compare-and-set is re-proved here, inside the one commit, not merely in the service's precheck:
        // the saved Draft must still carry exactly the Campaign revision, Draft identity and revision and both
        // hashes the frozen base preparation carries.
        requireDraftStillMatches(preparation);

        FullPublishPreparation existing = preparationsById.get(preparation.preparationId());
        if (existing != null) {
            if (!existing.equals(preparation)) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                        "fullPreparationIdReuse",
                        "A full preparation identifier is already bound to a different preparation.");
            }
            return existing;
        }
        auditSink.record(commit.auditEvent());
        preparationsById.put(preparation.preparationId(), preparation);
        return preparation;
    }

    // ------------------------------------------------------------------ full publication

    @Override
    public synchronized Optional<FullPublishReceipt> findFullReceipt(
            String tenantId, String programId, String userId, String preparationId, String idempotencyKey) {
        return Optional.ofNullable(receiptsByRequest.get(
                requestKey(tenantId, programId, userId, preparationId, idempotencyKey)));
    }

    @Override
    public synchronized Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId) {
        Objects.requireNonNull(fullDefinitionId, "fullDefinitionId");
        return Optional.ofNullable(fullDefinitionsById.get(fullDefinitionId));
    }

    @Override
    public synchronized Optional<FullPublishedDefinition> findLatestFullDefinition(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return Optional.ofNullable(latestFullIdByCampaign.get(campaignId)).map(fullDefinitionsById::get);
    }

    @Override
    public synchronized FullPublishReceipt commitFullPublish(FullPublishCommit commit) {
        Objects.requireNonNull(commit, "commit");

        FullPublishedDefinition fullDefinition = commit.fullDefinition();
        String key = requestKey(
                fullDefinition.tenantId(), fullDefinition.programId(), fullDefinition.userId(),
                commit.expectedPreparationId(), commit.idempotencyKey());

        // The idempotency lookup is deliberately first: an exact repeat must still return its original full
        // receipt after the Draft, the inputs or the preparation itself have moved on, while the same key with
        // a changed request stays a conflict forever instead of decaying into "consumed".
        FullPublishReceipt existing = receiptsByRequest.get(key);
        if (existing != null) {
            if (!existing.requestHash().equals(commit.requestHash())) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PUBLISH_CONFLICT,
                        "idempotencyKeyReuse",
                        "An idempotency key is already bound to a different full publish request.");
            }
            return existing;
        }

        FullPublishPreparation preparation = preparationsById.get(commit.expectedPreparationId());
        if (preparation == null) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE,
                    "preparationMissing",
                    "The full preparation this commit consumes is no longer stored.");
        }
        if (!preparation.preparationHash().equals(commit.expectedPreparationHash())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_MISMATCH,
                    "preparationHash",
                    "The commit's full preparation hash is not the stored preparation's own hash.");
        }
        if (consumedPreparationIds.contains(commit.expectedPreparationId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_CONSUMED,
                    "preparationConsumed",
                    "A full preparation may be consumed by at most one successful full publication.");
        }
        if (!preparation.expiresAt().isAfter(fullDefinition.publishedAt())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_EXPIRED,
                    "preparationExpiry",
                    "The full preparation was already expired at the publication instant.");
        }
        // The frozen full preparation must describe exactly the publication this commit freezes.
        if (!preparation.campaignId().equals(fullDefinition.campaignId())
                || preparation.campaignRevision() != fullDefinition.campaignRevision()
                || !preparation.journeyHash().equals(fullDefinition.journeyHash())
                || !preparation.scheduleHash().equals(fullDefinition.scheduleHash())
                || !preparation.requiredRiskCodes().equals(commit.fullReceipt().confirmedRiskCodes())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_MISMATCH,
                    "preparationBinding",
                    "The full definition does not freeze the preparation's own Journey, Schedule or risks.");
        }
        // The accepted T17 store's Draft must still be exactly the state the frozen base preparation carries.
        requireDraftStillMatches(preparation);

        // The active schedule projection is written by this same commit, so the revision it replaces is
        // re-proved here instead of trusted from the service's earlier read: a concurrent pause, resume or
        // publication that advanced the projection between that read and this commit is refused rather than
        // silently overwritten. The incoming projection must be exactly the stored Campaign's next revision,
        // or the first revision when this Campaign holds no projection yet.
        CampaignScheduleState currentSchedule = scheduleStatesByCampaign.get(fullDefinition.campaignId());
        long currentScheduleRevision = currentSchedule == null ? 0L : currentSchedule.stateRevision();
        if (!commit.scheduleState().campaignId().equals(fullDefinition.campaignId())
                || (currentSchedule != null
                        && !currentSchedule.campaignId().equals(commit.scheduleState().campaignId()))
                || commit.scheduleState().stateRevision() != currentScheduleRevision + 1L) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT,
                    "scheduleStateRevision",
                    "The Campaign's active schedule moved before this full publication committed.");
        }

        // The published revision is observed here but only proved inside the accepted T17 commit below, which
        // also re-proves the base preparation, the base definition binding and the retention holds.
        long nextPublishedRevision = base.nextPublishedRevision(fullDefinition.campaignId());
        if (commit.expectedPublishedRevision() != nextPublishedRevision) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                    "publishedRevision",
                    "The Campaign's published revision moved before this full publication committed.");
        }
        if (fullDefinitionsById.containsKey(fullDefinition.fullDefinitionId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                    "fullDefinitionIdReuse",
                    "A full definition identifier is already bound to a different definition.");
        }
        if (receiptIds.contains(commit.fullReceipt().receiptId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                    "receiptIdReuse",
                    "A receipt identifier is already bound to a different full publish receipt.");
        }
        if (outbox.size() >= FullPublishVersions.MAX_OUTBOX_EVENTS
                || outboxEventIds.contains(commit.outboxEvent().eventId())) {
            throw new LifecycleException(
                    FullPublishErrorCodes.FULL_OUTBOX_INVALID,
                    "outbox",
                    "The local scheduling-registration outbox cannot accept this event.");
        }

        // The full receipt's base identity must witness exactly the base receipt this commit freezes. This is
        // proved before the accepted commit rather than after it, so the whole failure surface of the commit is
        // one ordering: every check, then the one accepted commit, then infallible in-memory writes only.
        requireBaseReceiptWitnessesCommit(commit);

        // The last failure point a local adapter can inject before the commit point: the outbox append itself.
        // It runs before anything is written, so a failure here leaves no full publication and no moved pointer.
        beforeOutboxAppend();

        // The accepted T17 commit consumes and witnesses the accepted T17 preparation the full preparation
        // extends, not the full preparation itself: the two artifacts have their own identities and hashes, and
        // the full definition binds the base definition that this commit freezes. The applied full-publication
        // audit travels with this commit as an additional audit event, so the accepted transaction records the
        // base audit first and then the full audit, before it publishes any state: a failing accepted commit
        // therefore leaves no full applied audit, and a failing additional audit leaves no publication.
        base.commitPublish(new PublishCommit(
                commit.baseReceipt().preparationId(), commit.baseReceipt().preparationHash(),
                commit.idempotencyKey(), commit.requestHash(), commit.expectedPublishedRevision(),
                commit.baseDefinition(), commit.baseReceipt(), commit.baseAuditEvent(),
                commit.retentionHolds(), List.of(commit.fullAuditEvent())));

        // Everything below is an infallible in-memory write, so the accepted T17 commit above is the commit
        // point of the whole transaction. No fallible full-publication operation runs after it.
        consumedPreparationIds.add(commit.expectedPreparationId());
        receiptsByRequest.put(key, commit.fullReceipt());
        receiptIds.add(commit.fullReceipt().receiptId());
        fullDefinitionsById.put(fullDefinition.fullDefinitionId(), fullDefinition);
        latestFullIdByCampaign.put(fullDefinition.campaignId(), fullDefinition.fullDefinitionId());
        // A newer publication atomically supersedes the older active schedule for future planning. The
        // occurrences already committed for the older version are deliberately left untouched and stay bound to
        // that frozen version.
        scheduleStatesByCampaign.put(fullDefinition.campaignId(), commit.scheduleState());
        outbox.add(commit.outboxEvent());
        outboxEventIds.add(commit.outboxEvent().eventId());
        // The original full receipt is returned unchanged; the accepted T17 receipt is already stored by the
        // accepted commit, whose return value needs no further handling. A receipt is immutable, so this is the
        // same value an exact retry returns, and the base-witness check already ran before the commit point.
        return commit.fullReceipt();
    }

    /**
     * Proves, before the accepted commit runs, that the full receipt witnesses the base receipt it freezes.
     *
     * <p>It is a validation of server-produced material, not a re-derivation: the full receipt, the full
     * definition and the base receipt are each self-valid and already bound to one another by the commit's own
     * constructor, so this only re-states the binding at the one place the transaction's ordering depends on.
     */
    private static void requireBaseReceiptWitnessesCommit(FullPublishCommit commit) {
        if (!commit.baseReceipt().publishedId().equals(commit.fullReceipt().basePublishedId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                    "basePublishedId",
                    "The accepted T17 receipt does not witness the full definition's own base publication.");
        }
    }

    /**
     * The one extension seam of this local adapter: called immediately before the outbox append, while nothing
     * has been written yet.
     *
     * <p>It exists so a deterministic local test can prove that a failing outbox write rolls the whole
     * publication back. A production adapter has no reason to override it.
     */
    protected void beforeOutboxAppend() {
        // No-op by default.
    }

    /**
     * The one extension seam of this local adapter's scheduling path: called before any occurrence is committed.
     *
     * <p>It exists so a deterministic local test can prove that a failing occurrence write commits nothing. A
     * production adapter has no reason to override it.
     */
    protected void beforeOccurrenceCommit() {
        // No-op by default.
    }

    private void requireDraftStillMatches(FullPublishPreparation preparation) {
        SavedCampaignDraft current = base.findDraft(preparation.campaignId()).orElse(null);
        if (current == null
                || !current.tenantId().equals(preparation.tenantId())
                || !current.programId().equals(preparation.programId())
                || !current.userId().equals(preparation.userId())
                || current.campaignRevision() != preparation.campaignRevision()
                || !current.draftId().equals(preparation.draftId())
                || current.draftRevision() != preparation.draftRevision()
                || !current.draftHash().equals(preparation.basePreparation().draftHash())
                || !current.manifestHash().equals(preparation.basePreparation().manifestHash())
                || !current.sourcePlanId().equals(preparation.basePreparation().planId())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE,
                    "savedDraft",
                    "The saved Draft moved before this full publication committed.");
        }
    }

    // ------------------------------------------------------------------ schedule store

    @Override
    public synchronized Optional<CampaignScheduleState> findScheduleState(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return Optional.ofNullable(scheduleStatesByCampaign.get(campaignId));
    }

    @Override
    public synchronized List<ScheduledOccurrenceRecord> findCommittedOccurrences(String campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        Map<String, ScheduledOccurrenceRecord> records =
                occurrencesByCampaign.getOrDefault(campaignId, Map.of());
        return records.values().stream()
                .sorted((left, right) -> {
                    int nominal = left.nominalInstant().compareTo(right.nominalInstant());
                    return nominal != 0 ? nominal : left.executionKey().compareTo(right.executionKey());
                })
                .toList();
    }

    @Override
    public synchronized Optional<CampaignScheduleState> commitScheduleOperation(
            String campaignId,
            long expectedStateRevision,
            CampaignScheduleState replacement,
            List<ScheduledOccurrenceRecord> records,
            LifecycleAuditEvent auditEvent) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(auditEvent, "auditEvent");
        if (expectedStateRevision < 0) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT,
                    "expectedStateRevision",
                    "An expected schedule state revision must not be negative.");
        }
        CampaignScheduleState current = scheduleStatesByCampaign.get(campaignId);
        long currentRevision = current == null ? 0L : current.stateRevision();
        if (currentRevision != expectedStateRevision) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT,
                    "stateRevision",
                    "The Campaign's active schedule state moved before this operation committed.");
        }
        if (replacement != null
                && (!replacement.campaignId().equals(campaignId)
                || replacement.stateRevision() != expectedStateRevision + 1L)) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT,
                    "replacementRevision",
                    "A replacement schedule state must be exactly the next revision of its Campaign.");
        }
        Map<String, ScheduledOccurrenceRecord> existing =
                occurrencesByCampaign.computeIfAbsent(campaignId, key -> new LinkedHashMap<>());
        List<ScheduledOccurrenceRecord> fresh = new ArrayList<>(records.size());
        Set<String> freshKeys = new LinkedHashSet<>();
        for (ScheduledOccurrenceRecord record : records) {
            if (record == null || !record.campaignId().equals(campaignId)) {
                throw new LifecycleException(
                        FullPublishErrorCodes.OCCURRENCE_RECORD_INVALID,
                        "occurrenceBinding",
                        "An occurrence record must witness the Campaign it is committed for.");
            }
            // An occurrence whose execution key is already committed is idempotently skipped rather than
            // duplicated or reassigned, and an already-committed record is never rewritten.
            if (existing.containsKey(record.executionKey()) || !freshKeys.add(record.executionKey())) {
                continue;
            }
            fresh.add(record);
        }

        beforeOccurrenceCommit();
        auditSink.record(auditEvent);

        if (replacement != null) {
            scheduleStatesByCampaign.put(campaignId, replacement);
        }
        for (ScheduledOccurrenceRecord record : fresh) {
            existing.put(record.executionKey(), record);
        }
        return Optional.ofNullable(scheduleStatesByCampaign.get(campaignId));
    }

    // ------------------------------------------------------------------ runtime work read model

    /**
     * Every committed scheduling-registration outbox event, in deterministic append order.
     *
     * <p>This is the production-shaped read model a Runtime delivery enumerates; it is deliberately not the
     * test-only {@link #outboxEvents()} getter, so a consumer never depends on a test seam.
     */
    @Override
    public synchronized List<ScheduleRegistrationOutboxEvent> findRegistrationWork() {
        return List.copyOf(outbox);
    }

    @Override
    public synchronized Optional<ScheduleRegistrationOutboxEvent> findRegistrationWork(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        for (ScheduleRegistrationOutboxEvent event : outbox) {
            if (event.eventId().equals(eventId)) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<ScheduledOccurrenceRecord> findOccurrenceWork(String campaignId) {
        return findCommittedOccurrences(campaignId);
    }

    @Override
    public synchronized Optional<ScheduledOccurrenceRecord> findOccurrenceWork(
            String campaignId, String executionKey) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(executionKey, "executionKey");
        Map<String, ScheduledOccurrenceRecord> records = occurrencesByCampaign.get(campaignId);
        return records == null
                ? Optional.empty()
                : Optional.ofNullable(records.get(executionKey));
    }

    @Override
    public synchronized Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId) {
        Objects.requireNonNull(publishedId, "publishedId");
        return base.findPublished(publishedId);
    }

    // ------------------------------------------------------------------ observation for tests

    /** Every scheduling-registration outbox event this store holds, in append order. */
    public synchronized List<ScheduleRegistrationOutboxEvent> outboxEvents() {
        return List.copyOf(outbox);
    }

    /** The number of server-stored full preparations, consumed ones included. */
    public synchronized int fullPreparationCount() {
        return preparationsById.size();
    }

    /** The number of immutable full definitions. */
    public synchronized int fullDefinitionCount() {
        return fullDefinitionsById.size();
    }

    /** The number of immutable full receipts. */
    public synchronized int fullReceiptCount() {
        return receiptsByRequest.size();
    }

    /** The accepted T17 store this adapter composes. */
    public LifecycleStore baseStore() {
        return base;
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

    /** Kept for symmetry with the accepted store's redacted diagnostics. */
    @Override
    public String toString() {
        return "InMemoryFullPublicationStore[preparations=" + fullPreparationCount()
                + ", definitions=" + fullDefinitionCount()
                + ", receipts=" + fullReceiptCount() + ']';
    }
}