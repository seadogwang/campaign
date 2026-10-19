package com.company.campaign.segment.dsl;

import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.testsupport.SegmentDslFixtures;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Strict rejection behavior of the codec: unknown and duplicate fields, unknown discriminators, malformed
 * Metadata bindings, alternate NOT shapes, the operator/value matrix, value lexical rules, windows, limits
 * and the explicit absence of {@code value:any} and Existing Audience Reference.
 */
class SegmentDslCodecStrictnessTest {

    private static final String METADATA_HASH = SegmentDslFixtures.binding().contentHash().value();
    private static final String PLACEHOLDER = SegmentDslFixtures.PLACEHOLDER_HASH;

    private static final String EQUAL_GOLD =
            "{\"nodeId\":\"n-a\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                    + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"}}";

    private final SegmentDslCodec codec = new SegmentDslCodec();

    private static String root(String predicateJson, String... extraFields) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"schemaVersion\":\"1.3\",");
        json.append("\"subject\":\"MEMBER\",");
        json.append("\"metadataSnapshotId\":\"metadata-17\",");
        json.append("\"metadataSnapshotVersion\":\"1\",");
        json.append("\"metadataSchemaVersion\":\"1.0\",");
        json.append("\"metadataContentHash\":\"").append(METADATA_HASH).append("\",");
        json.append("\"predicate\":").append(predicateJson).append(',');
        json.append("\"editableDslHash\":\"").append(PLACEHOLDER).append('"');
        for (String extra : extraFields) {
            json.append(',').append(extra);
        }
        return json.append('}').toString();
    }

    private static String attribute(String operator, String valueJson) {
        return "{\"nodeId\":\"n-a\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                + "\"operator\":\"" + operator + "\"" + (valueJson == null ? "" : ",\"value\":" + valueJson) + "}";
    }

    private static String metric(String operator, String windowJson, String valueJson) {
        return "{\"nodeId\":\"n-m\",\"type\":\"METRIC\",\"metricId\":\"order.count\","
                + "\"window\":" + windowJson + ",\"operator\":\"" + operator + "\""
                + (valueJson == null ? "" : ",\"value\":" + valueJson) + "}";
    }

    private static String nestedGroups(int depth) {
        String predicate = EQUAL_GOLD;
        for (int index = 0; index < depth; index++) {
            predicate = "{\"nodeId\":\"n-" + index + "\",\"type\":\"ALL\",\"children\":[" + predicate + "]}";
        }
        return predicate;
    }

    @Test
    void rejectsUnknownFieldsAtEveryObjectLevel() {
        assertFailsWithCode(
                () -> codec.parse(root(EQUAL_GOLD, "\"extra\":1")),
                SegmentDslErrorCodes.UNKNOWN_FIELD);
        assertFailsWithCode(
                () -> codec.parse(root(
                        "{\"nodeId\":\"n-a\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"},\"extra\":1}")),
                SegmentDslErrorCodes.UNKNOWN_FIELD);
        assertFailsWithCode(
                () -> codec.parse(root(
                        "{\"nodeId\":\"n-a\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\",\"extra\":1}}")),
                SegmentDslErrorCodes.UNKNOWN_FIELD);
        assertFailsWithCode(
                () -> codec.parse(root(metric("EQUAL", "{\"type\":\"LIFETIME\",\"amount\":1}",
                        "{\"kind\":\"INTEGER\",\"value\":1}"))),
                SegmentDslErrorCodes.UNKNOWN_FIELD);
    }

    @Test
    void rejectsDuplicateJsonKeysAndMalformedJson() {
        assertFailsWithCode(() -> codec.parse("{\"a\":1,\"a\":2}"), SegmentDslErrorCodes.JSON_DUPLICATE_KEY);
        assertFailsWithCode(() -> codec.parse("{not json"), SegmentDslErrorCodes.JSON_MALFORMED);
        assertFailsWithCode(() -> codec.parse(""), SegmentDslErrorCodes.JSON_MALFORMED);
    }

    @Test
    void rejectsUnknownDiscriminators() {
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-a\",\"type\":\"MAYBE\",\"children\":[" + EQUAL_GOLD + "]}")),
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"kind\":\"OBJECT\",\"value\":1}"))),
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR);
        assertFailsWithCode(
                () -> codec.parse(root(metric("EQUAL", "{\"type\":\"CALENDAR\"}",
                        "{\"kind\":\"INTEGER\",\"value\":1}"))),
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("LIKE", "{\"kind\":\"ENUM\",\"value\":\"GOLD\"}"))),
                SegmentDslErrorCodes.UNKNOWN_OPERATOR);
    }

    @Test
    void rejectsUnsupportedSegmentSchemaVersions() {
        for (String version : new String[] {"1.2", "2.0", "1.3.0", "x", ""}) {
            String json = root(EQUAL_GOLD).replace("\"schemaVersion\":\"1.3\"", "\"schemaVersion\":\"" + version + "\"");
            assertFailsWithCode(() -> codec.parse(json), SegmentDslErrorCodes.SCHEMA_VERSION_UNSUPPORTED);
        }
    }

    @Test
    void rejectsMalformedMetadataBindings() {
        assertFailsWithCode(
                () -> codec.parse(root(EQUAL_GOLD).replace("\"metadataSnapshotVersion\":\"1\",", "")),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(EQUAL_GOLD).replace(METADATA_HASH, "sha256:not-a-hash")),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(EQUAL_GOLD).replace("\"metadataSchemaVersion\":\"1.0\"",
                        "\"metadataSchemaVersion\":\"2.0\"")),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(EQUAL_GOLD).replace("\"metadataSnapshotId\":\"metadata-17\"",
                        "\"metadataSnapshotId\":\"bad id\"")),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(EQUAL_GOLD).replace("\"editableDslHash\":\"" + PLACEHOLDER + "\"",
                        "\"editableDslHash\":\"nope\"")),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(EQUAL_GOLD).replace("\"subject\":\"MEMBER\"", "\"subject\":\"ALIEN\"")),
                SegmentDslErrorCodes.SUBJECT_INVALID);
    }

    @Test
    void rejectsAlternateNotShapes() {
        // A NOT must use the singular child field; the group children shape is an unknown field here.
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-not\",\"type\":\"NOT\",\"children\":[" + EQUAL_GOLD + "]}")),
                SegmentDslErrorCodes.UNKNOWN_FIELD);
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-not\",\"type\":\"NOT\"}")),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-not\",\"type\":\"NOT\",\"child\":[" + EQUAL_GOLD + "]}")),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-not\",\"type\":\"NOT\",\"child\":null}")),
                SegmentDslErrorCodes.SHAPE_INVALID);
    }

    @Test
    void rejectsNullPredicateAndNullChildren() {
        assertFailsWithCode(() -> codec.parse(root("null")), SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-all\",\"type\":\"ALL\",\"children\":[" + EQUAL_GOLD + ",null]}")),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-all\",\"type\":\"ALL\",\"children\":[]}")),
                SegmentDslErrorCodes.SHAPE_INVALID);
    }

    @Test
    void enforcesTheOperatorValueMatrixOnTheWire() {
        assertFailsWithCode(
                () -> codec.parse(root(attribute("IS_NULL", "{\"kind\":\"ENUM\",\"value\":\"GOLD\"}"))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("IS_NOT_NULL", "null"))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", null))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL",
                        "{\"kind\":\"LIST\",\"items\":[{\"kind\":\"ENUM\",\"value\":\"GOLD\"}]}"))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("IN", "{\"kind\":\"ENUM\",\"value\":\"GOLD\"}"))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("BETWEEN",
                        "{\"kind\":\"LIST\",\"items\":[{\"kind\":\"INTEGER\",\"value\":1}]}"))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("BETWEEN", null))),
                SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID);
    }

    @Test
    void rejectsMalformedTypedValues() {
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"value\":\"GOLD\"}"))),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "[1,2]"))),
                SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"kind\":\"INTEGER\",\"value\":3.0}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"kind\":\"INTEGER\",\"value\":1e3}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL",
                        "{\"kind\":\"INTEGER\",\"value\":99999999999999999999999}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"kind\":\"BOOLEAN\",\"value\":\"true\"}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"kind\":\"DECIMAL\",\"value\":\"1E3\"}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL",
                        "{\"kind\":\"DATETIME\",\"value\":\"2026-01-01T00:00:00\"}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"kind\":\"MONEY\",\"amount\":\"1\",\"currency\":\"cny\"}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
    }

    @Test
    void rejectsInvalidListAndRangeStructures() {
        assertFailsWithCode(
                () -> codec.parse(root(attribute("IN", "{\"kind\":\"LIST\",\"items\":[]}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("IN", "{\"kind\":\"LIST\",\"items\":[null]}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("IN",
                        "{\"kind\":\"LIST\",\"items\":[{\"kind\":\"LIST\",\"items\":[{\"kind\":\"INTEGER\",\"value\":1}]}]}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("BETWEEN",
                        "{\"kind\":\"RANGE\",\"lower\":null,\"upper\":{\"kind\":\"INTEGER\",\"value\":9},"
                                + "\"includeLower\":true,\"includeUpper\":false}"))),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("BETWEEN",
                        "{\"kind\":\"RANGE\",\"lower\":{\"kind\":\"INTEGER\",\"value\":1},"
                                + "\"upper\":{\"kind\":\"INTEGER\",\"value\":9},\"includeLower\":true}"))),
                SegmentDslErrorCodes.SHAPE_INVALID);
    }

    @Test
    void rejectsInvalidWindows() {
        assertFailsWithCode(
                () -> codec.parse(root(metric("EQUAL", "{\"type\":\"ROLLING\",\"amount\":0,\"unit\":\"DAY\"}",
                        "{\"kind\":\"INTEGER\",\"value\":1}"))),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(metric("EQUAL", "{\"type\":\"ROLLING\",\"amount\":30,\"unit\":\"MONTH\"}",
                        "{\"kind\":\"INTEGER\",\"value\":1}"))),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(metric("EQUAL",
                        "{\"type\":\"FIXED\",\"start\":\"2026-02-01T00:00:00Z\",\"end\":\"2026-01-01T00:00:00Z\"}",
                        "{\"kind\":\"INTEGER\",\"value\":1}"))),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> codec.parse(root(metric("EQUAL",
                        "{\"type\":\"FIXED\",\"start\":\"2026-01-01T00:00:00\",\"end\":\"2026-02-01T00:00:00Z\"}",
                        "{\"kind\":\"INTEGER\",\"value\":1}"))),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
    }

    @Test
    void rejectsDuplicateNodeIdsAnywhereInTheWireTree() {
        String duplicated = "{\"nodeId\":\"dup\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"}}";
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-root\",\"type\":\"ALL\",\"children\":["
                        + duplicated + "," + duplicated + "]}")),
                SegmentDslErrorCodes.DUPLICATE_NODE_ID);
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"dup\",\"type\":\"EXISTS\",\"relationId\":\"member.orders\","
                        + "\"window\":{\"type\":\"LIFETIME\"},\"predicate\":" + duplicated + "}")),
                SegmentDslErrorCodes.DUPLICATE_NODE_ID);
    }

    @Test
    void enforcesByteAndNestingLimits() {
        assertFailsWithCode(
                () -> codec.parse("{" + " ".repeat(SegmentDslLimits.MAX_INPUT_BYTES + 1)),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
        assertFailsWithCode(
                () -> codec.parse(root(nestedGroups(SegmentDslLimits.MAX_PREDICATE_DEPTH + 1))),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);

        // Exactly at the limit is accepted; one level deeper is refused by the same bound.
        assertThat(SegmentDslFixtures.publish(deepPredicate(SegmentDslLimits.MAX_PREDICATE_DEPTH - 1)).editableDslHash())
                .isNotNull();
        assertFailsWithCode(
                () -> SegmentDslFixtures.publish(deepPredicate(SegmentDslLimits.MAX_PREDICATE_DEPTH)),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
    }

    /** Wraps one leaf attribute in the requested number of ALL groups, programmatically. */
    private static com.company.campaign.segment.dsl.predicate.Predicate deepPredicate(int wrappers) {
        com.company.campaign.segment.dsl.predicate.Predicate predicate = SegmentDslFixtures.tierEqualsGold("n-leaf");
        for (int index = 0; index < wrappers; index++) {
            predicate = new com.company.campaign.segment.dsl.predicate.GroupPredicate(
                    "n-wrap-" + index, com.company.campaign.segment.dsl.predicate.PredicateType.ALL,
                    java.util.List.of(predicate));
        }
        return predicate;
    }

    @Test
    void enforcesIndividualTextAndCollectionSizeBounds() {
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL",
                        "{\"kind\":\"STRING\",\"value\":\"" + "x".repeat(SegmentDslLimits.MAX_STRING_LENGTH + 1) + "\"}"))),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL",
                        "{\"kind\":\"DECIMAL\",\"value\":\"1." + "0".repeat(SegmentDslLimits.MAX_DECIMAL_SCALE + 1) + "\"}"))),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);

        // A LIST one item over the bound is refused; one at the bound is accepted by the value contract.
        String item = "{\"kind\":\"INTEGER\",\"value\":1}";
        String oversized = "{\"kind\":\"LIST\",\"items\":["
                + String.join(",", java.util.Collections.nCopies(SegmentDslLimits.MAX_LIST_ITEMS + 1, item)) + "]}";
        assertFailsWithCode(
                () -> codec.parse(root(attribute("IN", oversized))),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
        assertThat(new com.company.campaign.segment.dsl.value.ListValue(
                java.util.stream.IntStream.range(0, SegmentDslLimits.MAX_LIST_ITEMS)
                        .mapToObj(index -> (com.company.campaign.segment.dsl.value.ScalarValue)
                                new com.company.campaign.segment.dsl.value.IntegerValue(index))
                        .toList()).items())
                .hasSize(SegmentDslLimits.MAX_LIST_ITEMS);
    }

    @Test
    void neverAdmitsValueAnyUnpinnedAudienceReferenceOrRawStructures() {
        // An open value:any object without a kind discriminator is a shape failure, not a passthrough.
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "{\"some\":\"raw\",\"object\":true}"))),
                SegmentDslErrorCodes.SHAPE_INVALID);
        // A raw scalar is likewise rejected.
        assertFailsWithCode(
                () -> codec.parse(root(attribute("EQUAL", "\"GOLD\""))),
                SegmentDslErrorCodes.SHAPE_INVALID);
        // An AUDIENCE_REFERENCE without the full immutable pin is refused rather than read as a bare audience.
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-aud\",\"type\":\"AUDIENCE_REFERENCE\",\"audienceId\":\"a-1\"}")),
                SegmentDslErrorCodes.SHAPE_INVALID);
        // A misspelt discriminator is never mapped onto the reference subtype.
        assertFailsWithCode(
                () -> codec.parse(root("{\"nodeId\":\"n-aud\",\"type\":\"audience_reference\","
                        + "\"audienceId\":\"a-1\",\"versionId\":\"v-1\",\"versionHash\":\""
                        + SegmentDslFixtures.PLACEHOLDER_HASH + "\"}")),
                SegmentDslErrorCodes.UNKNOWN_DISCRIMINATOR);
        // Even a known type cannot smuggle an audience reference field.
        assertFailsWithCode(
                () -> codec.parse(root(
                        "{\"nodeId\":\"n-a\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"value\":\"GOLD\"},"
                                + "\"audienceRef\":\"a-1\"}")),
                SegmentDslErrorCodes.UNKNOWN_FIELD);
    }

    @Test
    void rejectsMalformedUtf8BytesInsteadOfReplacingThem() {
        // Each raw sequence would decode to U+FFFD under a replacing decoder, silently turning an invalid
        // payload into a different (here, structurally plausible) document. A reporting decoder must refuse.
        List<byte[]> malformed = List.of(
                new byte[] {(byte) 0x80},                          // isolated continuation byte
                new byte[] {(byte) 0xBF},                          // isolated continuation byte
                new byte[] {(byte) 0xE2, (byte) 0x82},             // truncated three-byte sequence
                new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98}, // truncated four-byte sequence
                new byte[] {(byte) 0xC0, (byte) 0xAF},             // overlong encoding of '/'
                new byte[] {(byte) 0xE0, (byte) 0x80, (byte) 0xAF}, // overlong three-byte encoding
                new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, // UTF-8-encoded lone surrogate U+D800
                new byte[] {(byte) 0xFF, (byte) 0xFE});            // invalid lead bytes
        for (byte[] raw : malformed) {
            assertFailsWithCode(
                    () -> codec.parse(documentWithRawValueBytes(raw)),
                    SegmentDslErrorCodes.JSON_MALFORMED);
        }
    }

    @Test
    void rejectsJavaStringsThatCarryUnpairedSurrogates() {
        // Encoding an unpaired surrogate to UTF-8 would replace it, so it must not reach the byte decoder.
        String json = root(attribute("EQUAL", "{\"kind\":\"STRING\",\"value\":\"\uD800\"}"));
        assertFailsWithCode(() -> codec.parse(json), SegmentDslErrorCodes.JSON_MALFORMED);
    }

    @Test
    void rejectsNestedContainersFromTheDiscriminatorWithoutDescendingIntoThem() {
        // A nested LIST/RANGE is structurally inadmissible as a LIST item or RANGE bound. It must be refused
        // from its kind alone: an inner field that would otherwise fail differently is never inspected.
        assertFailsWithCode(
                () -> codec.readValue("{\"kind\":\"LIST\",\"items\":[{\"kind\":\"LIST\",\"items\":[{\"notKind\":1}]}]}"),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.readValue("{\"kind\":\"RANGE\",\"lower\":{\"kind\":\"LIST\",\"items\":[{\"notKind\":1}]},"
                        + "\"upper\":{\"kind\":\"INTEGER\",\"value\":9},\"includeLower\":true,\"includeUpper\":true}"),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.readValue("{\"kind\":\"LIST\",\"items\":[{\"kind\":\"RANGE\",\"lower\":{\"notKind\":1},"
                        + "\"upper\":{\"kind\":\"INTEGER\",\"value\":9},\"includeLower\":true,\"includeUpper\":true}]}"),
                SegmentDslErrorCodes.TYPED_VALUE_INVALID);
    }

    @Test
    void rejectsDeeplyNestedContainerJsonNearTheInputLimitWithoutExcessiveRecursion() {
        // The container nesting is deep, and the document is padded toward the input byte limit, yet the
        // rejection is immediate and stable: no StackOverflowError and no expensive descent.
        // 400 levels keeps the container depth under the JSON reader's own nesting bound while the padding
        // pushes the document toward the Segment DSL input byte limit.
        for (String kind : List.of("LIST", "RANGE")) {
            String json = paddedNestedContainer(kind, 400);
            assertThat(json.length()).isLessThanOrEqualTo(SegmentDslLimits.MAX_INPUT_BYTES);
            assertFailsWithCode(() -> codec.readValue(json), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
            assertFailsWithCode(
                    () -> codec.parse(root(attribute(kind.equals("LIST") ? "IN" : "BETWEEN", json))),
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        }
    }

    @Test
    void neverEchoesPayloadValuesFieldNamesOrRawJsonInFailures() {
        String secret = "AKIAIOSFODNN7EXAMPLE-CONFIDENTIAL-MARKER";

        // An unknown field name must not travel outward in the message, details or any wrapped cause.
        assertNoEcho(
                catchThrowable(() -> codec.parse(root(EQUAL_GOLD, "\"" + secret + "\":1"))),
                secret);

        // A malformed document whose raw JSON contains the marker must not leak the source text.
        assertNoEcho(
                catchThrowable(() -> codec.parse("{\"" + secret + "\":")),
                secret);

        // A malformed content hash, schema version or reference identifier must not echo its value either.
        assertNoEcho(
                catchThrowable(() -> codec.parse(root(EQUAL_GOLD).replace(METADATA_HASH, secret))),
                secret);
        assertNoEcho(
                catchThrowable(() -> codec.parse(
                        root(EQUAL_GOLD).replace("\"schemaVersion\":\"1.3\"", "\"schemaVersion\":\"" + secret + "\""))),
                secret);
        assertNoEcho(
                catchThrowable(() -> codec.parse(
                        root(EQUAL_GOLD).replace("\"metadataSchemaVersion\":\"1.0\"",
                                "\"metadataSchemaVersion\":\"" + secret + "\""))),
                secret);
        assertNoEcho(
                catchThrowable(() -> codec.parse(root(EQUAL_GOLD).replace("\"subject\":\"MEMBER\"",
                        "\"subject\":\"" + secret + "\""))),
                secret);

        // The unknown-field failure stays on its stable code and carries no structured value.
        Throwable unknownField = catchThrowable(() -> codec.parse(root(EQUAL_GOLD, "\"" + secret + "\":1")));
        assertThat(((ContractException) unknownField).code()).isEqualTo(SegmentDslErrorCodes.UNKNOWN_FIELD);
        assertThat(((ContractException) unknownField).details()).isEmpty();
        assertThat(unknownField.getMessage())
                .doesNotContain(secret)
                .doesNotContain("AKIA");
    }

    @Test
    void nullWritesFailWithStableCodesInsteadOfLeakingNullPointerErrors() {
        assertFailsWithCode(
                () -> codec.write(null), SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.writeString(null), SegmentDslErrorCodes.SHAPE_INVALID);
        assertFailsWithCode(
                () -> codec.writeValueString(null), SegmentDslErrorCodes.TYPED_VALUE_INVALID);
        assertFailsWithCode(
                () -> codec.writeWindowString(null), SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> codec.writePredicateString(null), SegmentDslErrorCodes.SHAPE_INVALID);

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable write :
                List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                        () -> codec.write(null),
                        () -> codec.writeString(null),
                        () -> codec.writeValueString(null),
                        () -> codec.writeWindowString(null),
                        () -> codec.writePredicateString(null))) {
            assertThat(catchThrowable(write)).isInstanceOf(SegmentDslException.class);
        }
    }

    /** A deep stack of one kind, innermost a scalar, padded toward the input byte limit. */
    private static String paddedNestedContainer(String kind, int depth) {
        String nested = "{\"kind\":\"INTEGER\",\"value\":1}";
        String template = "{\"kind\":\"" + kind + "\",\"items\":[%s]}";
        if (kind.equals("RANGE")) {
            template = "{\"kind\":\"RANGE\",\"lower\":%s,\"upper\":{\"kind\":\"INTEGER\",\"value\":9},"
                    + "\"includeLower\":true,\"includeUpper\":true}";
        }
        for (int index = 0; index < depth; index++) {
            nested = String.format(template, nested);
        }
        // Leave headroom for the surrounding document so a wrapping parse stays under the same byte limit.
        int padding = SegmentDslLimits.MAX_INPUT_BYTES - nested.length() - 1024;
        return " ".repeat(Math.max(0, padding)) + nested;
    }

    /** The full document text with raw, un-decodable bytes spliced inside a STRING operand. */
    private static byte[] documentWithRawValueBytes(byte[] raw) {
        String prefix = "{\"schemaVersion\":\"1.3\",\"subject\":\"MEMBER\",\"metadataSnapshotId\":\"metadata-17\","
                + "\"metadataSnapshotVersion\":\"1\",\"metadataSchemaVersion\":\"1.0\","
                + "\"metadataContentHash\":\"" + METADATA_HASH + "\","
                + "\"predicate\":{\"nodeId\":\"n-a\",\"type\":\"ATTRIBUTE\",\"attributeId\":\"member.tier\","
                + "\"operator\":\"EQUAL\",\"value\":{\"kind\":\"STRING\",\"value\":\"";
        String suffix = "\"}},\"editableDslHash\":\"" + PLACEHOLDER + "\"}";
        byte[] head = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] tail = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] document = new byte[head.length + raw.length + tail.length];
        System.arraycopy(head, 0, document, 0, head.length);
        System.arraycopy(raw, 0, document, head.length, raw.length);
        System.arraycopy(tail, 0, document, head.length + raw.length, tail.length);
        return document;
    }

    /** Asserts the marker appears in no outward message, detail value or wrapped cause. */
    private static void assertNoEcho(Throwable thrown, String marker) {
        assertThat(thrown).isNotNull();
        List<String> rendered = new ArrayList<>();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            rendered.add(current.getClass().getName());
            rendered.add(String.valueOf(current.getMessage()));
            if (current instanceof ContractException contractException) {
                rendered.add(String.valueOf(contractException.details()));
            }
        }
        assertThat(rendered).as("no outward text may echo the payload").noneMatch(text -> text.contains(marker));
    }
}