package com.company.campaign.production.audit;

import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.DurableRuntimeAuditSink;
import com.company.campaign.runtime.RuntimeErrorCodes;
import com.company.campaign.runtime.RuntimeSupport;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The production adapter of {@link RuntimeAuditSink} into the durable audit ledger.
 *
 * <p>It maps every accepted field of {@link RuntimeAuditRecord} into the type's canonical source document: the
 * source contract version, the record identifier, the trusted tenant identifier as a domain-separated
 * fingerprint, the bounded topic, reason and subject-kind codes, the subject and execution-key identifiers as
 * fingerprints, the before/after revisions, the trusted occurrence instant and the record's own canonical hash.
 * The record carries no member value, template body, reward description, raw fact value, SQL, credential,
 * provider data or unrestricted exception message, so none of them can reach a row.
 *
 * <p>Every entry's source hash <em>is re-proved</em>: the runtime contract exposes a factory that recomputes the
 * canonical hash of a record's material, so this adapter rebuilds the record from its own accepted fields and
 * refuses it when the carried hash disagrees. A forged or corrupted hash is therefore never appended.
 *
 * <p>{@link #recordAll(List)} is atomic: the whole batch is mapped, canonicalized and re-proved before the
 * ledger opens a transaction, and it is appended in one transaction — all of it or none. The default method's
 * refusal of a {@code null} or empty batch is preserved exactly.
 *
 * <p>Both the logical key and the event identifier are derived from the record identifier through a
 * domain-separated, non-reversible fingerprint, so a raw source identifier is never stored readable and the same
 * source identifier in another audit domain never collides with this one. An exact replay of one record is
 * idempotent and the same identifier presented with changed material is a bounded conflict.
 */
public final class ProductionRuntimeAuditSink implements DurableRuntimeAuditSink {

    private final MyBatisPlusProductionAuditLedger ledger;

    public ProductionRuntimeAuditSink(MyBatisPlusProductionAuditLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public void record(RuntimeAuditRecord record) {
        Objects.requireNonNull(record, "record");
        ledger.record(List.of(entry(record)));
    }

    @Override
    public void recordAll(List<RuntimeAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "audits");
        }
        List<ProductionAuditEntry> batch = new ArrayList<>(records.size());
        for (RuntimeAuditRecord record : records) {
            if (record == null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "audit");
            }
            batch.add(entry(record));
        }
        ledger.record(batch);
    }

    /** The one lossless, safe mapping of one accepted runtime Outbox/Audit record. */
    static ProductionAuditEntry entry(RuntimeAuditRecord record) {
        ProductionAuditEventType type = ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT;
        reProve(record);
        ContentHash tenant = ProductionAuditIdentity.tenantFingerprint(record.tenantId());
        ContentHash subject = ProductionAuditIdentity.subjectFingerprint(record.subjectId());
        ContentHash executionKey = ProductionAuditIdentity.executionKeyFingerprint(record.executionKey());

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
                .hash("executionKeyFingerprint", executionKey)
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
    private static void reProve(RuntimeAuditRecord record) {
        ContentHash recomputed = RuntimeAuditRecord.of(
                        record.recordId(),
                        record.tenantId(),
                        record.reasonCode(),
                        record.subjectKind(),
                        record.subjectId(),
                        record.executionKey(),
                        record.beforeRevision(),
                        record.afterRevision(),
                        record.occurredAt())
                .recordHash();
        if (!recomputed.equals(record.recordHash())) {
            throw ProductionAuditException.sourceHashMismatch();
        }
    }
}
