package com.company.campaign.evaluation.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CatalogReference;
import com.company.campaign.evaluation.segment.contract.CorpusManifest;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.FixtureCatalog;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.contract.MetadataCatalog;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RefinementTargetPlaceholder;
import com.company.campaign.evaluation.segment.contract.ReportArtifactHashes;
import com.company.campaign.evaluation.segment.contract.ReportMetadata;
import com.company.campaign.evaluation.segment.contract.ReportModelDescriptor;
import com.company.campaign.evaluation.segment.contract.ReportTimeContext;
import com.company.campaign.evaluation.segment.contract.ReportVersionPins;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.testsupport.ExpectedResultBuilder;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import com.company.campaign.testsupport.ContractAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract-level guarantees of the evaluation assets: exact versioning, strict parsing, set semantics and
 * deterministic canonical bytes.
 *
 * <p>These tests never touch the filesystem. They prove that each asset type round-trips through canonical
 * JSON unchanged, that unknown fields and duplicate keys are refused by the canonical JSON rules, and that
 * order-insensitive expectations still serialize to one stable representation.
 */
class SegmentEvaluationContractTest {

    private final CanonicalJson canonicalJson = new CanonicalJson();

    private static ContentHash hashOf(String content) {
        return ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    // Requirement: every asset type round-trips and hashes deterministically.

    @Test
    void corpusManifestRoundTripsAndHashesItsOwnContent() {
        CorpusManifest manifest = sampleManifest();

        assertThat(manifest.manifestHash()).isEqualTo(manifest.recomputeContentHash());
        manifest.requireValidContentHash();

        String written = canonicalJson.writeString(manifest);
        CorpusManifest read = canonicalJson.read(written, CorpusManifest.class);

        assertThat(read).isEqualTo(manifest);
        assertThat(read.manifestHash()).isEqualTo(manifest.manifestHash());
        assertThat(canonicalJson.writeString(read)).isEqualTo(written);
        assertThat(canonicalJson.hash(read)).isEqualTo(canonicalJson.hash(manifest));
    }

    // Requirement: the manifest hash excludes the hash field, so it cannot be self-referential.
    @Test
    void manifestHashIsComputedWithoutTheHashField() {
        CorpusManifest manifest = sampleManifest();
        String withoutHash = canonicalJson.writeString(manifest.withManifestHash(null));

        assertThat(withoutHash).doesNotContain("manifestHash");
        assertThat(ContentHash.sha256(canonicalJson.write(manifest.withManifestHash(null))))
                .isEqualTo(manifest.manifestHash());
    }

    @Test
    void manifestRejectsATamperedContentHash() {
        CorpusManifest manifest = sampleManifest();
        CorpusManifest tampered = manifest.withManifestHash(hashOf("not the manifest"));

        ContractAssertions.assertFailsWithCode(
                tampered::requireValidContentHash, EvaluationErrorCodes.MANIFEST_HASH_MISMATCH);
    }

    @Test
    void caseInputRoundTripsAndCarriesNoExpectedAnswers() {
        CaseInput caseInput = sampleCase();
        String written = canonicalJson.writeString(caseInput);

        assertThat(canonicalJson.read(written, CaseInput.class)).isEqualTo(caseInput);
        assertThat(written).doesNotContain("expected");
        assertThat(Arrays.stream(CaseInput.class.getRecordComponents()).map(r -> r.getName()))
                .noneMatch(name -> name.startsWith("expected"));
    }

    @Test
    void expectedResultRoundTripsAndKeepsItsPlaceholders() {
        ExpectedResult expected = sampleExpected();
        String written = canonicalJson.writeString(expected);

        assertThat(canonicalJson.read(written, ExpectedResult.class)).isEqualTo(expected);
        assertThat(written).contains("expectedCanonicalAst");
    }

    @Test
    void catalogsAndReportMetadataRoundTrip() {
        MetadataCatalog metadataCatalog = new MetadataCatalog(EvaluationVersions.CURRENT, "1", List.of(
                new MetadataSnapshotDescriptor("metadata-001", "1", hashOf("snapshot"),
                        "evaluation/segment/metadata/payloads/metadata-001.json")));
        FixtureCatalog fixtureCatalog = new FixtureCatalog(EvaluationVersions.CURRENT, "1", List.of(
                new FixtureDescriptor("fixture-primary-001", "1", FixtureRole.PRIMARY,
                        "evaluation/segment/fixtures/payloads/fixture-primary-001.json", hashOf("fixture"),
                        Instant.parse("2026-10-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"),
                        Instant.parse("2026-10-01T01:55:00Z"), new MemberIdSet(List.of("m-001", "m-002")))));

        for (Object asset : List.of(metadataCatalog, fixtureCatalog, sampleReportMetadata())) {
            String written = canonicalJson.writeString(asset);
            assertThat(canonicalJson.writeString(canonicalJson.read(written, asset.getClass())))
                    .isEqualTo(written);
            assertThat(canonicalJson.hash(canonicalJson.read(written, asset.getClass())))
                    .isEqualTo(canonicalJson.hash(asset));
        }
    }

    // Requirement: every one of the fifteen categories exists as a stable machine identifier.

    @Test
    void allExactFifteenCategoriesAreRepresented() {
        assertThat(SegmentCategory.values()).hasSize(15);
        assertThat(SegmentCategory.ordered()).containsExactly(SegmentCategory.values());
        assertThat(Arrays.stream(SegmentCategory.values()).map(SegmentCategory::specNumber))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 15).boxed().toList());
        assertThat(Arrays.stream(SegmentCategory.values()).map(SegmentCategory::summary))
                .allSatisfy(summary -> assertThat(summary).isNotBlank());
        assertThat(SegmentCategory.valueOf("PROMPT_INJECTION_UNAUTHORIZED_AND_CROSS_TENANT").specNumber())
                .isEqualTo(15);
        assertThat(SegmentCategory.valueOf("ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS").specNumber())
                .isEqualTo(1);
    }

    @Test
    void riskLevelsAreClosedAndOnlyHighRiskRequiresRepeats() {
        assertThat(Arrays.stream(RiskLevel.values()).map(Enum::name))
                .containsExactly("HIGH", "MEDIUM", "LOW");
        assertThat(RiskLevel.HIGH.requiresRepeatConsistency()).isTrue();
        assertThat(RiskLevel.MEDIUM.requiresRepeatConsistency()).isFalse();
        assertThat(RiskLevel.LOW.requiresRepeatConsistency()).isFalse();
        assertThat(Arrays.stream(FixtureRole.values()).map(Enum::name))
                .containsExactly("PRIMARY", "COUNTEREXAMPLE", "EXECUTION_CONTEXT");
        assertThat(FixtureRole.EXECUTION_CONTEXT.isMemberSetFixture()).isFalse();
        assertThat(FixtureRole.COUNTEREXAMPLE.isMemberSetFixture()).isTrue();
    }

    // Requirement: unknown fields and duplicate JSON keys fail through the canonical JSON rules.

    @Test
    void unknownFieldsAreRejectedByEveryAssetType() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(withExtraField(canonicalJson.writeString(sampleCase()),
                        "\"unexpected\":1"), CaseInput.class),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(withExtraField(canonicalJson.writeString(sampleExpected()),
                        "\"unexpected\":1"), ExpectedResult.class),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(withExtraField(canonicalJson.writeString(sampleManifest()),
                        "\"unexpected\":1"), CorpusManifest.class),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
    }

    // The separation of question and answer is enforced by binding, not by review.
    @Test
    void aCaseInputCannotCarryAnExpectedAnswer() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(withExtraField(canonicalJson.writeString(sampleCase()),
                        "\"expectedDisposition\":\"RESOLVED\""), CaseInput.class),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(withExtraField(canonicalJson.writeString(sampleCase()),
                        "\"expectedMemberIdsByFixture\":{}"), CaseInput.class),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
    }

    @Test
    void duplicateJsonKeysAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.readTree("{\"caseId\":\"a\",\"caseId\":\"b\"}"),
                ContractErrorCodes.JSON_DUPLICATE_KEY);
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("{\"caseId\":\"a\",\"caseId\":\"b\"}", CaseInput.class),
                ContractErrorCodes.JSON_DUPLICATE_KEY);
    }

    // Requirement: exact versions only.

    @ParameterizedTest
    @ValueSource(strings = {"1.2", "1.4", "2.0", "0.9"})
    void unsupportedExactSchemaVersionsAreRejected(String version) {
        ContractAssertions.assertFailsWithCode(
                () -> EvaluationVersions.requireSupported(
                        com.company.campaign.contracts.SchemaVersion.parse(version)),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);
    }

    @Test
    void malformedSchemaVersionsAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read("{\"schemaVersion\":\"1.x\"}", CorpusManifest.class),
                ContractErrorCodes.SCHEMA_VERSION_MALFORMED);
    }

    @Test
    void currentSchemaVersionIsAccepted() {
        EvaluationVersions.requireSupported(EvaluationVersions.CURRENT);
        assertThat(EvaluationVersions.CURRENT.value()).isEqualTo("1.3");
    }

    // Requirement: member sets are sets, with order-independent equality and stable serialization.

    @Test
    void memberIdSetsRejectDuplicatesAndSortDeterministically() {
        ContractAssertions.assertFailsWithCode(
                () -> new MemberIdSet(List.of("m-001", "m-001")),
                EvaluationErrorCodes.MEMBER_ID_DUPLICATE);
        ContractAssertions.assertFailsWithCode(
                () -> new MemberIdSet(List.of("not a member!")),
                EvaluationErrorCodes.MEMBER_ID_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new MemberIdSet(null),
                EvaluationErrorCodes.MEMBER_ID_INVALID);

        MemberIdSet sorted = new MemberIdSet(List.of("m-003", "m-001", "m-002"));
        MemberIdSet otherOrder = new MemberIdSet(List.of("m-002", "m-003", "m-001"));

        assertThat(sorted.memberIds()).containsExactly("m-001", "m-002", "m-003");
        assertThat(sorted).isEqualTo(otherOrder);
        assertThat(sorted).hasSameHashCodeAs(otherOrder);
        assertThat(canonicalJson.writeString(sorted)).isEqualTo(canonicalJson.writeString(otherOrder));
        assertThat(sorted.contains("m-002")).isTrue();
        assertThat(sorted.contains("m-004")).isFalse();
        assertThat(new MemberIdSet(List.of()).size()).isZero();
        assertThat(canonicalJson.writeString(MemberIdSet.of("m-001"))).isEqualTo("[\"m-001\"]");
    }

    @Test
    void expectedResultsAreOrderIndependentForSetLikeExpectations() {
        ExpectedResult first = ExpectedResultBuilder.from(sampleExpected())
                .forbiddenAssumptions(List.of("ASSUME_GROSS_AMOUNT", "ASSUME_CURRENT_TIER"))
                .clarificationCodes(List.of("SELECT_SPEND_DEFINITION", "SELECT_CURRENCY"))
                .memberSet("fixture-primary-001", List.of("m-002", "m-001"))
                .build();
        ExpectedResult second = ExpectedResultBuilder.from(sampleExpected())
                .forbiddenAssumptions(List.of("ASSUME_CURRENT_TIER", "ASSUME_GROSS_AMOUNT"))
                .clarificationCodes(List.of("SELECT_CURRENCY", "SELECT_SPEND_DEFINITION"))
                .memberSet("fixture-primary-001", List.of("m-001", "m-002"))
                .build();

        assertThat(first).isEqualTo(second);
        assertThat(canonicalJson.writeString(first)).isEqualTo(canonicalJson.writeString(second));
        assertThat(first.forbiddenAssumptions())
                .containsExactly("ASSUME_CURRENT_TIER", "ASSUME_GROSS_AMOUNT");
    }

    // Requirement: duplicate set-like values are rejected, but issue order is preserved.

    @Test
    void duplicateSetLikeValuesAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> ExpectedResultBuilder.from(sampleExpected())
                        .forbiddenAssumptions(List.of("ASSUME_A", "ASSUME_A")).build(),
                EvaluationErrorCodes.DUPLICATE_SET_VALUE);
        ContractAssertions.assertFailsWithCode(
                () -> ExpectedResultBuilder.from(sampleExpected())
                        .clarificationCodes(List.of("SELECT_CURRENCY", "SELECT_CURRENCY")).build(),
                EvaluationErrorCodes.DUPLICATE_SET_VALUE);
        ContractAssertions.assertFailsWithCode(
                () -> ExpectedResultBuilder.from(sampleExpected())
                        .selectedCandidates(List.of("member.tier", "member.tier")).build(),
                EvaluationErrorCodes.DUPLICATE_SET_VALUE);
        ContractAssertions.assertFailsWithCode(
                () -> ExpectedResultBuilder.from(sampleExpected())
                        .validating("ATTRIBUTE_UNKNOWN", "ATTRIBUTE_UNKNOWN").build(),
                EvaluationErrorCodes.DUPLICATE_SET_VALUE);
    }

    @Test
    void expectedValidationIssueOrderIsPreserved() {
        ExpectedResult expected = ExpectedResultBuilder.from(sampleExpected())
                .validating("METADATA_SNAPSHOT_MISMATCH", "ATTRIBUTE_UNKNOWN", "CURRENCY_MISSING")
                .build();

        assertThat(expected.expectedValidationIssueCodes())
                .containsExactly("METADATA_SNAPSHOT_MISMATCH", "ATTRIBUTE_UNKNOWN", "CURRENCY_MISSING");
    }

    // Requirement: missing and malformed required values fail at binding time.

    @Test
    void missingExpectedDispositionFailsStrictBinding() {
        String written = canonicalJson.writeString(sampleExpected());
        written = written.replace("\"expectedDisposition\":\"RESOLVED\",", "");

        assertThat(written).doesNotContain("expectedDisposition");
        String finalWritten = written;
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(finalWritten, ExpectedResult.class),
                EvaluationErrorCodes.ASSET_INVALID);
    }

    @Test
    void complexSemanticsMustBeDeclaredExplicitly() {
        String written = canonicalJson.writeString(sampleCase()).replace("\"complexSemantics\":false,", "");
        String finalWritten = written;

        assertThat(finalWritten).doesNotContain("complexSemantics");
        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(finalWritten, CaseInput.class),
                EvaluationErrorCodes.ASSET_INVALID);
    }

    @Test
    void astIsProhibitedForExactlyTheThreeNonResolvedDispositions() {
        assertThat(ExpectedResult.AST_PROHIBITED_DISPOSITIONS)
                .containsExactlyInAnyOrder(
                        Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED);
        assertThat(sampleExpected().permitsCanonicalAst()).isTrue();
        assertThat(ExpectedResultBuilder.from(sampleExpected())
                .disposition(Disposition.NEEDS_INPUT).build().permitsCanonicalAst()).isFalse();
        assertThat(ExpectedResultBuilder.from(sampleExpected())
                .disposition(Disposition.UNSUPPORTED).build().permitsCanonicalAst()).isFalse();
        assertThat(ExpectedResultBuilder.from(sampleExpected())
                .disposition(Disposition.DENIED).build().permitsCanonicalAst()).isFalse();
        assertThat(ExpectedResultBuilder.from(sampleExpected())
                .disposition(Disposition.INVALID).build().permitsCanonicalAst()).isTrue();
    }

    // Requirement: fixture time context is fixed and internally consistent.

    @Test
    void fixtureTimeContextMustBeConsistent() {
        ContractAssertions.assertFailsWithCode(
                () -> fixtureDescriptor(Instant.parse("2026-10-01T02:00:00Z"), Instant.parse("2026-10-01T03:00:00Z")),
                EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new FixtureDescriptor("fixture-primary-001", "1", FixtureRole.PRIMARY,
                        "evaluation/segment/fixtures/payloads/fixture-primary-001.json", hashOf("fixture"),
                        null, ZoneId.of("Asia/Shanghai"), null, new MemberIdSet(List.of())),
                EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_INVALID);
        assertThat(fixtureDescriptor(Instant.parse("2026-10-01T02:00:00Z"), Instant.parse("2026-10-01T01:55:00Z")))
                .isNotNull();
    }

    @Test
    void refinementPlaceholderUsesStableReferencesNotArrayPositions() {
        RefinementTargetPlaceholder placeholder = new RefinementTargetPlaceholder(
                RefinementOperation.REPLACE_FIELD, "n-spend", "n-root");

        assertThat(canonicalJson.writeString(placeholder))
                .isEqualTo("{\"operation\":\"REPLACE_FIELD\",\"scopeRef\":\"n-root\",\"targetRef\":\"n-spend\"}");
        ContractAssertions.assertFailsWithCode(
                () -> new RefinementTargetPlaceholder(RefinementOperation.MOVE_NODE, "", null),
                EvaluationErrorCodes.ID_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> new RefinementTargetPlaceholder(RefinementOperation.MOVE_NODE, "n-spend?", null),
                EvaluationErrorCodes.ID_INVALID);
    }

    // The span predicate must not leak into the wire shape.
    @Test
    void sourceSpanSerializesAsExactlyTextStartEnd() {
        assertThat(canonicalJson.writeString(new SourceSpan("Gold", 0, 4)))
                .isEqualTo("{\"end\":4,\"start\":0,\"text\":\"Gold\"}");
        assertThat(canonicalJson.read("{\"end\":4,\"start\":0,\"text\":\"Gold\"}", SourceSpan.class))
                .isEqualTo(new SourceSpan("Gold", 0, 4));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String withExtraField(String json, String fieldJson) {
        return json.substring(0, json.length() - 1) + "," + fieldJson + "}";
    }

    private static FixtureDescriptor fixtureDescriptor(Instant asOf, Instant dataWatermark) {
        return new FixtureDescriptor("fixture-primary-001", "1", FixtureRole.PRIMARY,
                "evaluation/segment/fixtures/payloads/fixture-primary-001.json", hashOf("fixture"),
                asOf, ZoneId.of("Asia/Shanghai"), dataWatermark, new MemberIdSet(List.of("m-001")));
    }

    private static CatalogReference catalogReference(String version, String fileName) {
        return new CatalogReference(version, hashOf(fileName), "evaluation/segment/" + fileName);
    }

    private static CaseFileReference caseFileReference() {
        return new CaseFileReference("case-attr-001",
                "evaluation/segment/cases/case-attr-001.json", hashOf("case"),
                "evaluation/segment/expected/case-attr-001.json", hashOf("expected"));
    }

    private static CorpusManifest sampleManifest() {
        return CorpusManifest.of(EvaluationVersions.CURRENT, "1",
                catalogReference("1", "metadata/catalog.json"),
                catalogReference("1", "fixtures/catalog.json"),
                CorpusManifest.GATE_MINIMUM_CASES_PER_CATEGORY,
                CorpusManifest.GATE_REQUIRED_HIGH_RISK_REPEATS,
                List.of(caseFileReference()));
    }

    private static CaseInput sampleCase() {
        return new CaseInput(EvaluationVersions.CURRENT, "case-attr-001",
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.HIGH,
                List.of(new LocaleTag("zh-CN")), "Gold会员", null,
                "fixture-context-001", "metadata-001",
                List.of("fixture-primary-001"), false, List.of("sample"), null);
    }

    private static ExpectedResult sampleExpected() {
        return new ExpectedResult(EvaluationVersions.CURRENT, "case-attr-001", Disposition.RESOLVED,
                List.of(new ExpectedIntentSlot("c1", "MEMBER_TIER", "EQUAL", true,
                        new SourceSpan("Gold", 0, 4), TextNode.valueOf("GOLD"))),
                List.of("member.tier"), List.of("member.tier.gross"), astPlaceholder(),
                List.of("CURRENCY_MISSING"), List.of("SELECT_CURRENCY"),
                Map.of("fixture-primary-001", new MemberIdSet(List.of("m-001"))),
                List.of("CLAIM_TIER_MATCH"), null, List.of("ASSUME_GROSS_AMOUNT"));
    }

    private static ObjectNode astPlaceholder() {
        ObjectNode node = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        node.put("nodeId", "n-root");
        node.put("type", "ALL");
        return node;
    }

    private static ReportMetadata sampleReportMetadata() {
        JsonNode decoding = TextNode.valueOf("deterministic");
        return new ReportMetadata(EvaluationVersions.CURRENT, "report-001",
                GateOutcome.FAILED,
                new ReportModelDescriptor("anthropic", "claude-sonnet-5", "2026-09-01"),
                decoding,
                new ReportArtifactHashes(hashOf("prompt"), hashOf("skill"), hashOf("tools")),
                new ReportVersionPins(EvaluationVersions.CURRENT, EvaluationVersions.CURRENT,
                        "1", hashOf("metadata-catalog"), "1", hashOf("corpus"),
                        "1", hashOf("fixture-catalog")),
                new ReportTimeContext(Instant.parse("2026-10-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"),
                        Instant.parse("2026-10-01T01:55:00Z")),
                "9f3c1a2", "local");
    }

    @Test
    void reportMetadataRequiresEveryReproducibilityField() {
        assertThatThrownBy(() -> new ReportMetadata(EvaluationVersions.CURRENT, "report-001",
                GateOutcome.FAILED,
                new ReportModelDescriptor("anthropic", "claude-sonnet-5", "2026-09-01"),
                null, new ReportArtifactHashes(null, null, null),
                null,
                new ReportTimeContext(Instant.parse("2026-10-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"),
                        Instant.parse("2026-10-01T01:55:00Z")),
                "9f3c1a2", "local"))
                .isInstanceOf(ContractException.class);
    }
}