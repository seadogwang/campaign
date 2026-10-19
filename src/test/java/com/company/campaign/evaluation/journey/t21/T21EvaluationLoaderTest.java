package com.company.campaign.evaluation.journey.t21;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused T21 section-8 tests: the strict, hash-pinned, explicitly non-production evaluation corpus. */
class T21EvaluationLoaderTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

    private static T21EvaluationLoader committedLoader() {
        return new T21EvaluationLoader(REPOSITORY_ROOT);
    }

    // ------------------------------------------------------------------ the committed corpus

    @Test
    void loadsTheFrozenCanonicalCorpus() {
        T21EvaluationCorpus corpus = committedLoader().loadCorpus();

        assertThat(corpus.manifest().assetId()).isEqualTo(T21EvaluationLoader.MANIFEST_ASSET_ID);
        assertThat(corpus.manifest().assetVersion()).isEqualTo(T21EvaluationLoader.ASSET_VERSION);
        assertThat(corpus.manifest().schemaVersion()).isEqualTo(T21EvaluationLoader.SCHEMA_VERSION);
        assertThat(corpus.manifest().productionGateEvidence())
                .as("T21 evaluation evidence is never production Gate evidence")
                .isFalse();
        assertThat(corpus.cases().productionGateEvidence()).isFalse();
        assertThat(corpus.cases().cases()).hasSize(52);
        assertThat(corpus.manifest().caseCount()).isEqualTo(corpus.cases().cases().size());
        assertThat(corpus.cases().cases()).allSatisfy(evaluationCase -> {
            assertThat(evaluationCase.expectedOutcome()).isIn("ACCEPT", "REJECT");
            assertThat(evaluationCase.assertions()).isNotEmpty();
        });
    }

    @Test
    void theCorpusCoversEveryRequiredScenarioCategory() {
        T21EvaluationCases cases = committedLoader().loadCorpus().cases();

        assertThat(cases.categories()).contains(
                "FULL_PREPARATION", "FULL_CONFIRMATION", "REJECTION", "HASH_BINDING", "ATOMICITY",
                "CONCURRENCY", "IMMEDIATE", "ONCE", "DAILY", "DST", "DETERMINISM", "PAUSE_RESUME",
                "REPLACEMENT", "RUNTIME_BOUNDARY", "SIDE_EFFECT_BOUNDARY");
    }

    @Test
    void theCorpusDeclaresTheRequiredScenarioAssertions() {
        T21EvaluationCases cases = committedLoader().loadCorpus().cases();

        assertThat(cases.declares("full-prepare-binds-journey-and-schedule", "JOURNEY_BOUND")).isTrue();
        assertThat(cases.declares("full-confirm-exact-retry", "IDEMPOTENT_REPLAY")).isTrue();
        assertThat(cases.declares("rollback-audit-failure", "NO_PUBLICATION")).isTrue();
        assertThat(cases.declares("rollback-outbox-failure", "POINTER_NOT_MOVED")).isTrue();
        assertThat(cases.declares("concurrent-publish-one-revision", "REVISION_CAS")).isTrue();
        assertThat(cases.declares("daily-most-recent-catch-up", "OLDER_MISSED_SKIPPED")).isTrue();
        assertThat(cases.declares("dst-gap-moves-forward", "NEXT_VALID_INSTANT")).isTrue();
        assertThat(cases.declares("dst-overlap-earlier-offset", "EARLIER_OFFSET")).isTrue();
        assertThat(cases.declares("execution-key-stable", "EXECUTION_KEY_NO_RANDOM")).isTrue();
        assertThat(cases.declares("pause-commits-no-new-due-work", "PAUSE_BLOCKS_DUE")).isTrue();
        assertThat(cases.declares("committed-work-preserved", "NO_REASSIGNMENT")).isTrue();
        assertThat(cases.declares("no-execution-participant-or-provider", "NO_PROVIDER_CALL")).isTrue();
        assertThat(cases.declares("no-model-mutation-tool", "NO_MODEL_TOOL")).isTrue();
    }

    @Test
    void theCommittedCasesDocumentIsStrictCanonicalJson() throws IOException {
        byte[] bytes = Files.readAllBytes(
                REPOSITORY_ROOT.resolve(T21EvaluationLoader.CASES_REFERENCE));
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertThat(text).doesNotContain("\n").doesNotContain(": ").doesNotContain(", ");
        assertThat(text).startsWith("{\"assetId\":");
    }

    // ------------------------------------------------------------------ tamper detection

    @Test
    void anExtraAssetIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Files.writeString(root.resolve(T21EvaluationLoader.ROOT_REFERENCE).resolve("extra.json"), "{}");

        assertThatThrownBy(() -> new T21EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.ASSET_SET_INVALID);
    }

    @Test
    void aTamperedCasesDocumentIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T21EvaluationLoader.CASES_REFERENCE);
        String text = Files.readString(cases, StandardCharsets.UTF_8);
        Files.writeString(cases, text.replace("SIDE_EFFECT_BOUNDARY", "SIDE_EFFECT_BOUNDARX"));

        assertThatThrownBy(() -> new T21EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.ASSET_HASH_MISMATCH);
    }

    @Test
    void nonCanonicalWhitespaceIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T21EvaluationLoader.CASES_REFERENCE);
        Files.writeString(cases, Files.readString(cases, StandardCharsets.UTF_8) + "\n");

        assertThatThrownBy(() -> new T21EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void anUnknownFieldIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T21EvaluationLoader.CASES_REFERENCE);
        String text = Files.readString(cases, StandardCharsets.UTF_8);
        Files.writeString(cases, text.replaceFirst("\\{\"assetId\"", "{\"additional\":true,\"assetId\""));

        assertThatThrownBy(() -> new T21EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void aManifestWhoseSelfHashWasNotUpdatedIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path manifest = root.resolve(T21EvaluationLoader.MANIFEST_REFERENCE);
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(manifest, text.replace("\"caseCount\":52", "\"caseCount\":51"));

        assertThatThrownBy(() -> new T21EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.MANIFEST_HASH_MISMATCH);
    }

    @Test
    void aMissingAssetIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Files.delete(root.resolve(T21EvaluationLoader.CASES_REFERENCE));

        assertThatThrownBy(() -> new T21EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.ASSET_SET_INVALID);
    }

    @Test
    void aCaseRecordRejectsAnUnboundedIdentifierOrOutcome() {
        assertThatThrownBy(() -> new T21EvaluationCase(
                "Not A Kebab Id", "FULL_PREPARATION", "ACCEPT", List.of("JOURNEY_BOUND")))
                .isInstanceOf(T21EvaluationException.class);
        assertThatThrownBy(() -> new T21EvaluationCase(
                "valid-id", "full_preparation", "ACCEPT", List.of("JOURNEY_BOUND")))
                .isInstanceOf(T21EvaluationException.class);
        assertThatThrownBy(() -> new T21EvaluationCase(
                "valid-id", "FULL_PREPARATION", "perhapS", List.of("JOURNEY_BOUND")))
                .isInstanceOf(T21EvaluationException.class);
        assertThatThrownBy(() -> new T21EvaluationCase(
                "valid-id", "FULL_PREPARATION", "ACCEPT", List.of()))
                .isInstanceOf(T21EvaluationException.class);
        assertThatThrownBy(() -> new T21EvaluationCase(
                "valid-id", "FULL_PREPARATION", "ACCEPT", List.of("JOURNEY_BOUND", "JOURNEY_BOUND")))
                .isInstanceOf(T21EvaluationException.class);
    }

    @Test
    void theCasesDocumentRejectsARelabelledProductionGateFlagOrADuplicateCase() {
        T21EvaluationCase one = new T21EvaluationCase(
                "case-one", "DAILY", "ACCEPT", List.of("INCLUSIVE_WINDOW"));

        assertThatThrownBy(() -> new T21EvaluationCases(
                T21EvaluationLoader.SCHEMA_VERSION, T21EvaluationLoader.CASES_ASSET_ID,
                T21EvaluationLoader.ASSET_VERSION, true, List.of(one)))
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.CORPUS_INVALID);
        assertThatThrownBy(() -> new T21EvaluationCases(
                T21EvaluationLoader.SCHEMA_VERSION, T21EvaluationLoader.CASES_ASSET_ID,
                T21EvaluationLoader.ASSET_VERSION, false, List.of(one, one)))
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.CORPUS_INVALID);
    }

    @Test
    void theManifestRejectsARelabelledProductionGateFlag() {
        assertThatThrownBy(() -> new T21EvaluationManifest(
                T21EvaluationLoader.SCHEMA_VERSION, T21EvaluationLoader.MANIFEST_ASSET_ID,
                T21EvaluationLoader.ASSET_VERSION, true, T21EvaluationLoader.CASES_REFERENCE,
                com.company.campaign.shared.canonical.ContentHash.sha256("x".getBytes(StandardCharsets.UTF_8)),
                1,
                com.company.campaign.shared.canonical.ContentHash.sha256("y".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(T21EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T21EvaluationErrorCodes.CORPUS_INVALID);
    }

    // ------------------------------------------------------------------ helpers

    private static void copyCorpus(Path root) throws IOException {
        Path target = root.resolve(T21EvaluationLoader.ROOT_REFERENCE);
        Files.createDirectories(target);
        for (String file : Set.of("manifest.json", "cases.json")) {
            Files.copy(REPOSITORY_ROOT.resolve(T21EvaluationLoader.ROOT_REFERENCE).resolve(file),
                    target.resolve(file));
        }
    }
}