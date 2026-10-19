package com.company.campaign.builder.refine;

import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.value.MoneyValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirements of section 1 and 4: the strict UTF-8/JSON patch codec round-trips every operation deterministically
 * and rejects duplicate, unknown, missing and null fields, alternate casing, malformed and oversized input,
 * duplicate operation ids, invalid node ids, index-like paths, raw array-index JSON Patch, and every forged
 * trusted, Metadata, SQL, backend, sample, Save/Publish or executable field.
 */
class SegmentRefinementPatchCodecTest {

    private static final SegmentRefinementPatchCodec CODEC = new SegmentRefinementPatchCodec();

    private static SegmentDsl base() {
        return RefinementTestFixtures.baseDocument();
    }

    private static String baseHash() {
        return base().editableDslHash().value();
    }

    private static String patch(String operationsJson) {
        return "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"" + baseHash()
                + "\",\"operations\":" + operationsJson + "}";
    }

    private static void assertRejected(String code, String json) {
        assertThatThrownBy(() -> CODEC.parse(json))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(code);
    }

    // ------------------------------------------------------------------ round trip

    @Test
    void parsesEveryOperationKindIntoTheClosedTypedUnion() {
        SegmentRefinementPatch parsed = CODEC.parse(patch("[" + addNodeJson("op-1")
                + "," + replaceWindowJson("op-2")
                + ",{\"opId\":\"op-3\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}"
                + ",{\"opId\":\"op-4\",\"op\":\"MOVE_NODE\",\"nodeId\":\"n-spend\",\"parentNodeId\":\"n-root\"}]"));

        assertThat(parsed.schemaVersion().value()).isEqualTo("1.3");
        assertThat(parsed.currentDslHash()).isEqualTo(base().editableDslHash());
        assertThat(parsed.operationIds()).containsExactly("op-1", "op-2", "op-3", "op-4");
        assertThat(parsed.operations()).extracting(SegmentRefinementOperation::kind).containsExactly(
                RefinementOpKind.ADD_NODE, RefinementOpKind.REPLACE_FIELD, RefinementOpKind.REMOVE_NODE,
                RefinementOpKind.MOVE_NODE);
        assertThat(parsed.operations().get(0)).isInstanceOf(SegmentRefinementOperation.AddNode.class);
        assertThat(((SegmentRefinementOperation.AddNode) parsed.operations().get(0)).node())
                .isInstanceOf(AttributePredicate.class);
        assertThat(((SegmentRefinementOperation.ReplaceField) parsed.operations().get(1)).replacement())
                .isInstanceOf(RefinementReplacement.WindowReplacement.class);
    }

    @Test
    void roundTripsThroughCanonicalBytesAndReparsesIdentically() {
        SegmentRefinementPatch parsed = CODEC.parse(patch(
                "[" + replaceWindowJson("op-1") + "," + replaceValueJson("op-2") + "]"));

        byte[] first = CODEC.write(parsed);
        SegmentRefinementPatch reparsed = CODEC.parse(first);
        byte[] second = CODEC.write(reparsed);

        assertThat(first).isEqualTo(second);
        assertThat(reparsed).isEqualTo(parsed);
    }

    @Test
    void theFingerprintCoversEverySemanticFieldAndOperationOrder() {
        SegmentRefinementPatch left = CODEC.parse(patch(
                "[" + replaceWindowJson("op-1") + ",{\"opId\":\"op-2\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}]"));
        SegmentRefinementPatch reordered = CODEC.parse(patch(
                "[{\"opId\":\"op-2\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}," + replaceWindowJson("op-1") + "]"));
        SegmentRefinementPatch otherWindow = CODEC.parse(patch("[" + replaceWindowJson("op-1", 45) + "]"));
        SegmentRefinementPatch removeOnly = CODEC.parse(patch(
                "[{\"opId\":\"op-2\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}]"));

        assertThat(left.patchFingerprint()).isNotEqualTo(reordered.patchFingerprint());
        assertThat(left.patchFingerprint()).isNotEqualTo(otherWindow.patchFingerprint());
        assertThat(reordered.patchFingerprint()).isNotEqualTo(removeOnly.patchFingerprint());
        assertThat(left.patchFingerprint()).isEqualTo(
                CODEC.parse(CODEC.writeString(left)).patchFingerprint());
    }

    @Test
    void aTamperedFingerprintIsRefused() {
        SegmentRefinementPatch parsed = CODEC.parse(patch("[" + replaceWindowJson("op-1") + "]"));
        com.company.campaign.shared.canonical.ContentHash forged =
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "forged".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new SegmentRefinementPatch(
                parsed.schemaVersion(), parsed.currentDslHash(), parsed.operations(), forged))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(SegmentRefinementErrorCodes.FINGERPRINT_MISMATCH);

        assertRejected(SegmentRefinementErrorCodes.FINGERPRINT_MISMATCH,
                "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"" + baseHash() + "\",\"operations\":["
                        + replaceWindowJson("op-1") + "],\"patchFingerprint\":\"" + forged.value() + "\"}");
    }

    // ------------------------------------------------------------------ structural strictness

    @Test
    void rejectsADuplicateJsonKey() {
        assertRejected(SegmentRefinementErrorCodes.JSON_DUPLICATE_KEY,
                "{\"schemaVersion\":\"1.3\",\"schemaVersion\":\"1.3\",\"currentDslHash\":\"" + baseHash()
                        + "\",\"operations\":[" + replaceWindowJson("op-1") + "]}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Unknown root members.
            "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"HASH\",\"operations\":[],\"mode\":\"EDIT\"}",
            // Missing required members.
            "{\"currentDslHash\":\"HASH\",\"operations\":[]}",
            "{\"schemaVersion\":\"1.3\",\"operations\":[]}",
            "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"HASH\"}",
            // Present but null.
            "{\"schemaVersion\":null,\"currentDslHash\":\"HASH\",\"operations\":[]}",
            "{\"schemaVersion\":\"1.3\",\"currentDslHash\":null,\"operations\":[]}",
            "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"HASH\",\"operations\":null}",
            // Empty or oversized operation list.
            "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"HASH\",\"operations\":[]}",
            // Alternate casing.
            "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"HASH\",\"Operations\":[]}",
            // Malformed and non-object documents.
            "{",
            "[]",
            "\"patch\""
    })
    void rejectsMalformedOrNonConformingEnvelopes(String json) {
        assertThatThrownBy(() -> CODEC.parse(json.replace("HASH", baseHash())))
                .isInstanceOf(SegmentRefinementException.class);
    }

    @Test
    void rejectsAnUnknownSchemaVersion() {
        assertRejected(SegmentRefinementErrorCodes.SCHEMA_VERSION_UNSUPPORTED,
                patch("[" + replaceWindowJson("op-1") + "]").replace("\"1.3\"", "\"1.2\""));
    }

    @Test
    void rejectsAnInvalidBaseHash() {
        assertRejected(SegmentRefinementErrorCodes.BASE_HASH_MISMATCH,
                "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"not-a-hash\",\"operations\":["
                        + replaceWindowJson("op-1") + "]}");
    }

    @Test
    void rejectsAnOperationWithoutItsDiscriminatorOrItsIdentifier() {
        assertRejected(SegmentRefinementErrorCodes.MISSING_FIELD,
                patch("[{\"opId\":\"op-1\",\"nodeId\":\"n-tier\"}]"));
        assertRejected(SegmentRefinementErrorCodes.MISSING_FIELD,
                patch("[{\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}]"));
    }

    @Test
    void rejectsAlternateOperationCasing() {
        assertRejected(SegmentRefinementErrorCodes.OPERATION_CASING_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"add_node\",\"parentNodeId\":\"n-root\",\"node\":"
                        + subtreeJson() + "}]"));
        assertRejected(SegmentRefinementErrorCodes.OPERATION_CASING_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"Remove_Node\",\"nodeId\":\"n-tier\"}]"));
    }

    @Test
    void rejectsAnUnknownOperation() {
        assertRejected(SegmentRefinementErrorCodes.UNKNOWN_OPERATION,
                patch("[{\"opId\":\"op-1\",\"op\":\"SET_FIELD\",\"nodeId\":\"n-tier\"}]"));
    }

    @Test
    void rejectsDuplicateOperationIdentifiers() {
        assertRejected(SegmentRefinementErrorCodes.DUPLICATE_OPERATION_ID,
                patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"},"
                        + "{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-spend\"}]"));
    }

    @Test
    void rejectsTooManyOperations() {
        StringBuilder operations = new StringBuilder("[");
        for (int index = 0; index < RefinementLimits.MAX_OPERATIONS + 1; index++) {
            if (index > 0) {
                operations.append(',');
            }
            operations.append("{\"opId\":\"op-").append(index)
                    .append("\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}");
        }
        assertRejected(SegmentRefinementErrorCodes.LIMIT_EXCEEDED, patch(operations.append(']').toString()));
    }

    @Test
    void rejectsOversizedAndNonUtf8Input() {
        String oversized = patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}]")
                + " ".repeat(RefinementLimits.MAX_INPUT_BYTES);
        assertRejected(SegmentRefinementErrorCodes.LIMIT_EXCEEDED, oversized);

        byte[] valid = patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}]")
                .getBytes(StandardCharsets.UTF_8);
        byte[] broken = valid.clone();
        broken[5] = (byte) 0xC3;
        assertThatThrownBy(() -> CODEC.parse(broken))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(SegmentRefinementErrorCodes.JSON_MALFORMED);

        byte[] unpaired = {'{', '"', 'x', '"', ':', '"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"', '}'};
        assertThatThrownBy(() -> CODEC.parse(unpaired)).isInstanceOf(SegmentRefinementException.class);
    }

    // ------------------------------------------------------------------ stable identity only

    @Test
    void rejectsARawArrayIndexJsonPatchDocument() {
        assertRejected(SegmentRefinementErrorCodes.JSON_PATCH_REJECTED,
                "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"" + baseHash() + "\",\"operations\":["
                        + "{\"op\":\"add\",\"path\":\"/predicate/children/0\",\"value\":{}}]}");
        assertRejected(SegmentRefinementErrorCodes.JSON_PATCH_REJECTED,
                patch("[{\"op\":\"add\",\"opId\":\"op-1\",\"path\":\"/children/0\",\"value\":{}}]"));
        assertRejected(SegmentRefinementErrorCodes.JSON_PATCH_REJECTED,
                patch("[{\"opId\":\"op-1\",\"op\":\"move\",\"from\":\"/children/0\",\"path\":\"/children/1\"}]"));
    }

    @Test
    void rejectsNumericAndPointerNodeIdentities() {
        assertRejected(SegmentRefinementErrorCodes.INDEX_PATH_REJECTED,
                patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"0\"}]"));
        assertRejected(SegmentRefinementErrorCodes.INDEX_PATH_REJECTED,
                patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"/children/0\"}]"));
        assertRejected(SegmentRefinementErrorCodes.INDEX_PATH_REJECTED,
                patch("[{\"opId\":\"op-1\",\"op\":\"MOVE_NODE\",\"nodeId\":\"n-tier\",\"parentNodeId\":\"n-root\","
                        + "\"beforeNodeId\":\"2\"}]"));
        assertRejected(SegmentRefinementErrorCodes.NODE_ID_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"not a node id\"}]"));
        assertRejected(SegmentRefinementErrorCodes.OPERATION_ID_INVALID,
                patch("[{\"opId\":\"not an id\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}]"));
        assertRejected(SegmentRefinementErrorCodes.INDEX_PATH_REJECTED,
                patch("[{\"opId\":\"1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\"}]"));
    }

    @Test
    void rejectsOperationIdentifiersWithAnArrayIndexMemberName() {
        assertRejected(SegmentRefinementErrorCodes.INDEX_PATH_REJECTED,
                patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\",\"0\":\"ignored\"}]"));
    }

    // ------------------------------------------------------------------ forged trusted inputs

    @ParameterizedTest
    @ValueSource(strings = {
            "tenantId", "tenant_id", "TENANT-ID", "programId", "userId", "dataScopeId", "permissions",
            "roles", "asOf", "timezone", "locale", "reportingCurrency", "dataWatermark", "fixtureVersion",
            "metadataSnapshotId", "metadataSnapshotVersion", "metadataContentHash", "sql", "query",
            "jdbc", "datasource", "backend", "storage", "catalog", "fixture", "includeSample", "presentation",
            "publish", "save", "apply", "runtime", "script", "executable", "message", "utterance"
    })
    void rejectsEveryForgedTrustedPersistenceOrExecutableField(String member) {
        assertRejected(SegmentRefinementErrorCodes.FORBIDDEN_FIELD,
                "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"" + baseHash() + "\",\"operations\":["
                        + replaceWindowJson("op-1") + "],\"" + member + "\":\"x\"}");
        assertRejected(SegmentRefinementErrorCodes.FORBIDDEN_FIELD,
                patch("[{\"opId\":\"op-1\",\"op\":\"REMOVE_NODE\",\"nodeId\":\"n-tier\",\"" + member + "\":\"x\"}]"));
    }

    @Test
    void rejectsAModelSuppliedMessageInsteadOfAnOperation() {
        assertRejected(SegmentRefinementErrorCodes.FORBIDDEN_FIELD,
                "{\"schemaVersion\":\"1.3\",\"currentDslHash\":\"" + baseHash()
                        + "\",\"message\":\"把90天改成30天\",\"operations\":[]}");
    }

    // ------------------------------------------------------------------ closed field/value union

    @Test
    void rejectsAnUnknownReplacementField() {
        assertRejected(SegmentRefinementErrorCodes.FIELD_UNKNOWN,
                patch("[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\",\"field\":\"children\","
                        + "\"value\":{}}]"));
        assertRejected(SegmentRefinementErrorCodes.FIELD_UNKNOWN,
                patch("[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\",\"field\":\"WINDOW\","
                        + "\"value\":{\"type\":\"LIFETIME\"}}]"));
    }

    @Test
    void rejectsAReplacementValueThatDoesNotMatchItsFieldType() {
        assertRejected(SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\",\"field\":\"window\","
                        + "\"value\":{\"kind\":\"STRING\",\"value\":\"thirty\"}}]"));
        assertRejected(SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\",\"field\":\"operator\","
                        + "\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"}}]"));
        assertRejected(SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\",\"field\":\"value\","
                        + "\"value\":{\"type\":\"LIFETIME\"}}]"));
    }

    @Test
    void rejectsAnUnknownFieldInsideTheAddedSubtree() {
        assertRejected(SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"ADD_NODE\",\"parentNodeId\":\"n-root\",\"node\":"
                        + "{\"nodeId\":\"n-new\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                        + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"},"
                        + "\"trustedContext\":\"x\"}}]"));
    }

    @Test
    void rejectsAMalformedTimeWindowReplacement() {
        assertRejected(SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                patch("[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\",\"field\":\"window\","
                        + "\"value\":{\"type\":\"ROLLING\",\"amount\":30}}]"));
    }

    @Test
    void anAbsentSiblingAnchorIsDifferentFromAPresentOne() {
        SegmentRefinementPatch appended = CODEC.parse(patch(
                "[{\"opId\":\"op-1\",\"op\":\"ADD_NODE\",\"parentNodeId\":\"n-root\",\"node\":"
                        + subtreeJson() + "}]"));
        SegmentRefinementPatch anchored = CODEC.parse(patch(
                "[{\"opId\":\"op-1\",\"op\":\"ADD_NODE\",\"parentNodeId\":\"n-root\",\"beforeNodeId\":\"n-tier\","
                        + "\"node\":" + subtreeJson() + "}]"));

        assertThat(((SegmentRefinementOperation.AddNode) appended.operations().get(0)).beforeNodeId()).isNull();
        assertThat(((SegmentRefinementOperation.AddNode) anchored.operations().get(0)).beforeNodeId())
                .isEqualTo("n-tier");
        assertThat(appended.patchFingerprint()).isNotEqualTo(anchored.patchFingerprint());

        assertRejected(SegmentRefinementErrorCodes.NULL_FIELD,
                patch("[{\"opId\":\"op-1\",\"op\":\"ADD_NODE\",\"parentNodeId\":\"n-root\",\"beforeNodeId\":null,"
                        + "\"node\":" + subtreeJson() + "}]"));
    }

    @Test
    void everyOperationCarriesItsStableIdentifierAndTypedKind() {
        SegmentRefinementPatch parsed = CODEC.parse(patch("[" + replaceWindowJson("op-1") + "]"));
        SegmentRefinementOperation operation = parsed.operations().get(0);

        assertThat(operation.operationId()).isEqualTo("op-1");
        assertThat(operation.kind()).isEqualTo(RefinementOpKind.REPLACE_FIELD);
        assertThat(RefinementOpKind.ordered()).containsExactly(
                RefinementOpKind.ADD_NODE, RefinementOpKind.REMOVE_NODE,
                RefinementOpKind.REPLACE_FIELD, RefinementOpKind.MOVE_NODE);
    }

    @Test
    void aMetricValueReplacementKeepsItsTypedMoneyOperand() {
        SegmentRefinementPatch parsed = CODEC.parse(patch(
                "[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\",\"field\":\"value\","
                        + "\"value\":{\"kind\":\"MONEY\",\"amount\":\"250.00\",\"currency\":\"CNY\"}}]"));
        RefinementReplacement.ConditionReplacement replacement =
                (RefinementReplacement.ConditionReplacement)
                        ((SegmentRefinementOperation.ReplaceField) parsed.operations().get(0)).replacement();

        assertThat(replacement.value()).isEqualTo(new MoneyValue("250.00", "CNY"));
        assertThat(replacement.value()).isNotEqualTo(new DecimalValue("250.00"));
    }

    @Test
    void aParsedPatchNeverRetainsARawJsonTreeOrOpenMap() {
        SegmentRefinementPatch parsed = CODEC.parse(patch("[" + replaceWindowJson("op-1") + "]"));
        for (SegmentRefinementOperation operation : parsed.operations()) {
            assertThat(operation).isInstanceOf(SegmentRefinementOperation.class);
            assertThat(operation.operationId()).isInstanceOf(String.class);
        }
        assertThat(((SegmentRefinementOperation.ReplaceField) parsed.operations().get(0)).replacement())
                .isInstanceOf(RefinementReplacement.WindowReplacement.class);
    }

    @Test
    void theBaseDocumentUnderTestIsAValidEditableT05Document() {
        assertThat(base().predicate()).isInstanceOf(GroupPredicate.class);
        assertThat(((GroupPredicate) base().predicate()).children()).hasSize(2);
        assertThat(((GroupPredicate) base().predicate()).children().get(1)).isInstanceOf(MetricPredicate.class);
        assertThat(new com.company.campaign.segment.dsl.codec.SegmentDslCodec()
                .parse(new com.company.campaign.segment.dsl.codec.SegmentDslCodec().writeString(base())))
                .isEqualTo(base());
        assertThat(ComparisonOperator.GREATER_THAN.name()).isEqualTo("GREATER_THAN");
    }

    // ------------------------------------------------------------------ helpers

    private static String subtreeJson() {
        return "{\"nodeId\":\"n-new\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"}}";
    }

    private static String addNodeJson(String operationId) {
        return "{\"opId\":\"" + operationId + "\",\"op\":\"ADD_NODE\",\"parentNodeId\":\"n-root\","
                + "\"beforeNodeId\":\"n-tier\",\"node\":" + subtreeJson() + "}";
    }

    private static String replaceWindowJson(String operationId) {
        return replaceWindowJson(operationId, 30);
    }

    private static String replaceWindowJson(String operationId, int days) {
        return "{\"opId\":\"" + operationId + "\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\","
                + "\"field\":\"window\",\"value\":{\"type\":\"ROLLING\",\"amount\":" + days + ",\"unit\":\"DAY\"}}";
    }

    private static String replaceValueJson(String operationId) {
        return "{\"opId\":\"" + operationId + "\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"n-spend\","
                + "\"field\":\"value\",\"value\":{\"kind\":\"MONEY\",\"amount\":\"500.00\",\"currency\":\"CNY\"}}";
    }
}