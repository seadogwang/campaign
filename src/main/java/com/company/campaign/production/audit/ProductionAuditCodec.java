package com.company.campaign.production.audit;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The hand-written, versioned canonical codec of the durable audit ledger.
 *
 * <p>Every behaviour-bearing field is written and read <em>explicitly</em>, one name at a time, in one fixed
 * canonical form. There is no Java serialization, no Jackson default typing, no polymorphic class metadata and
 * no reflection anywhere in the mapping: the codec builds a plain JSON object field by field and reads a JSON
 * object field by field, so a stored row can never carry a class name, a type tag or anything the vocabulary
 * does not name.
 *
 * <p>Decoding is deliberately strict about <em>form</em>. A document is accepted only when it is already in
 * canonical form: its key set is exactly the closed one, its type/domain/format-version triple is consistent, its
 * source document's key set is exactly the field set its own type declares, every leaf is a bounded scalar, and
 * re-encoding the decoded value reproduces the input byte for byte. Unknown fields, duplicate keys, reordered
 * fields, extra whitespace, a nested structure, an out-of-bounds identifier and a malformed hash are all refused
 * with one bounded code. The refusal message never echoes a document value, a field value or a type name from
 * the input, so a malformed document cannot smuggle data into a diagnostic.
 *
 * <p>Form is not integrity. A canonical-form decode cannot detect a self-consistent mutation of a scalar — a
 * well-formed value that still satisfies every shape and pattern — because re-encoding it reproduces exactly the
 * same text. Integrity is proved by the <em>recorded</em> canonical document hash: the ledger stores
 * {@code document_hash} beside the document, and {@link #decode(String, ContentHash)} refuses any document that
 * no longer hashes to the value the ledger recorded for it. That is what makes a one-bit change to a stored
 * document detectable, and it is the check the durable ledger relies on when a row is re-read.
 */
public final class ProductionAuditCodec {

    /** The largest accepted event identifier; a fingerprint-width canonical hash fits with room to spare. */
    public static final int MAX_EVENT_ID_LENGTH = 160;

    /** The largest accepted canonical source document, in characters. */
    public static final int MAX_SOURCE_DOCUMENT_LENGTH = 8192;

    /** The exact leaf key set of one canonical ledger document. */
    static final Set<String> DOCUMENT_FIELDS = Set.of(
            "actionCode",
            "dispositionCode",
            "domain",
            "eventId",
            "eventType",
            "formatVersion",
            "logicalKey",
            "occurredAt",
            "programFingerprint",
            "reasonCode",
            "resourceFingerprint",
            "sourceDocument",
            "sourceHash",
            "tenantFingerprint",
            "topicCode");

    private static final CanonicalJson CANONICAL = new CanonicalJson();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    /**
     * The bounded shape of a topic code.
     *
     * <p>A topic is a lowercase dotted channel name such as {@code campaign.runtime.audit}, not an uppercase
     * machine code, so it is bounded by its own closed shape rather than by the machine-code pattern.
     */
    static final Pattern TOPIC_CODE = Pattern.compile("^[a-z][a-z0-9._-]{0,127}$");

    private ProductionAuditCodec() {
    }

    // ------------------------------------------------------------------ encoding

    /** The canonical UTF-8 bytes of one entry; the document hash is computed over exactly these bytes. */
    public static byte[] canonicalBytes(ProductionAuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return CANONICAL.write(document(entry));
    }

    /** The canonical text of one entry. */
    public static String canonicalDocument(ProductionAuditEntry entry) {
        return new String(canonicalBytes(entry), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ObjectNode document(ProductionAuditEntry entry) {
        ObjectNode node = NODES.objectNode();
        putCode(node, "actionCode", entry.actionCode());
        putCode(node, "dispositionCode", entry.dispositionCode());
        node.put("domain", entry.type().domain().wireName());
        node.put("eventId", entry.eventId());
        node.put("eventType", entry.type().wireName());
        node.put("formatVersion", entry.type().formatVersion());
        node.put("logicalKey", entry.logicalKey().value());
        node.put("occurredAt", entry.occurredAt().toString());
        putHash(node, "programFingerprint", entry.programFingerprint());
        putCode(node, "reasonCode", entry.reasonCode());
        putHash(node, "resourceFingerprint", entry.resourceFingerprint());
        node.replace("sourceDocument", CANONICAL.readTree(entry.sourceDocument()));
        putHash(node, "sourceHash", entry.sourceHash());
        putHash(node, "tenantFingerprint", entry.tenantFingerprint());
        putCode(node, "topicCode", entry.topicCode());
        return node;
    }

    private static void putCode(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putHash(ObjectNode node, String field, ContentHash value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.value());
        }
    }

    // ------------------------------------------------------------------ source documents

    /**
     * Canonicalizes one source document and proves it carries exactly the fields its type declares.
     *
     * <p>Only flat, bounded scalars are allowed: a string, a number, a boolean or an explicit JSON null. A nested
     * object or an array is refused, so a source document can never become an unbounded or opaque channel.
     */
    public static String canonicalSourceDocument(ProductionAuditEventType type, JsonNode sourceDocument) {
        Objects.requireNonNull(type, "type");
        if (sourceDocument == null || !sourceDocument.isObject()) {
            throw invalid("sourceDocument");
        }
        Set<String> names = fieldNames(sourceDocument);
        if (!names.equals(type.sourceFields())) {
            throw invalid("sourceDocument");
        }
        for (String name : names) {
            JsonNode value = sourceDocument.get(name);
            if (value == null || !isLeaf(value)) {
                throw invalid("sourceDocument");
            }
        }
        String canonical = CANONICAL.writeString(sourceDocument);
        if (canonical.length() > MAX_SOURCE_DOCUMENT_LENGTH) {
            throw invalid("sourceDocument");
        }
        return canonical;
    }

    private static boolean isLeaf(JsonNode value) {
        return value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean();
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Decodes one canonical ledger document, refusing anything that is not already in canonical form.
     *
     * <p>It refuses a document whose key set is not exactly the closed one, whose type, domain and format version
     * do not agree, whose source document does not carry exactly its own type's field set, whose leaves are not
     * bounded scalars, and whose text is not byte-identical to the canonical re-encoding. A self-consistent
     * mutation of a well-formed scalar is outside what form alone can detect; use
     * {@link #decode(String, ContentHash)} to prove integrity as well.
     */
    public static ProductionAuditEntry decode(String canonicalDocument) {
        if (canonicalDocument == null || canonicalDocument.isEmpty()) {
            throw invalid("document");
        }
        JsonNode tree;
        try {
            tree = CANONICAL.readTree(canonicalDocument);
        } catch (RuntimeException failure) {
            // A malformed document, a duplicate key and a tree that cannot be read all refuse identically.
            throw invalid("document");
        }
        if (!tree.isObject() || !fieldNames(tree).equals(DOCUMENT_FIELDS)) {
            throw invalid("document");
        }

        ProductionAuditEventType type = ProductionAuditEventType.fromWireName(requiredText(tree, "eventType"));
        ProductionAuditDomain domain = ProductionAuditDomain.fromWireName(requiredText(tree, "domain"));
        String formatVersion = requiredText(tree, "formatVersion");
        if (type.domain() != domain || !type.formatVersion().equals(formatVersion)) {
            throw invalid("eventType");
        }

        ProductionAuditEntry entry = new ProductionAuditEntry(
                type,
                requiredBoundText(tree, "eventId", MAX_EVENT_ID_LENGTH),
                requiredHash(tree, "logicalKey"),
                nullableHash(tree, "tenantFingerprint"),
                nullableHash(tree, "programFingerprint"),
                nullableHash(tree, "resourceFingerprint"),
                nullableCode(tree, "dispositionCode"),
                nullableCode(tree, "reasonCode"),
                nullableCode(tree, "actionCode"),
                nullableTopicCode(tree, "topicCode"),
                nullableHash(tree, "sourceHash"),
                canonicalSourceDocument(type, tree.get("sourceDocument")),
                requiredInstant(tree, "occurredAt"));

        if (!entry.canonicalDocument().equals(canonicalDocument)) {
            // Reordered keys, extra whitespace, a differently escaped value or a non-canonical source document.
            throw invalid("document");
        }
        return entry;
    }

    /**
     * Decodes one canonical ledger document and proves it is the exact document a recorded hash names.
     *
     * <p>This is the integrity check of a re-read row: the ledger stores the canonical document hash beside the
     * document, and a document that no longer hashes to the recorded value — however well-formed it still looks —
     * is refused. A one-bit change to any stored scalar therefore cannot pass unnoticed.
     */
    public static ProductionAuditEntry decode(String canonicalDocument, ContentHash recordedDocumentHash) {
        Objects.requireNonNull(recordedDocumentHash, "recordedDocumentHash");
        ProductionAuditEntry entry = decode(canonicalDocument);
        if (!entry.documentHash().equals(recordedDocumentHash)) {
            throw invalid("documentHash");
        }
        return entry;
    }

    // ------------------------------------------------------------------ leaf access

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) {
            throw invalid(field);
        }
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field);
        }
        return value.textValue();
    }

    private static String requiredBoundText(JsonNode node, String field, int maximumLength) {
        String value = requiredText(node, field);
        if (value.isEmpty() || value.length() > maximumLength) {
            throw invalid(field);
        }
        return value;
    }

    private static String nullableCode(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value != null && !MACHINE_CODE.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    private static String nullableTopicCode(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value != null && !TOPIC_CODE.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    private static ContentHash requiredHash(JsonNode node, String field) {
        String value = requiredText(node, field);
        return hash(field, value);
    }

    private static ContentHash nullableHash(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : hash(field, value);
    }

    private static ContentHash hash(String field, String value) {
        try {
            return new ContentHash(value);
        } catch (RuntimeException failure) {
            throw invalid(field);
        }
    }

    private static Instant requiredInstant(JsonNode node, String field) {
        String value = requiredText(node, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeException failure) {
            throw invalid(field);
        }
    }

    /** Bounds the event identifier without echoing it. */
    static String boundEventId(String eventId) {
        if (eventId == null || eventId.isEmpty() || eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw invalid("eventId");
        }
        return eventId;
    }

    /** Requires a bounded machine code, or an explicit absence. */
    static String requireCode(String field, String value) {
        if (value == null) {
            return null;
        }
        if (!MACHINE_CODE.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    /** Requires a bounded topic code, or an explicit absence. */
    static String requireTopicCode(String field, String value) {
        if (value == null) {
            return null;
        }
        if (!TOPIC_CODE.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    /**
     * Proves one text is already the canonical source document of its type, and returns it.
     *
     * <p>The constructor of {@link ProductionAuditEntry} uses this, so an entry cannot exist whose source
     * document is non-canonical, carries an unknown field, omits a declared field or nests a structure.
     */
    static String requireCanonicalSourceDocument(ProductionAuditEventType type, String sourceDocument) {
        if (sourceDocument == null || sourceDocument.isEmpty()) {
            throw invalid("sourceDocument");
        }
        JsonNode parsed;
        try {
            parsed = CANONICAL.readTree(sourceDocument);
        } catch (RuntimeException failure) {
            throw invalid("sourceDocument");
        }
        String canonical = canonicalSourceDocument(type, parsed);
        if (!canonical.equals(sourceDocument)) {
            throw invalid("sourceDocument");
        }
        return canonical;
    }

    /** The one bounded codec refusal. The message carries a literal field name and no document value. */
    static ProductionAuditException invalid(String field) {
        return new ProductionAuditException(
                ProductionAuditErrorCodes.PRODUCTION_AUDIT_CODEC_INVALID,
                "The production durable audit codec refused a malformed value: " + field);
    }
}