package com.company.campaign.evaluation.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.FixtureCatalog;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.contract.MetadataCatalog;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.testsupport.EvaluationCorpusFixture;
import com.company.campaign.evaluation.segment.loader.EvaluationRoot;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loader guarantees: reference containment, file existence, hash verification and identity pairing.
 *
 * <p>Every corpus lives in a JUnit temporary directory. The loader is never pointed at the source tree, so
 * a passing test cannot be confused with a real corpus having been checked in.
 */
class SegmentEvaluationLoaderTest {

    private static final String CONTEXT_PAYLOAD =
            EvaluationCorpusFixture.reference("fixtures/payloads/fixture-context-001.json");
    private static final String CASE_REFERENCE = EvaluationCorpusFixture.reference("cases/case-attr-001.json");

    @TempDir
    Path repositoryRoot;

    // --- happy path ------------------------------------------------------------------------------

    @Test
    void loadsAWellFormedCorpusInManifestOrder() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS).risk(RiskLevel.HIGH))
                .addCase(EvaluationCorpusFixture.caseSpec("case-bool-001",
                        SegmentCategory.BOOLEAN_AND_OR_NESTED_GROUPS))
                .write();

        CorpusBundle bundle = loader().loadCorpus();

        assertThat(bundle.cases()).extracting("caseId").containsExactly("case-attr-001", "case-bool-001");
        assertThat(bundle.expectedResults()).extracting("caseId").containsExactly("case-attr-001", "case-bool-001");
        assertThat(bundle.manifest().corpusVersion()).isEqualTo("1");
        assertThat(bundle.manifest().cases()).hasSize(2);
        assertThat(bundle.metadataCatalog().snapshots()).hasSize(1);
        assertThat(bundle.fixtureCatalog().fixtures()).hasSize(2);
        assertThat(bundle.expectedFor("case-bool-001").expectedDisposition()).isEqualTo(Disposition.RESOLVED);

        TrustedExecutionContextRef context =
                bundle.executionContext(EvaluationCorpusFixture.CONTEXT_FIXTURE_ID);
        assertThat(context).isNotNull();
        assertThat(context.tenantId()).isEqualTo("tenant-001");
        assertThat(context.timezone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(context.asOf()).isEqualTo(EvaluationCorpusFixture.AS_OF);
    }

    // --- Requirement: paths stay under the evaluation root ---------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "C:/Windows/system32/config.json",
            "/etc/passwd.json",
            "//server/share/asset.json",
    })
    void absoluteReferencesAreRejected(String reference) {
        ContractAssertions.assertFailsWithCode(
                () -> loader().root().resolve(reference), EvaluationErrorCodes.PATH_ABSOLUTE);
    }

    @Test
    void traversalReferencesAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> loader().root().resolve("evaluation/segment/../metadata/catalog.json"),
                EvaluationErrorCodes.PATH_TRAVERSAL);
        ContractAssertions.assertFailsWithCode(
                () -> loader().root().resolve("evaluation/segment/cases/../../../../etc/passwd.json"),
                EvaluationErrorCodes.PATH_TRAVERSAL);
        ContractAssertions.assertFailsWithCode(
                () -> loader().readVerifiedBytes("evaluation/segment/../secret.json", null),
                EvaluationErrorCodes.PATH_TRAVERSAL);
    }

    @Test
    void referencesOutsideTheEvaluationRootAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> loader().root().resolve("docs/README.md"),
                EvaluationErrorCodes.PATH_OUTSIDE_ROOT);
        ContractAssertions.assertFailsWithCode(
                () -> loader().root().resolve("evaluation/other/case.json"),
                EvaluationErrorCodes.PATH_OUTSIDE_ROOT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "evaluation\\segment\\cases\\a.json", "evaluation/segment/./a.json"})
    void malformedReferencesAreRejected(String reference) {
        ContractAssertions.assertFailsWithCode(
                () -> loader().root().resolve(reference), EvaluationErrorCodes.PATH_MALFORMED);
    }

    @Test
    void theConfiguredEvaluationRootMustBeARepositoryRelativePath() {
        ContractAssertions.assertFailsWithCode(
                () -> EvaluationRoot.of(repositoryRoot, "../outside"),
                EvaluationErrorCodes.PATH_TRAVERSAL);
        ContractAssertions.assertFailsWithCode(
                () -> EvaluationRoot.of(repositoryRoot, "/absolute/root"),
                EvaluationErrorCodes.PATH_ABSOLUTE);
        ContractAssertions.assertFailsWithCode(
                () -> EvaluationRoot.of(repositoryRoot, "  "),
                EvaluationErrorCodes.PATH_MALFORMED);

        Path root = repositoryRoot.toAbsolutePath().normalize();
        assertThat(EvaluationRoot.of(repositoryRoot).evaluationRoot())
                .isEqualTo(root.resolve("evaluation/segment"));
        // The configured root is honoured: a corpus can live somewhere else inside the repository.
        assertThat(EvaluationRoot.of(repositoryRoot, "evaluation").evaluationRoot())
                .isEqualTo(root.resolve("evaluation"));
    }

    // --- Requirement: missing files and content-hash mismatches ----------------------------------

    @Test
    void aMissingReferencedFileIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot).write();
        delete(repositoryRoot.resolve(CONTEXT_PAYLOAD));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.ASSET_NOT_FOUND);
    }

    @Test
    void aMissingCaseFileIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();
        delete(repositoryRoot.resolve(EvaluationCorpusFixture.reference("cases/case-attr-001.json")));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.ASSET_NOT_FOUND);
    }

    @Test
    void aCaseFileThatDoesNotMatchItsDeclaredHashIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();
        Path caseFile = repositoryRoot.resolve(EvaluationCorpusFixture.reference("cases/case-attr-001.json"));
        write(caseFile, read(caseFile).replace("\"Gold会员\"", "\"Silver会员\""));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.HASH_MISMATCH);
    }

    @Test
    void aCatalogThatDoesNotMatchItsDeclaredHashIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot).write();
        Path catalog = repositoryRoot.resolve(EvaluationCorpusFixture.reference("fixtures/catalog.json"));
        write(catalog, read(catalog).replace("\"catalogVersion\":\"1\"", "\"catalogVersion\":\"9\""));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.HASH_MISMATCH);
    }

    @Test
    void aManifestThatDoesNotMatchItsOwnHashIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot).write();
        Path manifest = EvaluationCorpusFixture.manifestPath(repositoryRoot);
        write(manifest, read(manifest).replace("\"corpusVersion\":\"1\"", "\"corpusVersion\":\"2\""));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.MANIFEST_HASH_MISMATCH);
    }

    // --- Requirement: duplicate identities and duplicate references ------------------------------

    @Test
    void duplicateCaseIdsAreRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-dup-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS).fileSlug("dup-a"))
                .addCase(EvaluationCorpusFixture.caseSpec("case-dup-001",
                        SegmentCategory.BOOLEAN_AND_OR_NESTED_GROUPS).fileSlug("dup-b"))
                .write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.DUPLICATE_ID);
    }

    @Test
    void duplicateFileReferencesAreRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-ref-a",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS).fileSlug("ref-a"))
                .addCase(EvaluationCorpusFixture.caseSpec("case-ref-b",
                        SegmentCategory.BOOLEAN_AND_OR_NESTED_GROUPS).fileSlug("ref-b")
                        .declaredCasePath(EvaluationCorpusFixture.reference("cases/ref-a.json")))
                .write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.DUPLICATE_REFERENCE);
    }

    @Test
    void duplicateFixtureAndSnapshotIdsAreRejectedByTheCatalogContract() {
        FixtureDescriptor descriptor = fixtureDescriptor("fixture-primary-001");

        ContractAssertions.assertFailsWithCode(
                () -> new FixtureCatalog(EvaluationVersions.CURRENT, "1", List.of(descriptor, descriptor)),
                EvaluationErrorCodes.DUPLICATE_ID);

        MetadataSnapshotDescriptor snapshot = new MetadataSnapshotDescriptor(
                "metadata-001", "1", ContentHash.sha256("payload".getBytes(StandardCharsets.UTF_8)),
                EvaluationCorpusFixture.reference("metadata/payloads/metadata-001.json"));
        ContractAssertions.assertFailsWithCode(
                () -> new MetadataCatalog(EvaluationVersions.CURRENT, "1", List.of(snapshot, snapshot)),
                EvaluationErrorCodes.DUPLICATE_ID);
    }

    // --- Requirement: case/expected pairing -------------------------------------------------------

    @Test
    void aMissingExpectedCounterpartIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS).omitExpectedCounterpart())
                .write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.CASE_EXPECTED_MISSING);
    }

    @Test
    void aManifestReferenceThatDisagreesWithItsCaseFileIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS).declaredCaseId("case-other-001"))
                .write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.CASE_EXPECTED_MISMATCH);
    }

    @Test
    void anExpectedResultForADifferentCaseIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                        .declaredExpectedCaseId("case-other-001"))
                .write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.CASE_EXPECTED_MISMATCH);
    }

    // --- Requirement: schema versions and strict parsing -----------------------------------------

    @Test
    void unsupportedExactAssetVersionsAreRejected() {
        Path caseFile = writeValidOneCaseCorpus();
        write(caseFile, read(caseFile).replace("\"schemaVersion\":\"1.3\"", "\"schemaVersion\":\"1.2\""));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCase(CASE_REFERENCE),
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE);
    }

    @Test
    void malformedAssetVersionsAreRejected() {
        Path caseFile = writeValidOneCaseCorpus();
        write(caseFile, read(caseFile).replace("\"schemaVersion\":\"1.3\"", "\"schemaVersion\":\"1.x\""));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCase(CASE_REFERENCE),
                ContractErrorCodes.SCHEMA_VERSION_MALFORMED);
    }

    @Test
    void unknownFieldsInAnAssetFileFailThroughTheCanonicalJsonRules() {
        Path caseFile = writeValidOneCaseCorpus();
        write(caseFile, withExtraField(read(caseFile), "\"injectedByTest\":true"));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCase(CASE_REFERENCE),
                ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
    }

    @Test
    void anAssetThatIsNotItsOwnCanonicalSerializationIsRejected() {
        Path caseFile = writeValidOneCaseCorpus();
        // Valid JSON, different bytes: a leading space. The reviewed bytes and the reviewed value must not
        // be able to diverge, so this is refused rather than normalized away.
        write(caseFile, " " + read(caseFile));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCase(CASE_REFERENCE), EvaluationErrorCodes.ASSET_NOT_CANONICAL);
    }

    @Test
    void duplicateJsonKeysInAnAssetFileFailThroughTheCanonicalJsonRules() {
        Path caseFile = writeValidOneCaseCorpus();
        write(caseFile, "{\"caseId\":\"injected\"," + read(caseFile).substring(1));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCase(CASE_REFERENCE),
                ContractErrorCodes.JSON_DUPLICATE_KEY);
    }

    // --- Requirement: trusted context payloads are usable, and never inlined ---------------------

    @Test
    void anExecutionContextPayloadThatIsNotAContextIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .contextPayloadJson("{\"tenantId\":\"tenant-001\"}")
                .write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.CONTEXT_PAYLOAD_INVALID);
    }

    @Test
    void anExecutionContextPayloadMissingItsSnapshotIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .contextPayloadJson("{\"tenantId\":\"tenant-001\",\"programId\":\"program-001\","
                        + "\"userId\":\"user-001\",\"dataScopeId\":\"scope-008\","
                        + "\"locale\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\","
                        + "\"reportingCurrency\":\"CNY\",\"asOf\":\"2026-10-01T02:00:00Z\","
                        + "\"dataWatermark\":\"2026-10-01T01:55:00Z\"}")
                .write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.CONTEXT_PAYLOAD_INVALID);
    }

    @Test
    void aCatalogWhoseVersionDisagreesWithTheManifestIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot).declaredCatalogVersion("9").write();

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.ASSET_INVALID);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private SegmentEvaluationLoader loader() {
        return new SegmentEvaluationLoader(repositoryRoot);
    }

    private static FixtureDescriptor fixtureDescriptor(String fixtureId) {
        return new FixtureDescriptor(fixtureId, "1", FixtureRole.PRIMARY,
                EvaluationCorpusFixture.reference("fixtures/payloads/" + fixtureId + ".json"),
                ContentHash.sha256("fixture".getBytes(StandardCharsets.UTF_8)),
                EvaluationCorpusFixture.AS_OF, EvaluationCorpusFixture.TIMEZONE,
                EvaluationCorpusFixture.DATA_WATERMARK, new MemberIdSet(List.of("m-001")));
    }

    /** Writes a corpus whose single case is valid, and returns that case file. */
    private Path writeValidOneCaseCorpus() {
        EvaluationCorpusFixture fixture = EvaluationCorpusFixture.in(repositoryRoot);
        fixture.addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        fixture.write();
        return repositoryRoot.resolve(CASE_REFERENCE);
    }

    private static String withExtraField(String json, String fieldJson) {
        return json.substring(0, json.length() - 1) + "," + fieldJson + "}";
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}