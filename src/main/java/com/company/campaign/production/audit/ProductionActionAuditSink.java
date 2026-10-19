package com.company.campaign.production.audit;

import com.company.campaign.action.ActionAuditRecord;
import com.company.campaign.action.DurableActionAuditSink;
import com.company.campaign.action.ActionErrorCodes;
import com.company.campaign.action.ActionSupport;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The production adapter of {@link ActionAuditSink} into the durable audit ledger.
 *
 * <p>It maps every accepted field of {@link ActionAuditRecord} into the type's canonical source document: the
 * source contract version, the record identifier, the trusted tenant identifier as a domain-separated
 * fingerprint, the bounded topic, reason and subject-kind codes, the subject identifier as a fingerprint, the
 * before/after revisions, the trusted occurrence instant and the record's own canonical hash. The record carries
 * no member value, recipient address, template body, variable value, reward description, Provider token or
 * reference, credential, SQL or unrestricted exception message, so none of them can reach a row.
 *
 * <p>Every entry's source hash <em>is re-proved</em>: the action contract exposes a factory that recomputes the
 * canonical hash of a record's material, so this adapter rebuilds the record from its own accepted fields and
 * refuses it when the carried hash disagrees.
 *
 * <p>Both the logical key and the event identifier are derived from the record identifier through a
 * domain-separated, non-reversible fingerprint, so a raw source identifier is never stored readable and the same
 * source identifier in another audit domain never collides with this one. An exact replay of one record is
 * idempotent and the same identifier presented with changed material is a bounded conflict.
 *
 * <h2>Why this adapter stages its batch</h2>
 *
 * <p>{@code ActionAuditSink} is the one accepted port whose contract is <em>compensating</em>: a store calls
 * {@code recordAll} and, when a later participant in the same commit fails, calls {@code rollbackAll} for the
 * exact batch, and only when every participant has succeeded does it call {@code commitAll} at the accepted
 * boundary. The audit ledger is a dedicated database, so the action store's own transaction cannot span it.
 * The only faithful implementation of that contract is therefore:
 *
 * <ol>
 *   <li>{@link #recordAll(List)} appends the whole batch inside one open, <strong>uncommitted</strong>
 *       transaction and returns; nothing is durable yet, and nothing has been mutated either. A second
 *       {@code recordAll} while one batch is still open is refused — it never silently commits the earlier
 *       batch;</li>
 *   <li>{@link #commitAll(List)} commits exactly that batch at the accepted boundary — the point at which every
 *       other fallible participant of the action commit has succeeded. The store's commit path calls it; a
 *       missing, mismatched or repeated commit is refused rather than guessed at;</li>
 *   <li>{@link #rollbackAll(List)} rolls that transaction back, discarding exactly that uncommitted batch — it is
 *       a real transaction rollback, never a {@code DELETE}, so it cannot touch a committed audit row.</li>
 * </ol>
 *
 * <p>The staged batch is held per thread of control, so a commit or rollback can only ever name the batch the
 * same thread staged, and the argument is also compared field by field against that batch. A batch that was
 * staged and neither committed nor rolled back is never committed by a later {@code recordAll}: the next stage
 * fails closed instead. Nothing about this is modelled by an update or a delete: the ledger has no such
 * statement, and an idle transaction timeout is not a substitute for an accepted commit.
 */
public final class ProductionActionAuditSink implements DurableActionAuditSink {

    private final MyBatisPlusProductionAuditLedger ledger;

    private final ThreadLocal<Staged> staged = new ThreadLocal<>();

    public ProductionActionAuditSink(MyBatisPlusProductionAuditLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public void recordAll(List<ActionAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "audits");
        }
        for (ActionAuditRecord record : records) {
            if (record == null) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "audit");
            }
        }
        if (staged.get() != null) {
            // One thread of control owns at most one open audit batch: a second stage never commits the first.
            throw stageAlreadyOpen();
        }
        List<ProductionAuditEntry> batch = new ArrayList<>(records.size());
        List<ActionAuditRecord> accepted = new ArrayList<>(records.size());
        for (ActionAuditRecord record : records) {
            batch.add(entry(record));
            accepted.add(record);
        }
        staged.set(new Staged(List.copyOf(accepted), ledger.stage(batch)));
    }

    /**
     * Commits exactly the batch this thread of control staged and has not yet compensated.
     *
     * <p>This is the accepted boundary of the action contract: the owning store calls it, with the exact batch it
     * recorded, once every other fallible participant of its commit has succeeded. The argument must name the
     * staged source records and their staged batch identity exactly; a missing, mismatched or repeated commit
     * fails closed with the bounded rollback/transaction code and commits nothing.
     */
    @Override
    public void commitAll(List<ActionAuditRecord> records) {
        requireRecords(records);
        Staged current = staged.get();
        if (current == null || !current.records().equals(List.copyOf(records))) {
            throw commitInvalid();
        }
        // The argument must also name the exact staged batch, re-proved from its own source records.
        if (!batchIdentityOf(records).equals(current.transaction().batchIdentity())) {
            throw commitInvalid();
        }
        staged.remove();
        current.transaction().commit();
    }

    @Override
    public void rollbackAll(List<ActionAuditRecord> records) {
        requireRecords(records);
        Staged current = staged.get();
        if (current == null || !current.records().equals(List.copyOf(records))) {
            throw rollbackInvalid();
        }
        // The token must still name exactly the batch this argument maps to; nothing else is compensable.
        if (!batchIdentityOf(records).equals(current.transaction().batchIdentity())) {
            throw rollbackInvalid();
        }
        staged.remove();
        current.transaction().rollback();
    }

    /**
     * A convenience over {@link #commitAll(List)} for the batch this thread of control staged.
     *
     * <p>It is deliberately not part of the accepted {@link ActionAuditSink} port and carries no argument: the
     * owning store always names the exact batch through the port, so no store code relies on this method. With
     * nothing staged it is a no-op.
     */
    void commitStaged() {
        Staged current = staged.get();
        if (current == null) {
            return;
        }
        commitAll(current.records());
    }

    private static void requireRecords(List<ActionAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            throw rollbackInvalid();
        }
        for (ActionAuditRecord record : records) {
            if (record == null) {
                throw rollbackInvalid();
            }
        }
    }

    private static ContentHash batchIdentityOf(List<ActionAuditRecord> records) {
        List<ProductionAuditEntry> mapped = new ArrayList<>(records.size());
        for (ActionAuditRecord record : records) {
            mapped.add(entry(record));
        }
        return ProductionAuditIdentity.batchIdentity(mapped);
    }

    private static ProductionAuditException rollbackInvalid() {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID,
                "The durable audit adapter has no staged batch matching this compensating rollback.");
    }

    private static ProductionAuditException commitInvalid() {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID,
                "The durable audit adapter has no staged batch matching this accepted commit.");
    }

    private static ProductionAuditException stageAlreadyOpen() {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_ROLLBACK_INVALID,
                "The durable audit adapter already holds an open staged batch for this thread of control.");
    }

    /** The one lossless, safe mapping of one accepted action Outbox/Audit record. */
    static ProductionAuditEntry entry(ActionAuditRecord record) {
        ProductionAuditEventType type = ProductionAuditEventType.ACTION_OUTBOX_AUDIT;
        reProve(record);
        ContentHash tenant = ProductionAuditIdentity.tenantFingerprint(record.tenantId());
        ContentHash subject = ProductionAuditIdentity.subjectFingerprint(record.subjectId());

        ContentHash eventId = ProductionAuditIdentity.eventIdFingerprint(type, record.recordId());

        ContentHash logicalKey = ProductionAuditIdentity.logicalKey(type, ProductionAuditFlatObject.of()
                .hash("eventIdFingerprint", eventId)
                .node());

        String sourceDocument = ProductionAuditFlatObject.of()
                .text("recordId", record.recordId())
                .text("formatVersion", record.formatVersion())
                .hash("tenantFingerprint", tenant)
                .text("topic", record.topic())
                .text("reasonCode", record.reasonCode())
                .text("subjectKind", record.subjectKind())
                .hash("subjectFingerprint", subject)
                .number("beforeRevision", record.beforeRevision())
                .number("afterRevision", record.afterRevision())
                .text("occurredAt", record.occurredAt().toString())
                .hash("recordHash", record.recordHash())
                .build(type);

        return new ProductionAuditEntry(
                type,
                eventId.value(),
                logicalKey,
                tenant,
                null,
                null,
                null,
                record.reasonCode(),
                null,
                record.topic(),
                record.recordHash(),
                sourceDocument,
                record.occurredAt());
    }

    /**
     * Rebuilds the record from its own accepted fields and refuses it when the carried hash disagrees.
     *
     * <p>The factory recomputes the canonical hash of the record's hash material, so this is a genuine re-proof
     * of the source contract's own hash rather than a restatement of it.
     */
    private static void reProve(ActionAuditRecord record) {
        ContentHash recomputed = ActionAuditRecord.of(
                        record.recordId(),
                        record.tenantId(),
                        record.reasonCode(),
                        record.subjectKind(),
                        record.subjectId(),
                        record.beforeRevision(),
                        record.afterRevision(),
                        record.occurredAt())
                .recordHash();
        if (!recomputed.equals(record.recordHash())) {
            throw ProductionAuditException.sourceHashMismatch();
        }
    }

    /** One staged, uncommitted batch of this thread of control and its exact source records. */
    private record Staged(List<ActionAuditRecord> records, ProductionAuditLedgerTransaction transaction) {
    }
}
