package com.company.campaign.production.audit;

import com.company.campaign.runtime.RuntimeVersions;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canonical codec of the durable audit ledger.
 *
 * <p>It is deterministic scripted/unit evidence, not database evidence: nothing here touches PostgreSQL.
 */
class ProductionAuditCodecTest {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private static final String LOGICAL_KEY_PREFIX = "\"logicalKey\":\"";

    @Test
    void everyPortsEntryRoundTripsThroughItsOwnCanonicalDocument() {
        for (ProductionAuditEntry entry : ProductionAuditTestFixtures.allEntries()) {
            String document = entry.canonicalDocument();
            assertThat(document).isEqualTo(entry.canonicalDocument());
            ProductionAuditEntry decoded = ProductionAuditEntry.decode(document);
            assertThat(decoded).isEqualTo(entry);
            assertThat(decoded.documentHash()).isEqualTo(entry.documentHash());
            assertThat(decoded.canonicalDocument()).isEqualTo(document);
        }
    }

    @Test
    void theSourceDocumentCarriesExactlyTheFieldsItsTypeDeclares() throws Exception {
        for (ProductionAuditEntry entry : ProductionAuditTestFixtures.allEntries()) {
            ObjectNode document = (ObjectNode) PLAIN.readTree(entry.canonicalDocument());
            ObjectNode source = (ObjectNode) document.get("sourceDocument");
            assertThat(names(source))
                    .as("the source document of " + entry.type())
                    .containsExactlyInAnyOrderElementsOf(entry.type().sourceFields());
        }
    }

    @Test
    void theCanonicalDocumentIsCompactAndItsKeysAreSorted() throws Exception {
        String document = ProductionAuditTestFixtures.runtimeEntry().canonicalDocument();
        assertThat(document).doesNotContain("\n").doesNotContain(": ").doesNotContain(", ");
        List<String> keys = names((ObjectNode) PLAIN.readTree(document));
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        assertThat(keys).isEqualTo(sorted);
    }

    @Test
    void theDeclaredDocumentFieldSetIsTheCanonicalKeySet() throws Exception {
        List<String> actual = names((ObjectNode) PLAIN.readTree(
                ProductionAuditTestFixtures.actionEntry().canonicalDocument()));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(ProductionAuditCodec.DOCUMENT_FIELDS);
        assertThat(ProductionAuditCodec.DOCUMENT_FIELDS).hasSize(15);
    }

    @Test
    void anUnknownLedgerFieldIsRefused() {
        String document = ProductionAuditTestFixtures.runtimeEntry().canonicalDocument();
        String mutated = "{\"extraField\":null," + document.substring(1);
        assertThatThrownBy(() -> ProductionAuditEntry.decode(mutated))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CODEC_INVALID));
    }

    @Test
    void aMissingLedgerFieldIsRefused() throws Exception {
        String document = ProductionAuditTestFixtures.runtimeEntry().canonicalDocument();
        ObjectNode node = (ObjectNode) PLAIN.readTree(document);
        node.remove("occurredAt");
        assertThatThrownBy(() -> ProductionAuditEntry.decode(PLAIN.writeValueAsString(node)))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void aDuplicateLedgerKeyIsRefused() {
        String document = ProductionAuditTestFixtures.runtimeEntry().canonicalDocument();
        int end = document.indexOf(',');
        String duplicated = "{" + document.substring(1, end) + "," + document.substring(1);
        assertThatThrownBy(() -> ProductionAuditEntry.decode(duplicated))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void aReorderedLedgerDocumentIsRefused() throws Exception {
        String document = ProductionAuditTestFixtures.runtimeEntry().canonicalDocument();
        ObjectNode parsed = (ObjectNode) PLAIN.readTree(document);
        ObjectNode reordered = JsonNodeFactory.instance.objectNode();
        reordered.replace("topicCode", parsed.get("topicCode"));
        parsed.fields().forEachRemaining(field -> {
            if (!"topicCode".equals(field.getKey())) {
                reordered.replace(field.getKey(), field.getValue());
            }
        });
        String mutated = PLAIN.writeValueAsString(reordered);
        assertThat(mutated).isNotEqualTo(document);
        assertThatThrownBy(() -> ProductionAuditEntry.decode(mutated))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void theRecordedDocumentHashProvesTheExactStoredDocument() {
        for (ProductionAuditEntry entry : ProductionAuditTestFixtures.allEntries()) {
            assertThat(ProductionAuditEntry.decode(entry.canonicalDocument(), entry.documentHash()))
                    .isEqualTo(entry);
        }
    }

    @Test
    void aOneBitChangeToAStoredLogicalKeyNoLongerMatchesItsRecordedDocumentHash() {
        ProductionAuditEntry entry = ProductionAuditTestFixtures.runtimeEntry();
        String mutated = flipOneHexDigit(entry.canonicalDocument());

        assertThat(mutated).isNotEqualTo(entry.canonicalDocument());
        // The mutation is still a well-formed canonical document, so form alone cannot detect it ...
        assertThat(ProductionAuditEntry.decode(mutated)).isNotNull();
        assertThat(ProductionAuditEntry.decode(mutated).documentHash()).isNotEqualTo(entry.documentHash());
        // ... and the recorded canonical document hash does detect it.
        assertThatThrownBy(() -> ProductionAuditEntry.decode(mutated, entry.documentHash()))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CODEC_INVALID));
    }

    @Test
    void aOneBitChangeToAStoredSourceDocumentNoLongerMatchesItsRecordedDocumentHash() {
        ProductionAuditEntry entry = ProductionAuditTestFixtures.runtimeEntry();
        String mutated = entry.canonicalDocument().replace("EXECUTION_CREATED", "EXECUTION_CREATEO");

        assertThat(mutated).isNotEqualTo(entry.canonicalDocument());
        assertThat(ProductionAuditEntry.decode(mutated)).isNotNull();
        assertThatThrownBy(() -> ProductionAuditEntry.decode(mutated, entry.documentHash()))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CODEC_INVALID));
    }

    @Test
    void aDocumentThatIsNotTheOneTheRecordedHashNamesIsRefused() {
        ProductionAuditEntry runtime = ProductionAuditTestFixtures.runtimeEntry();
        ProductionAuditEntry action = ProductionAuditTestFixtures.actionEntry();

        assertThatThrownBy(() -> ProductionAuditEntry.decode(runtime.canonicalDocument(), action.documentHash()))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CODEC_INVALID));
    }

    @Test
    void anUnknownTypeOrADisagreeingDomainOrVersionIsRefused() {
        String document = ProductionAuditTestFixtures.runtimeEntry().canonicalDocument();
        assertThatThrownBy(() -> ProductionAuditEntry.decode(document.replace(
                        "\"eventType\":\"RUNTIME_OUTBOX_AUDIT\"", "\"eventType\":\"UNKNOWN_TYPE\"")))
                .isInstanceOf(ProductionAuditException.class);
        assertThatThrownBy(() -> ProductionAuditEntry.decode(document.replace(
                        "\"domain\":\"RUNTIME\"", "\"domain\":\"ACTION\"")))
                .isInstanceOf(ProductionAuditException.class);
        assertThatThrownBy(() -> ProductionAuditEntry.decode(document.replace(
                        "\"formatVersion\":\"1.0\"", "\"formatVersion\":\"9.9\"")))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void anUndeclaredSourceFieldIsRefused() {
        assertThatThrownBy(() -> ProductionAuditCodec.canonicalSourceDocument(
                        ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT,
                        ProductionAuditFlatObject.of().text("recordId", "record-1").text("surprise", "x").node()))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void anIncompleteSourceDocumentIsRefused() {
        ProductionAuditFlatObject incomplete = ProductionAuditFlatObject.of().text("recordId", "record-1");
        assertThatThrownBy(() -> incomplete.build(ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void aNestedOrNonScalarSourceFieldIsRefused() {
        ObjectNode nested = completeSourceDocument(ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT);
        nested.replace("subjectFingerprint", JsonNodeFactory.instance.objectNode());
        assertThatThrownBy(() -> ProductionAuditCodec.canonicalSourceDocument(
                        ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT, nested))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void anOversizedSourceDocumentIsRefused() {
        ObjectNode oversized = completeSourceDocument(ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT);
        oversized.put("recordId", "r".repeat(ProductionAuditCodec.MAX_SOURCE_DOCUMENT_LENGTH));
        assertThatThrownBy(() -> ProductionAuditCodec.canonicalSourceDocument(
                        ProductionAuditEventType.RUNTIME_OUTBOX_AUDIT, oversized))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void anOversizedEventIdentifierIsRefused() {
        ProductionAuditEntry base = ProductionAuditTestFixtures.runtimeEntry();
        assertThatThrownBy(() -> new ProductionAuditEntry(
                        base.type(),
                        "e".repeat(ProductionAuditCodec.MAX_EVENT_ID_LENGTH + 1),
                        base.logicalKey(),
                        base.tenantFingerprint(),
                        base.programFingerprint(),
                        base.resourceFingerprint(),
                        base.dispositionCode(),
                        base.reasonCode(),
                        base.actionCode(),
                        base.topicCode(),
                        base.sourceHash(),
                        base.sourceDocument(),
                        base.occurredAt()))
                .isInstanceOf(ProductionAuditException.class)
                .satisfies(failure -> assertThat(((ProductionAuditException) failure).code())
                        .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CODEC_INVALID));
    }

    @Test
    void aMalformedCodeIsRefused() {
        ProductionAuditEntry base = ProductionAuditTestFixtures.runtimeEntry();
        assertThatThrownBy(() -> new ProductionAuditEntry(
                        base.type(),
                        base.eventId(),
                        base.logicalKey(),
                        base.tenantFingerprint(),
                        base.programFingerprint(),
                        base.resourceFingerprint(),
                        base.dispositionCode(),
                        "not-a-machine-code",
                        base.actionCode(),
                        base.topicCode(),
                        base.sourceHash(),
                        base.sourceDocument(),
                        base.occurredAt()))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void aTopicCodeIsBoundedByItsOwnClosedShape() {
        assertThat(ProductionAuditTestFixtures.runtimeEntry().topicCode()).isEqualTo(RuntimeVersions.AUDIT_TOPIC);
        ProductionAuditEntry base = ProductionAuditTestFixtures.runtimeEntry();
        assertThatThrownBy(() -> new ProductionAuditEntry(
                        base.type(),
                        base.eventId(),
                        base.logicalKey(),
                        base.tenantFingerprint(),
                        base.programFingerprint(),
                        base.resourceFingerprint(),
                        base.dispositionCode(),
                        base.reasonCode(),
                        base.actionCode(),
                        "Not A Topic",
                        base.sourceHash(),
                        base.sourceDocument(),
                        base.occurredAt()))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void noCanonicalDocumentCarriesARawIdentifier() {
        List<String> rawIdentifiers = List.of(
                ProductionAuditTestFixtures.TENANT,
                ProductionAuditTestFixtures.PROGRAM,
                ProductionAuditTestFixtures.USER,
                ProductionAuditTestFixtures.DATA_SCOPE,
                "subject-1",
                "execution-1");
        for (ProductionAuditEntry entry : ProductionAuditTestFixtures.allEntries()) {
            String document = entry.canonicalDocument();
            for (String raw : rawIdentifiers) {
                assertThat(document)
                        .as("no raw identifier in the canonical document of " + entry.type())
                        .doesNotContain(raw);
            }
        }
    }

    @Test
    void theDomainVocabularyIsClosed() {
        assertThat(ProductionAuditDomain.values())
                .extracting(Enum::name)
                .containsExactly("SECURITY", "CONVERSATION", "LIFECYCLE", "RUNTIME", "ACTION");
        assertThatThrownBy(() -> ProductionAuditDomain.fromWireName("security"))
                .isInstanceOf(ProductionAuditException.class);
        assertThatThrownBy(() -> ProductionAuditEventType.fromWireName("SECURITY"))
                .isInstanceOf(ProductionAuditException.class);
    }

    @Test
    void everyPortKeepsOneClosedTypeAndFormatVersion() {
        assertThat(ProductionAuditEventType.values())
                .extracting(ProductionAuditEventType::wireName)
                .containsExactly(
                        "SECURITY_DECISION",
                        "CONVERSATION_TURN",
                        "LIFECYCLE_COMMAND",
                        "RUNTIME_OUTBOX_AUDIT",
                        "ACTION_OUTBOX_AUDIT");
        for (ProductionAuditEventType type : ProductionAuditEventType.values()) {
            assertThat(type.formatVersion()).isEqualTo("1.0");
            assertThat(type.sourceFields()).isNotEmpty();
        }
    }

    @Test
    void thePreimageDomainSeparatesTheFingerprintFromOtherHashDomains() {
        assertThat(ProductionAuditFingerprint.preimage("tenant", "tenant-1"))
                .isNotEqualTo(ProductionAuditFingerprint.preimage("program", "tenant-1"));
        assertThat(ProductionAuditFingerprint.of("tenant", "tenant-1"))
                .isNotEqualTo(ContentHash.sha256("tenant-1".getBytes(StandardCharsets.UTF_8)));
        assertThat(ProductionAuditFingerprint.ofNullable("tenant", null)).isNull();
    }

    @Test
    void anEntryCarriesOnlyTheTrustedInstantOfItsSource() {
        ProductionAuditEntry entry = ProductionAuditTestFixtures.runtimeEntry();
        assertThat(entry.occurredAt()).isEqualTo(ProductionAuditTestFixtures.OCCURRED_AT);
        assertThat(entry.canonicalDocument()).contains(ProductionAuditTestFixtures.OCCURRED_AT.toString());
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> names(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ObjectNode completeSourceDocument(ProductionAuditEventType type) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (String field : type.sourceFields()) {
            node.put(field, "value");
        }
        return node;
    }

    private static String flipOneHexDigit(String document) {
        int marker = document.indexOf(LOGICAL_KEY_PREFIX);
        int valueStart = marker + LOGICAL_KEY_PREFIX.length() + "sha256:".length();
        char original = document.charAt(valueStart);
        char replacement = original == 'f' ? 'e' : 'f';
        return document.substring(0, valueStart) + replacement + document.substring(valueStart + 1);
    }
}