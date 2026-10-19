package com.company.campaign.evaluation.journey.t20;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class T20EvaluationLoaderTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath().normalize();

    @Test
    void loadsTheFrozenCanonicalCorpus() {
        T20EvaluationCorpus corpus = new T20EvaluationLoader(REPOSITORY_ROOT).loadCorpus();

        assertThat(corpus.manifest().schemaVersion()).isEqualTo("1.0");
        assertThat(corpus.manifest().assetVersion()).isEqualTo("t20-v1");
        assertThat(corpus.manifest().productionGateEvidence()).isFalse();
        assertThat(corpus.cases().productionGateEvidence()).isFalse();
        assertThat(corpus.cases().cases()).hasSize(33);
        assertThat(corpus.cases().cases().stream()
                .map(T20EvaluationCase::category)
                .collect(Collectors.toSet()))
                .containsAll(Set.of(
                        "VALID_GRAPH", "GRAPH_REJECTION", "CAPABILITY_REJECTION",
                        "REFERENCE_BINDING", "SIMULATION", "DETERMINISM",
                        "LIMIT_REJECTION", "SEMANTIC_SAFETY", "SIDE_EFFECT_BOUNDARY"));
    }

    @Test
    void rejectsAnExtraAsset(@TempDir Path temporaryRoot) throws IOException {
        copyCorpus(temporaryRoot);
        Files.writeString(
                temporaryRoot.resolve(T20EvaluationLoader.ROOT_REFERENCE).resolve("extra.json"),
                "{}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new T20EvaluationLoader(temporaryRoot).loadCorpus())
                .isInstanceOf(T20EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T20EvaluationErrorCodes.ASSET_SET_INVALID);
    }

    @Test
    void rejectsTamperedCases(@TempDir Path temporaryRoot) throws IOException {
        copyCorpus(temporaryRoot);
        Path cases = temporaryRoot.resolve(T20EvaluationLoader.CASES_REFERENCE);
        String tampered = Files.readString(cases, StandardCharsets.UTF_8)
                .replace("known-true", "known-truth");
        Files.writeString(cases, tampered, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new T20EvaluationLoader(temporaryRoot).loadCorpus())
                .isInstanceOf(T20EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T20EvaluationErrorCodes.ASSET_HASH_MISMATCH);
    }

    @Test
    void rejectsNonCanonicalWhitespace(@TempDir Path temporaryRoot) throws IOException {
        copyCorpus(temporaryRoot);
        Path cases = temporaryRoot.resolve(T20EvaluationLoader.CASES_REFERENCE);
        Files.writeString(
                cases,
                Files.readString(cases, StandardCharsets.UTF_8) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new T20EvaluationLoader(temporaryRoot).loadCorpus())
                .isInstanceOf(T20EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T20EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void rejectsAnUnknownField(@TempDir Path temporaryRoot) throws IOException {
        copyCorpus(temporaryRoot);
        Path cases = temporaryRoot.resolve(T20EvaluationLoader.CASES_REFERENCE);
        String invalid = Files.readString(cases, StandardCharsets.UTF_8)
                .replace("{\"assetId\"", "{\"additional\":true,\"assetId\"");
        Files.writeString(cases, invalid, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new T20EvaluationLoader(temporaryRoot).loadCorpus())
                .isInstanceOf(T20EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T20EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void rejectsAManifestWhoseSelfHashWasNotUpdated(@TempDir Path temporaryRoot) throws IOException {
        copyCorpus(temporaryRoot);
        Path manifest = temporaryRoot.resolve(T20EvaluationLoader.MANIFEST_REFERENCE);
        String invalid = Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"caseCount\":33", "\"caseCount\":32");
        Files.writeString(manifest, invalid, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new T20EvaluationLoader(temporaryRoot).loadCorpus())
                .isInstanceOf(T20EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T20EvaluationErrorCodes.MANIFEST_HASH_MISMATCH);
    }

    private static void copyCorpus(Path temporaryRoot) throws IOException {
        Path target = temporaryRoot.resolve(T20EvaluationLoader.ROOT_REFERENCE);
        Files.createDirectories(target);
        Files.copy(
                REPOSITORY_ROOT.resolve(T20EvaluationLoader.MANIFEST_REFERENCE),
                target.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                REPOSITORY_ROOT.resolve(T20EvaluationLoader.CASES_REFERENCE),
                target.resolve("cases.json"), StandardCopyOption.REPLACE_EXISTING);
    }
}
