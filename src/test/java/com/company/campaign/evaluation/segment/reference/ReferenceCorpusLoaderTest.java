package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.reference.testsupport.ReferenceCorpusV1Generator;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused loader tests for the committed T18 Existing Audience Reference corpus.
 *
 * <p>They prove four things about the checked-in assets and nothing more. The committed tree is exactly what
 * the reviewed blueprints generate, so the files that were reviewed are the files that are read. Every case,
 * expectation, review ledger, fixture payload and catalog is its own canonical serialization and matches the
 * hash the manifest pins. The corpus is intact above the T18 floor of forty cases. And a corpus that has been
 * hand-edited, re-hashed without re-reviewing, given an unknown field or made to declare one case twice is
 * refused by the loader rather than read as reviewed evidence.
 */
class ReferenceCorpusLoaderTest {

    private static final Path REPOSITORY_ROOT = Path.of("");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    @TempDir
    Path temporaryRoot;

    @Test
    void theCommittedCorpusIsExactlyTheGeneratedCorpus() {
        assertThat(ReferenceCorpusV1Generator.differences(
                REPOSITORY_ROOT, ReferenceCorpusV1Generator.generate()))
                .as("the committed reference corpus is not the reviewed blueprint output")
                .isEmpty();
    }

    @Test
    void theCommittedCorpusIsIntactAndAboveTheGateFloor() {
        ReferenceCorpusBundle bundle = committedLoader().loadCorpus();

        assertThat(bundle.cases()).hasSizeGreaterThanOrEqualTo(ReferenceCorpusVersions.GATE_MINIMUM_CASES);
        assertThat(bundle.expectedResults()).hasSize(bundle.cases().size());
        assertThat(bundle.reviews()).hasSize(bundle.cases().size());
        assertThat(bundle.cases()).extracting(ReferenceCorpusCase::caseId).doesNotHaveDuplicates();
        assertThat(bundle.manifest().requiresGateMinimum()).isTrue();
        assertThat(bundle.manifest().minimumCases())
                .isGreaterThanOrEqualTo(ReferenceCorpusVersions.GATE_MINIMUM_CASES);
        assertThat(bundle.manifest().manifestHash()).isEqualTo(bundle.manifest().recomputeContentHash());
        assertThat(bundle.fixtureCatalog().catalogVersion())
                .isEqualTo(ReferenceCorpusVersions.FIXTURE_CATALOG_VERSION);
    }

    @Test
    void everyFixturePayloadMatchesTheHashItsCatalogPins() {
        ReferenceCorpusBundle bundle = committedLoader().loadCorpus();

        assertThat(bundle.fixtureCatalog().fixtures()).hasSizeGreaterThan(1);
        for (ReferenceFixtureDescriptor fixture : bundle.fixtureCatalog().fixtures()) {
            byte[] payload = bundle.fixturePayload(fixture.fixtureId());
            assertThat(payload).as("fixture %s", fixture.fixtureId()).isNotNull();
            assertThat(ContentHash.sha256(payload))
                    .as("fixture %s", fixture.fixtureId())
                    .isEqualTo(fixture.payloadHash());
        }
    }

    @Test
    void everyCaseExpectedAndReviewAssetIsItsOwnCanonicalSerializationAtItsPinnedHash() {
        ReferenceCorpusBundle bundle = committedLoader().loadCorpus();

        for (CaseFileReference reference : bundle.manifest().cases()) {
            assertCanonicalAndPinned(reference.casePath(), reference.caseHash(), ReferenceCorpusCase.class);
            assertCanonicalAndPinned(
                    reference.expectedPath(), reference.expectedHash(), ReferenceCorpusExpected.class);
            assertCanonicalAndPinned(
                    reference.reviewPath(), reference.reviewHash(), CaseReviewEvidence.class);
        }
        assertCanonicalAndPinned(
                bundle.manifest().fixtureCatalog().path(),
                bundle.manifest().fixtureCatalog().contentHash(),
                ReferenceFixtureCatalog.class);
        assertManifestIsCanonicalAndSelfConsistent();
    }

    @Test
    void aCommittedCaseEditedWithoutRehashingIsRefused() {
        Path root = corpusCopy();
        ReferenceCorpusManifest manifest = readManifest(root);
        Path caseFile = root.resolve(manifest.cases().get(0).casePath());
        byte[] original = read(caseFile);
        byte[] tampered = new String(original, StandardCharsets.UTF_8)
                .replace("\"reference-corpus\"", "\"reference-corpusx\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(tampered).as("the tamper must actually change the bytes").isNotEqualTo(original);
        write(caseFile, tampered);

        assertThatThrownBy(() -> new ReferenceCorpusLoader(root).loadCorpus())
                .isInstanceOf(ContractException.class)
                .extracting(thrown -> ((ContractException) thrown).code())
                .isEqualTo(EvaluationErrorCodes.HASH_MISMATCH);
    }

    @Test
    void aRehashedButHandEditedCaseIsRefusedAsNonCanonical() {
        Path root = corpusCopy();
        ReferenceCorpusManifest manifest = readManifest(root);
        CaseFileReference first = manifest.cases().get(0);
        Path caseFile = root.resolve(first.casePath());
        byte[] tampered = (" " + new String(read(caseFile), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        write(caseFile, tampered);
        writeManifest(root, withCaseHash(manifest, first.caseId(), ContentHash.sha256(tampered)));

        // The declared hash now matches the edited bytes, so the hash check passes and only the canonicity
        // rule can catch it: a file whose bytes are not the canonical serialization of the value they carry
        // is not the reviewed value, however well it hashes.
        assertThatThrownBy(() -> new ReferenceCorpusLoader(root).loadCorpus())
                .isInstanceOf(ContractException.class)
                .extracting(thrown -> ((ContractException) thrown).code())
                .isEqualTo(EvaluationErrorCodes.ASSET_NOT_CANONICAL);
    }

    @Test
    void aCaseCarryingAnUnknownFieldIsRefused() {
        Path root = corpusCopy();
        ReferenceCorpusManifest manifest = readManifest(root);
        CaseFileReference first = manifest.cases().get(0);
        Path caseFile = root.resolve(first.casePath());
        ObjectNode caseTree = (ObjectNode) CANONICAL_JSON.readTree(
                new String(read(caseFile), StandardCharsets.UTF_8));
        caseTree.put("expectedOutcome", "CLEAN");
        byte[] tampered = CANONICAL_JSON.write(caseTree);
        write(caseFile, tampered);
        writeManifest(root, withCaseHash(manifest, first.caseId(), ContentHash.sha256(tampered)));

        assertThatThrownBy(() -> new ReferenceCorpusLoader(root).loadCorpus())
                .isInstanceOf(ContractException.class)
                .extracting(thrown -> ((ContractException) thrown).code())
                .isEqualTo(ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
    }

    @Test
    void aManifestDeclaringOneCaseTwiceIsRefused() {
        Path root = corpusCopy();
        ReferenceCorpusManifest manifest = readManifest(root);
        List<CaseFileReference> duplicated = new ArrayList<>(manifest.cases());
        duplicated.add(manifest.cases().get(0));
        writeManifest(root, withCases(manifest, duplicated));

        assertThatThrownBy(() -> new ReferenceCorpusLoader(root).loadCorpus())
                .isInstanceOf(ContractException.class)
                .extracting(thrown -> ((ContractException) thrown).code())
                .isEqualTo(ReferenceCorpusErrorCodes.DUPLICATE_CASE);
    }

    @Test
    void theLoaderNeverWritesToTheRepository() {
        // One ordinary load of the committed corpus, twice, with no regeneration property set: the source tree
        // is untouched, which is what makes the generator explicitly manual.
        ReferenceCorpusBundle first = committedLoader().loadCorpus();
        ReferenceCorpusBundle second = committedLoader().loadCorpus();

        assertThat(second.cases()).extracting(ReferenceCorpusCase::caseId)
                .isEqualTo(first.cases().stream().map(ReferenceCorpusCase::caseId).toList());
        assertThat(second.manifest().manifestHash()).isEqualTo(first.manifest().manifestHash());
        assertThat(ReferenceCorpusV1Generator.differences(
                REPOSITORY_ROOT, ReferenceCorpusV1Generator.generate())).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private static ReferenceCorpusLoader committedLoader() {
        return new ReferenceCorpusLoader(REPOSITORY_ROOT);
    }

    /** Writes the generated corpus into the temporary root and returns it as a mutable sandbox. */
    private Path corpusCopy() {
        ReferenceCorpusV1Generator.write(temporaryRoot, ReferenceCorpusV1Generator.generate());
        return temporaryRoot;
    }

    private void assertCanonicalAndPinned(String reference, ContentHash pinned, Class<?> type) {
        byte[] bytes = read(REPOSITORY_ROOT.resolve(reference));
        assertThat(ContentHash.sha256(bytes)).as(reference).isEqualTo(pinned);
        Object asset = CANONICAL_JSON.read(new String(bytes, StandardCharsets.UTF_8), type);
        assertThat(CANONICAL_JSON.write(asset))
                .as("%s is valid JSON but not its canonical serialization", reference)
                .isEqualTo(bytes);
    }

    /**
     * The manifest is the one asset whose declared hash is deliberately not the hash of its own file bytes:
     * {@link ReferenceCorpusManifest#manifestHash()} covers the manifest with that field excluded, so no file
     * can be its own hash. It is checked the same way the corpus contract checks it — the file is its own
     * canonical serialization, and the hash it declares is the one the parsed value re-derives.
     */
    private void assertManifestIsCanonicalAndSelfConsistent() {
        byte[] bytes = read(REPOSITORY_ROOT.resolve(ReferenceCorpusVersions.MANIFEST_REFERENCE));
        ReferenceCorpusManifest manifest = CANONICAL_JSON.read(
                new String(bytes, StandardCharsets.UTF_8), ReferenceCorpusManifest.class);

        assertThat(CANONICAL_JSON.write(manifest))
                .as("%s is not its own canonical serialization", ReferenceCorpusVersions.MANIFEST_REFERENCE)
                .isEqualTo(bytes);
        assertThat(manifest.manifestHash())
                .as("the manifest hash covers the manifest without the hash field")
                .isEqualTo(manifest.recomputeContentHash());
    }

    private static ReferenceCorpusManifest readManifest(Path root) {
        return CANONICAL_JSON.read(
                new String(read(root.resolve(ReferenceCorpusVersions.MANIFEST_REFERENCE)),
                        StandardCharsets.UTF_8),
                ReferenceCorpusManifest.class);
    }

    private static void writeManifest(Path root, ReferenceCorpusManifest manifest) {
        write(root.resolve(ReferenceCorpusVersions.MANIFEST_REFERENCE), CANONICAL_JSON.write(manifest));
    }

    private static ReferenceCorpusManifest withCaseHash(
            ReferenceCorpusManifest manifest, String caseId, ContentHash caseHash) {
        List<CaseFileReference> updated = new ArrayList<>(manifest.cases().size());
        for (CaseFileReference reference : manifest.cases()) {
            updated.add(reference.caseId().equals(caseId)
                    ? new CaseFileReference(
                            reference.caseId(), reference.casePath(), caseHash, reference.expectedPath(),
                            reference.expectedHash(), reference.reviewPath(), reference.reviewHash())
                    : reference);
        }
        return withCases(manifest, updated);
    }

    private static ReferenceCorpusManifest withCases(
            ReferenceCorpusManifest manifest, List<CaseFileReference> cases) {
        ReferenceCorpusManifest withoutHash = new ReferenceCorpusManifest(
                manifest.schemaVersion(), manifest.corpusVersion(), manifest.fixtureCatalog(),
                manifest.minimumCases(), manifest.minimumCasesPerCategory(), manifest.humanReviewStatus(),
                manifest.modelEvidenceStatus(), manifest.modelEvidenceReference(), manifest.releaseStatus(),
                manifest.requiredCategories(), cases, null);
        return withoutHash.withManifestHash(withoutHash.recomputeContentHash());
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.write(file, bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}