package com.company.campaign.production.audit;

import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.security.audit.AuditSink;
import com.company.campaign.security.audit.SecurityAuditEvent;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * The production adapter of {@link AuditSink} into the durable audit ledger.
 *
 * <p>It maps every accepted field of {@link SecurityAuditEvent} into the type's canonical source document: the
 * closed action and disposition names, the bounded reason code, the bounded duration, the trusted occurrence
 * instant, the resource kind and the caller's trusted identifiers. Identifiers are never stored readable — the
 * tenant, program, user and DataScope identifiers become domain-separated fingerprints, and the resource becomes
 * the canonical hash its own {@link AuditResourceFingerprint} already carries, so a not-found decision still
 * cannot disclose a foreign resource.
 *
 * <p>The source contract carries no whole-record canonical hash, so nothing is re-proved here and the ledger's
 * {@code source_hash} column carries the one hash the event does carry: its already-redacted projection hash,
 * when present. The logical key is derived from the fields that are immutable for one recorded decision — the
 * trusted identity tuple, the action, the resource, the projection hash and the occurrence instant — so an exact
 * replay is idempotent and the same decision presented with a different disposition or reason is a bounded
 * conflict.
 */
public final class ProductionSecurityAuditSink implements AuditSink {

    private final MyBatisPlusProductionAuditLedger ledger;

    public ProductionSecurityAuditSink(MyBatisPlusProductionAuditLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public void record(SecurityAuditEvent event) {
        Objects.requireNonNull(event, "event");
        ledger.record(List.of(entry(event)));
    }

    /** The one lossless, safe mapping of one accepted security audit event. */
    static ProductionAuditEntry entry(SecurityAuditEvent event) {
        ProductionAuditEventType type = ProductionAuditEventType.SECURITY_DECISION;
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
                .hash("redactedProjectionHash", event.redactedProjectionHash())
                .text("occurredAt", event.occurredAt().toString())
                .node());

        String sourceDocument = ProductionAuditFlatObject.of()
                .name("action", event.action())
                .name("disposition", event.disposition())
                .text("reasonCode", event.reasonCode())
                .number("durationNanos", event.durationNanos())
                .text("occurredAt", event.occurredAt().toString())
                .hash("tenantFingerprint", tenant)
                .hash("programFingerprint", program)
                .hash("userFingerprint", user)
                .hash("dataScopeFingerprint", dataScope)
                .text("resourceKind", resourceKind)
                .hash("resourceFingerprint", resourceHash)
                .hash("redactedProjectionHash", event.redactedProjectionHash())
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
                event.redactedProjectionHash(),
                sourceDocument,
                event.occurredAt());
    }
}