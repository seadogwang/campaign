package com.company.campaign.production.audit;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the immutable logical event key every durable audit entry is identified by.
 *
 * <p>The key is the canonical hash of a domain-separated tuple: the audit domain, the audit type, the key format
 * version and the <em>identity material</em> of the source record. The identity material is chosen by each
 * adapter from the fields that are immutable for one logical event — a record identifier where the source
 * contract carries one, and otherwise the exact trusted identity/instant tuple the source record itself is
 * defined by. It deliberately excludes the fields that describe the <em>outcome</em> of the event (a disposition,
 * a reason, a revision move, a duration), so:
 *
 * <ul>
 *   <li>replaying the exact same logical event reproduces the same key and the same canonical document, which is
 *       idempotent; and</li>
 *   <li>presenting the same logical event with changed outcome material reproduces the same key with a different
 *       canonical document, which is a bounded conflict rather than a second row.</li>
 * </ul>
 *
 * <p>The key is a pure function of bounded values: no clock, no randomness and no iteration order reaches it, so
 * the same event always hashes the same way across processes and machines.
 */
final class ProductionAuditIdentity {

    /** The logical-key format version; a literal, so a future key change is a new version, not a reinterpretation. */
    static final String KEY_FORMAT_VERSION = "1.0";

    private static final String NAMESPACE_TENANT = "tenant";
    private static final String NAMESPACE_PROGRAM = "program";
    private static final String NAMESPACE_USER = "user";
    private static final String NAMESPACE_DATA_SCOPE = "data-scope";
    private static final String NAMESPACE_SUBJECT = "subject";
    private static final String NAMESPACE_EXECUTION = "execution";
    private static final String NAMESPACE_EVENT_ID = "event-id";

    private static final CanonicalJson CANONICAL = new CanonicalJson();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ProductionAuditIdentity() {
    }

    /**
     * The logical key of one type plus one identity material tuple.
     *
     * <p>The material must be non-empty and flat, and it must not name the domain, the type or the key format
     * version: those are supplied here so an adapter cannot move an event into another domain's hash space.
     */
    static ContentHash logicalKey(ProductionAuditEventType type, ObjectNode material) {
        Objects.requireNonNull(type, "type");
        if (material == null || material.isEmpty()) {
            throw ProductionAuditCodec.invalid("logicalKey");
        }
        ObjectNode node = NODES.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> fields = material.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String name = field.getKey();
            if (value == null
                    || !(value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean())
                    || "domain".equals(name)
                    || "eventType".equals(name)
                    || "keyFormatVersion".equals(name)) {
                throw ProductionAuditCodec.invalid("logicalKey");
            }
            node.replace(name, value);
        }
        node.put("domain", type.domain().wireName());
        node.put("eventType", type.wireName());
        node.put("keyFormatVersion", KEY_FORMAT_VERSION);
        return CANONICAL.hash(node);
    }

    /**
     * The canonical identity of one ordered batch: the hash of its ordered logical keys.
     *
     * <p>Two different batches never share one, so a staged transaction token can name exactly the batch it
     * staged and a compensating rollback can prove it is compensating that batch and no other.
     */
    static ContentHash batchIdentity(java.util.List<ProductionAuditEntry> batch) {
        Objects.requireNonNull(batch, "batch");
        java.util.List<String> keys = new java.util.ArrayList<>(batch.size());
        for (ProductionAuditEntry entry : batch) {
            keys.add(entry.logicalKey().value());
        }
        return CANONICAL.hash(keys);
    }

    // ------------------------------------------------------------------ identifier fingerprints

    /** The non-reversible fingerprint of a trusted tenant identifier. */
    static ContentHash tenantFingerprint(String tenantId) {
        return ProductionAuditFingerprint.of(NAMESPACE_TENANT, tenantId);
    }

    /** The non-reversible fingerprint of a trusted program identifier. */
    static ContentHash programFingerprint(String programId) {
        return ProductionAuditFingerprint.of(NAMESPACE_PROGRAM, programId);
    }

    /** The non-reversible fingerprint of a trusted user identifier. */
    static ContentHash userFingerprint(String userId) {
        return ProductionAuditFingerprint.of(NAMESPACE_USER, userId);
    }

    /** The non-reversible fingerprint of a trusted DataScope identifier. */
    static ContentHash dataScopeFingerprint(String dataScopeId) {
        return ProductionAuditFingerprint.of(NAMESPACE_DATA_SCOPE, dataScopeId);
    }

    /** The non-reversible fingerprint of an audited subject identifier. */
    static ContentHash subjectFingerprint(String subjectId) {
        return ProductionAuditFingerprint.of(NAMESPACE_SUBJECT, subjectId);
    }

    /** The non-reversible fingerprint of an audited execution key, or {@code null} when there is none. */
    static ContentHash executionKeyFingerprint(String executionKey) {
        return ProductionAuditFingerprint.ofNullable(NAMESPACE_EXECUTION, executionKey);
    }

    /**
     * The bounded event identifier of a source record that carries none of its own.
     *
     * <p>It is exactly the logical key, which is already the canonical, immutable, domain-separated identity of
     * the event. Nothing is invented and nothing is random.
     */
    static String derivedEventId(ContentHash logicalKey) {
        return logicalKey.value();
    }

    /**
     * The non-reversible, domain-separated fingerprint of a source record's own event identifier.
     *
     * <p>A source record that already carries an event identifier must not have that identifier persisted
     * readable, and two audit domains may legitimately carry the same source identifier: a Runtime record and an
     * Action record can both be named {@code record-1}. The namespace is the closed type wire name, so the same
     * source identifier in two types never shares one fingerprint, and the identifier cannot be read back from
     * the value.
     */
    static ContentHash eventIdFingerprint(ProductionAuditEventType type, String sourceEventId) {
        Objects.requireNonNull(type, "type");
        return ProductionAuditFingerprint.of(NAMESPACE_EVENT_ID + "." + type.wireName(), sourceEventId);
    }
}