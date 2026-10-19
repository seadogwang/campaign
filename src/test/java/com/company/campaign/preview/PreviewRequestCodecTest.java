package com.company.campaign.preview;

import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Requirement 1 and 2: the strict whole-request codec rejects malformed UTF-8/JSON, duplicate, unknown,
 * missing and null fields, alternate casing, wrong shapes, oversized input and every attempt to inject a
 * trusted, SQL, catalog, fixture, FX or backend field — at the top level, inside {@code presentation} and
 * inside {@code segmentDsl}.
 */
class PreviewRequestCodecTest {

    private static final SegmentDslCodec DSL = new SegmentDslCodec();
    private static final PreviewRequestCodec CODEC =
            new PreviewRequestCodec(PreviewTestSupport.policy(), DSL);

    @Test
    void acceptsTheExactDocumentedRequest() {
        PreviewRequest request = CODEC.parse(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));

        assertThat(request.presentation().includeSample()).isTrue();
        assertThat(request.presentation().includeConditionStats()).isTrue();
        assertThat(request.segmentDsl().predicate().nodeId()).isEqualTo("n-tier");
    }

    @Test
    void rejectsAbsentEmptyAndOversizedBodies() {
        assertThat(code(null)).isEqualTo(PreviewErrorCodes.REQUEST_MISSING);
        assertThat(code(new byte[0])).isEqualTo(PreviewErrorCodes.REQUEST_MISSING);

        PreviewRequestCodec tiny = new PreviewRequestCodec(
                new PreviewPolicy(32, 8, 8, 8, java.time.Duration.ofMinutes(5)), DSL);
        byte[] oversized = PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true);
        assertThat(oversized.length).isGreaterThan(32);
        PreviewException failure = failureOf(tiny, oversized);
        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.REQUEST_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsMalformedUtf8() {
        byte[] invalid = new byte[] {'{', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        assertThat(code(invalid)).isEqualTo(PreviewErrorCodes.REQUEST_ENCODING_INVALID);
    }

    @Test
    void rejectsMalformedJsonAndNonObjectRoots() {
        assertThat(code("{\"segmentDsl\":".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_JSON_MALFORMED);
        assertThat(code("[1,2,3]".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_SHAPE_INVALID);
        assertThat(code("   ".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_JSON_MALFORMED);
    }

    @Test
    void rejectsDuplicateKeysAtEveryNestingLevel() {
        String dsl = DSL.writeString(PreviewTestSupport.goldDocument());
        byte[] topLevel = ("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":false,\"includeConditionStats\":true}"
                + ",\"presentation\":{\"includeSample\":false,\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(code(topLevel)).isEqualTo(PreviewErrorCodes.REQUEST_DUPLICATE_KEY);

        byte[] nested = ("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":false,\"includeSample\":true,\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(code(nested)).isEqualTo(PreviewErrorCodes.REQUEST_DUPLICATE_KEY);
    }

    @Test
    void rejectsMissingNullAndWrongShapedFields() {
        String dsl = DSL.writeString(PreviewTestSupport.goldDocument());
        assertThat(code("{\"presentation\":{\"includeSample\":false,\"includeConditionStats\":true}}"
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_MISSING_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl + "}").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_MISSING_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeConditionStats\":true}}").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_MISSING_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":false}}").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_MISSING_FIELD);

        assertThat(code(("{\"segmentDsl\":null,\"presentation\":{\"includeSample\":false,"
                + "\"includeConditionStats\":true}}").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_NULL_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl + ",\"presentation\":null}").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_NULL_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":null,\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_NULL_FIELD);

        assertThat(code(("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":\"false\",\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_SHAPE_INVALID);
        assertThat(code(("{\"segmentDsl\":[],\"presentation\":{\"includeSample\":false,"
                + "\"includeConditionStats\":true}}").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_SHAPE_INVALID);
        assertThat(code(("{\"segmentDsl\":" + dsl + ",\"presentation\":[]}").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_SHAPE_INVALID);
    }

    @Test
    void rejectsAlternateCasingOfEveryDocumentedName() {
        String dsl = DSL.writeString(PreviewTestSupport.goldDocument());
        assertThat(code(("{\"SegmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":false,\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_UNKNOWN_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl
                + ",\"Presentation\":{\"includeSample\":false,\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_UNKNOWN_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includesample\":false,\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_UNKNOWN_FIELD);
        assertThat(code(("{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":false,\"IncludeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PreviewErrorCodes.REQUEST_UNKNOWN_FIELD);
    }

    @Test
    void rejectsEveryInjectedTrustedSqlCatalogFixtureFxOrBackendFieldAtTopLevel() {
        for (String injected : INJECTED_FIELDS) {
            PreviewException failure = failureOf(withTopLevelInjection(injected));
            assertThat(failure.code()).as(injected).isEqualTo(PreviewErrorCodes.REQUEST_UNKNOWN_FIELD);
            assertNoEcho(failure, injected);
        }
    }

    @Test
    void rejectsEveryInjectedFieldInsidePresentation() {
        for (String injected : INJECTED_FIELDS) {
            PreviewException failure = failureOf(withPresentationInjection(injected));
            assertThat(failure.code()).as(injected).isEqualTo(PreviewErrorCodes.REQUEST_UNKNOWN_FIELD);
            assertNoEcho(failure, injected);
        }
    }

    @Test
    void rejectsInjectedFieldsInsideTheSegmentDslThroughTheAcceptedCodec() {
        for (String injected : INJECTED_FIELDS) {
            PreviewException failure = failureOf(withDslInjection(injected));
            // A name the DSL itself declares is a duplicate-key rejection; any other injected name is an
            // unknown-DSL-field rejection. Both are bounded, stable and non-disclosing.
            assertThat(failure.code()).as(injected).isIn(
                    PreviewErrorCodes.REQUEST_DSL_INVALID, PreviewErrorCodes.REQUEST_DUPLICATE_KEY);
            assertNoEcho(failure, injected);
        }
    }

    @Test
    void rejectsAMalformedEmbeddedDslThroughTheAcceptedCodec() {
        byte[] body = ("{\"segmentDsl\":{\"schemaVersion\":\"1.3\"},"
                + "\"presentation\":{\"includeSample\":false,\"includeConditionStats\":true}}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(code(body)).isEqualTo(PreviewErrorCodes.REQUEST_DSL_INVALID);
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> INJECTED_FIELDS = List.of(
            "tenantId", "programId", "userId", "membershipId", "dataScopeId", "permission", "permissions",
            "asOf", "timezone", "dataWatermark", "watermark", "snapshotId", "metadataSnapshotId",
            "metadataSnapshotOverride", "reportingCurrency", "plan", "planHash", "prebuiltPlan",
            "semanticQueryPlan", "sql", "sqlText", "compiledSql", "catalogId", "storageCatalog",
            "tableName", "columnName", "fixtureId", "fixturePath", "fixtureVersion", "fxRate",
            "fxRateSnapshot", "backend", "backendId", "backendSelection", "authorizationResult",
            "authorizationDecision", "trustedContext");

    private static byte[] withTopLevelInjection(String field) {
        String base = PreviewTestSupport.utf8(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
        return ("{\"" + field + "\":\"injected\"," + base.substring(1)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withPresentationInjection(String field) {
        String base = PreviewTestSupport.utf8(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
        return base.replace("\"includeConditionStats\":true}",
                        "\"includeConditionStats\":true,\"" + field + "\":\"injected\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withDslInjection(String field) {
        String base = PreviewTestSupport.utf8(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
        return base.replace("\"segmentDsl\":{", "\"segmentDsl\":{\"" + field + "\":\"injected\",")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String code(byte[] bytes) {
        try {
            CODEC.parse(bytes);
            return null;
        } catch (PreviewException failure) {
            return failure.code();
        }
    }

    private static PreviewException failureOf(byte[] bytes) {
        try {
            return failureOf(CODEC, bytes);
        } catch (RuntimeException unexpected) {
            fail("a non-bounded exception escaped the request codec: " + unexpected.getClass().getName());
            return null;
        }
    }

    private static PreviewException failureOf(PreviewRequestCodec codec, byte[] bytes) {
        try {
            codec.parse(bytes);
            fail("expected a bounded PreviewException");
            return null;
        } catch (PreviewException failure) {
            return failure;
        }
    }

    private static void assertNoEcho(PreviewException failure, String injected) {
        assertThat(failure.getMessage()).doesNotContain(injected);
        assertThat(failure.details().toString()).doesNotContain(injected);
    }
}