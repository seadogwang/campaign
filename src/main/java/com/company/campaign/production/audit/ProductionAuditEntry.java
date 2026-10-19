package com.company.campaign.production.audit;

import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable, canonical, ready-to-append durable audit entry.
 *
 * <p>It is the exact value one adapter maps an accepted source record into, and the exact value the ledger
 * appends. Every stored field is bounded, closed and already safe: identifiers are domain-separated
 * fingerprints, codes come from a closed vocabulary, the source document is the hand-written canonical mapping
 * of the source record, and the only instants are the trusted occurrence instant of the source and the
 * database's own insertion instant.
 *
 * <p>Two derived values are not constructor inputs, because they are <em>defined</em> by the entry:
 * <ul>
 *   <li>{@link #documentHash()} is the canonical hash of {@link #canonicalDocument()};</li>
 *   <li>the ledger's uniqueness rule uses {@link #logicalKey()} for identity, so an exact replay of the same
 *       logical event carries the same key and the same document hash, while changed material for the same
 *       logical event carries the same key and a different document hash.</li>
 * </ul>
 *
 * <p>The canonical constructor re-proves the source document, so an entry cannot be built over a
 * non-canonical, unknown-field, incomplete or nested source document.
 */
record ProductionAuditEntry(
        ProductionAuditEventType type,
        String eventId,
        ContentHash logicalKey,
        ContentHash tenantFingerprint,
        ContentHash programFingerprint,
        ContentHash resourceFingerprint,
        String dispositionCode,
        String reasonCode,
        String actionCode,
        String topicCode,
        ContentHash sourceHash,
        String sourceDocument,
        Instant occurredAt) {

    ProductionAuditEntry {
        Objects.requireNonNull(type, "type");
        eventId = ProductionAuditCodec.boundEventId(eventId);
        Objects.requireNonNull(logicalKey, "logicalKey");
        dispositionCode = ProductionAuditCodec.requireCode("dispositionCode", dispositionCode);
        reasonCode = ProductionAuditCodec.requireCode("reasonCode", reasonCode);
        actionCode = ProductionAuditCodec.requireCode("actionCode", actionCode);
        topicCode = ProductionAuditCodec.requireTopicCode("topicCode", topicCode);
        sourceDocument = ProductionAuditCodec.requireCanonicalSourceDocument(type, sourceDocument);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** The canonical ledger document of this entry; the document hash is computed over exactly this text. */
    String canonicalDocument() {
        return ProductionAuditCodec.canonicalDocument(this);
    }

    /** The canonical document hash: the identity of the exact stored material. */
    ContentHash documentHash() {
        return ContentHash.sha256(ProductionAuditCodec.canonicalBytes(this));
    }

    /** Decodes one canonical ledger document, refusing anything not already in canonical form. */
    static ProductionAuditEntry decode(String canonicalDocument) {
        return ProductionAuditCodec.decode(canonicalDocument);
    }

    /** Decodes one canonical ledger document and proves it hashes to the recorded canonical document hash. */
    static ProductionAuditEntry decode(String canonicalDocument, ContentHash recordedDocumentHash) {
        return ProductionAuditCodec.decode(canonicalDocument, recordedDocumentHash);
    }

    /** Redacted diagnostics: no event identifier, fingerprint, code, instant or document is printed. */
    @Override
    public String toString() {
        return "ProductionAuditEntry[type=" + type
                + ", documentHash=" + documentHash() + ']';
    }
}