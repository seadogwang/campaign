package com.company.campaign.production.audit;

import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleAuditSink;
import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The production adapter of {@link LifecycleAuditSink} into the durable audit ledger.
 *
 * <p>It maps every accepted field of {@link LifecycleAuditEvent} into the type's canonical source document: the
 * closed action and disposition names, the bounded reason code, the trusted occurrence instant, the resource
 * kind and the caller's trusted identifiers as domain-separated fingerprints. A not-found decision still cannot
 * disclose a foreign resource, because the event itself already drops its fingerprint and this adapter only ever
 * reads what the event carries.
 *
 * <p>{@link #recordAll(List)} is atomic: the whole batch is mapped and canonicalized before the ledger opens a
 * transaction, and it is appended in one transaction — all of it or none. The default method's refusal of a
 * {@code null} or {@code null}-element batch and its acceptance of an empty batch are preserved exactly.
 *
 * <p>The event carries the canonical hash of the material the command addressed ({@code contentHash}), and the
 * ledger's {@code source_hash} column carries exactly that hash unchanged. The logical key is derived from the
 * immutable identity of one command — the trusted identity tuple, the action, the resource, the plan and content
 * hashes and the occurrence instant — so an exact replay is idempotent and the same command presented with a
 * different disposition or reason is a bounded conflict.
 */
public final class ProductionLifecycleAuditSink implements LifecycleAuditSink {

    private final MyBatisPlusProductionAuditLedger ledger;

    public ProductionLifecycleAuditSink(MyBatisPlusProductionAuditLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public void record(LifecycleAuditEvent event) {
        Objects.requireNonNull(event, "event");
        ledger.record(List.of(entry(event)));
    }

    @Override
    public void recordAll(List<LifecycleAuditEvent> events) {
        Objects.requireNonNull(events, "events");
        for (LifecycleAuditEvent event : events) {
            Objects.requireNonNull(event, "event");
        }
        if (events.isEmpty()) {
            return;
        }
        List<ProductionAuditEntry> batch = new ArrayList<>(events.size());
        for (LifecycleAuditEvent event : events) {
            batch.add(entry(event));
        }
        ledger.record(batch);
    }

    /** The one lossless, safe mapping of one accepted lifecycle audit event. */
    static ProductionAuditEntry entry(LifecycleAuditEvent event) {
        ProductionAuditEventType type = ProductionAuditEventType.LIFECYCLE_COMMAND;
        ContentHash tenant = ProductionAuditIdentity.tenantFingerprint(event.tenantId());
        ContentHash program = ProductionAuditIdentity.programFingerprint(event.programId());
        ContentHash user = ProductionAuditIdentity.userFingerprint(event.userId());
        ContentHash dataScope = ProductionAuditIdentity.dataScopeFingerprint(event.dataScopeId());
        AuditResourceFingerprint resource = event.resourceFingerprint();
        ContentHash resourceHash = resource == null ? null : resource.hash();
        String resourceKind = resource == null ? null : resource.kind().name();

        ContentHash logicalKey = ProductionAuditIdentity.logicalKey(type, ProductionAuditFlatObject.of()
                .hash("tenantFingerprint", tenant)
                .hash("programFingerprint", program)
                .hash("userFingerprint", user)
                .hash("dataScopeFingerprint", dataScope)
                .name("action", event.action())
                .hash("resourceFingerprint", resourceHash)
                .hash("planHash", event.planHash())
                .hash("contentHash", event.contentHash())
                .text("occurredAt", event.occurredAt().toString())
                .node());

        String sourceDocument = ProductionAuditFlatObject.of()
                .name("action", event.action())
                .name("disposition", event.disposition())
                .text("reasonCode", event.reasonCode())
                .text("occurredAt", event.occurredAt().toString())
                .hash("tenantFingerprint", tenant)
                .hash("programFingerprint", program)
                .hash("userFingerprint", user)
                .hash("dataScopeFingerprint", dataScope)
                .text("resourceKind", resourceKind)
                .hash("resourceFingerprint", resourceHash)
                .hash("planHash", event.planHash())
                .hash("contentHash", event.contentHash())
                .build(type);

        return new ProductionAuditEntry(
                type,
                ProductionAuditIdentity.derivedEventId(logicalKey),
                logicalKey,
                tenant,
                program,
                resourceHash,
                event.disposition().name(),
                event.reasonCode(),
                event.action().name(),
                null,
                event.contentHash(),
                sourceDocument,
                event.occurredAt());
    }
}