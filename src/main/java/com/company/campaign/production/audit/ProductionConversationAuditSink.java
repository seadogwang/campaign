package com.company.campaign.production.audit;

import com.company.campaign.conversation.CampaignConversationAuditRecord;
import com.company.campaign.conversation.DurableCampaignConversationAuditSink;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * The production adapter of
 * {@link com.company.campaign.conversation.CampaignConversationAuditSink} into the durable audit ledger.
 *
 * <p>It maps every accepted field of {@link CampaignConversationAuditRecord} into the type's canonical source
 * document: the conversation fingerprint, the trusted tenant/program/user identifiers as domain-separated
 * fingerprints, the bounded route kind and phase, the acceptance flag, the bounded machine code and the
 * revision. The record cannot carry an utterance, an answer, a prompt, a model output, a Proposal or a payload,
 * so none of them can reach a row.
 *
 * <p>The ledger's {@code resource_fingerprint} column carries the conversation fingerprint itself: it is already
 * the canonical, non-reversible fingerprint of the addressed conversation. The record carries no whole-record
 * canonical hash, so nothing is re-proved and {@code source_hash} stays empty rather than carrying a hash that
 * is not the record's own. The logical key is derived from the immutable identity of one turn — the conversation
 * fingerprint, the trusted identity tuple, the revision and the occurrence instant — so an exact replay is
 * idempotent and the same turn presented with a different route, phase, acceptance or code is a bounded conflict.
 */
public final class ProductionConversationAuditSink implements DurableCampaignConversationAuditSink {

    /** The disposition code of an accepted turn. */
    static final String DISPOSITION_ACCEPTED = "ACCEPTED";

    /** The disposition code of a refused turn. */
    static final String DISPOSITION_REJECTED = "REJECTED";

    private final MyBatisPlusProductionAuditLedger ledger;

    public ProductionConversationAuditSink(MyBatisPlusProductionAuditLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public void record(CampaignConversationAuditRecord record) {
        Objects.requireNonNull(record, "record");
        ledger.record(List.of(entry(record)));
    }

    /** The one lossless, safe mapping of one accepted conversation audit record. */
    static ProductionAuditEntry entry(CampaignConversationAuditRecord record) {
        ProductionAuditEventType type = ProductionAuditEventType.CONVERSATION_TURN;
        ContentHash conversation = record.conversationFingerprint();
        ContentHash tenant = ProductionAuditIdentity.tenantFingerprint(record.tenantId());
        ContentHash program = ProductionAuditIdentity.programFingerprint(record.programId());
        ContentHash user = ProductionAuditIdentity.userFingerprint(record.userId());
        String disposition = record.accepted() ? DISPOSITION_ACCEPTED : DISPOSITION_REJECTED;

        ContentHash logicalKey = ProductionAuditIdentity.logicalKey(type, ProductionAuditFlatObject.of()
                .hash("conversationFingerprint", conversation)
                .hash("tenantFingerprint", tenant)
                .hash("programFingerprint", program)
                .hash("userFingerprint", user)
                .number("revision", record.revision())
                .text("occurredAt", record.occurredAt().toString())
                .node());

        String sourceDocument = ProductionAuditFlatObject.of()
                .hash("conversationFingerprint", conversation)
                .hash("tenantFingerprint", tenant)
                .hash("programFingerprint", program)
                .hash("userFingerprint", user)
                .text("routeKind", record.routeKind())
                .text("phase", record.phase())
                .flag("accepted", record.accepted())
                .text("code", record.code())
                .number("revision", record.revision())
                .text("occurredAt", record.occurredAt().toString())
                .build(type);

        return new ProductionAuditEntry(
                type,
                ProductionAuditIdentity.derivedEventId(logicalKey),
                logicalKey,
                tenant,
                program,
                conversation,
                disposition,
                record.code(),
                record.routeKind(),
                null,
                null,
                sourceDocument,
                record.occurredAt());
    }
}