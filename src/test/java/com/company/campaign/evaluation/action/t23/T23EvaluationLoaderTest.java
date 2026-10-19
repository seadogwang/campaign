package com.company.campaign.evaluation.action.t23;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T23 external-action evaluation tests: the frozen corpus loads, covers every required category and
 * assertion, is strict canonical JSON, and refuses a tampered case, an extra file, a stray whitespace byte, an
 * unknown field and a manifest whose self-hash was not updated.
 */
class T23EvaluationLoaderTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

    private static T23EvaluationLoader committedLoader() {
        return new T23EvaluationLoader(REPOSITORY_ROOT);
    }

    // ------------------------------------------------------------------ the committed corpus

    @Test
    void loadsTheFrozenCanonicalCorpus() {
        T23EvaluationCorpus corpus = committedLoader().loadCorpus();
        assertThat(T23EvaluationLoader.ASSET_VERSION).isEqualTo("t23-v1");
        assertThat(corpus.manifest().assetId()).isEqualTo("action-t23-manifest");
        assertThat(corpus.cases().assetId()).isEqualTo("action-t23-cases");
        assertThat(corpus.manifest().productionGateEvidence()).isFalse();
        assertThat(corpus.cases().productionGateEvidence()).isFalse();
        assertThat(corpus.cases().cases()).isNotEmpty();
        assertThat(corpus.manifest().caseCount()).isEqualTo(corpus.cases().cases().size());
        for (T23EvaluationCase evaluationCase : corpus.cases().cases()) {
            assertThat(evaluationCase.assertions()).isNotEmpty();
        }
    }

    @Test
    void theCorpusCoversEveryRequiredRequirementCategory() {
        T23EvaluationCorpus corpus = committedLoader().loadCorpus();
        assertThat(corpus.cases().categories()).containsExactlyInAnyOrder(
                "IDENTITY", "IDEMPOTENCY", "CONCURRENCY", "PROVIDER_OUTCOME", "RECONCILIATION", "RETRY",
                "RECOVERY", "BUDGET", "RUNTIME", "PAUSE", "SECURITY", "CAPABILITY", "BOUNDARY");
    }

    @Test
    void theCorpusDeclaresTheRequiredReliabilityAssertions() {
        T23EvaluationCases cases = committedLoader().loadCorpus().cases();
        assertThat(cases.declares("stable-business-key-excludes-attempts", "NO_ATTEMPT_IN_KEY")).isTrue();
        assertThat(cases.declares("concurrent-duplicate-preparation-single-action", "ONE_ACTION_EXISTS"))
                .isTrue();
        assertThat(cases.declares("duplicate-submission-one-remote-effect", "ONE_REMOTE_EFFECT")).isTrue();
        assertThat(cases.declares("remote-success-response-timeout", "TRANSPORT_TIMEOUT_NEVER_FAILURE"))
                .isTrue();
        assertThat(cases.declares("remote-success-response-timeout", "RECONCILED_BY_ORIGINAL_KEY")).isTrue();
        assertThat(cases.declares("timeout-then-reconcile-one-grant", "REWARD_GRANTED_ONCE")).isTrue();
        assertThat(cases.declares("no-retry-without-definitive-evidence", "RETRY_REQUIRES_EVIDENCE")).isTrue();
        assertThat(cases.declares("verification-exhaustion-needs-review", "NEEDS_REVIEW_TERMINAL")).isTrue();
        assertThat(cases.declares("crash-after-remote-success-before-commit", "CRASH_INJECTED")).isTrue();
        assertThat(cases.declares("reward-reservation-held-while-unknown", "RESERVATION_HELD")).isTrue();
        assertThat(cases.declares("reward-finalize-once-on-success", "RESERVATION_FINALIZED_ONCE")).isTrue();
        assertThat(cases.declares("reward-release-once-on-definitive-failure", "RESERVATION_RELEASED_ONCE"))
                .isTrue();
        assertThat(cases.declares("reward-held-for-needs-review", "RESERVATION_HELD")).isTrue();
        assertThat(cases.declares("message-creates-no-reward-reservation", "NO_REWARD_RESERVATION")).isTrue();
        assertThat(cases.declares("concurrent-budget-limit-deterministic", "NEVER_OVERSPEND")).isTrue();
        assertThat(cases.declares("runtime-unknown-integration", "NODE_VERIFYING")).isTrue();
        assertThat(cases.declares("runtime-needs-review-integration", "EXECUTION_NEVER_COMPLETE")).isTrue();
        assertThat(cases.declares("pause-before-submission", "NO_PROVIDER_CALL")).isTrue();
        assertThat(cases.declares("pause-during-in-flight-call", "NEVER_CLAIMS_CANCELLATION")).isTrue();
        assertThat(cases.declares("resume-without-resend", "SUCCESSFUL_ACTION_NOT_RESENT")).isTrue();
        assertThat(cases.declares("tenant-isolation", "NON_DISCLOSING_NOT_FOUND")).isTrue();
        assertThat(cases.declares("audit-failure-rolls-back", "NOTHING_WRITTEN")).isTrue();
        assertThat(cases.declares("store-failure-rolls-back", "NOTHING_WRITTEN")).isTrue();
        assertThat(cases.declares("provider-without-idempotency-fails-closed", "NO_PROVIDER_CALL")).isTrue();
        assertThat(cases.declares("simulated-provider-cannot-satisfy-live-ready", "LIVE_READY_IMPOSSIBLE"))
                .isTrue();
        assertThat(cases.declares("evaluation-assets-are-not-production-evidence",
                "PRODUCTION_GATE_EVIDENCE_FALSE")).isTrue();
        assertThat(cases.declares("runtime-worker-never-calls-provider", "ACTION_READY_NOT_LOCALLY_CLAIMABLE"))
                .isTrue();
        assertThat(cases.declares("dependency-isolation-from-builder-and-llm", "NO_MODEL_CLIENT")).isTrue();
    }

    @Test
    void theCommittedCasesDocumentIsStrictCanonicalJson() throws IOException {
        byte[] bytes = Files.readAllBytes(
                REPOSITORY_ROOT.resolve(T23EvaluationLoader.CASES_REFERENCE));
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("\n");
        assertThat(text).doesNotContain("\": ");
        assertThat(text).doesNotContain("\", ");
        assertThat(text).startsWith("{\"assetId\":");
    }

    // ------------------------------------------------------------------ tamper detection

    @Test
    void anExtraAssetIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Files.writeString(root.resolve("evaluation/action/t23-v1/extra.json"), "{}");
        assertThatThrownBy(() -> new T23EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.ASSET_SET_INVALID);
    }

    @Test
    void aTamperedCasesDocumentIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T23EvaluationLoader.CASES_REFERENCE);
        Files.writeString(cases, Files.readString(cases, StandardCharsets.UTF_8)
                .replace("BOUNDARY", "BOUNDARZ"));
        assertThatThrownBy(() -> new T23EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.ASSET_HASH_MISMATCH);
    }

    @Test
    void nonCanonicalWhitespaceIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T23EvaluationLoader.CASES_REFERENCE);
        Files.writeString(cases, Files.readString(cases, StandardCharsets.UTF_8) + "\n");
        assertThatThrownBy(() -> new T23EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void anUnknownFieldIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T23EvaluationLoader.CASES_REFERENCE);
        String text = Files.readString(cases, StandardCharsets.UTF_8);
        Files.writeString(cases, text.replaceFirst("\\{\"assetId\":", "{\"additional\":true,\"assetId\":"));
        assertThatThrownBy(() -> new T23EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void aManifestWhoseSelfHashWasNotUpdatedIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path manifest = root.resolve(T23EvaluationLoader.MANIFEST_REFERENCE);
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"caseCount\":57", "\"caseCount\":56"));
        assertThatThrownBy(() -> new T23EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.MANIFEST_HASH_MISMATCH);
    }

    @Test
    void aMissingAssetIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Files.delete(root.resolve(T23EvaluationLoader.CASES_REFERENCE));
        assertThatThrownBy(() -> new T23EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.ASSET_SET_INVALID);
    }

    // ------------------------------------------------------------------ bounded records

    @Test
    void aCaseRecordRejectsAnUnboundedIdentifierOrOutcome() {
        assertThatThrownBy(() -> new T23EvaluationCase("Case-With-Capitals", "IDENTITY", "ACCEPTED",
                List.of("AN_ASSERTION")))
                .isInstanceOf(T23EvaluationException.class);
        assertThatThrownBy(() -> new T23EvaluationCase("valid-id", "lowercase", "ACCEPTED",
                List.of("AN_ASSERTION")))
                .isInstanceOf(T23EvaluationException.class);
        assertThatThrownBy(() -> new T23EvaluationCase("valid-id", "IDENTITY", "lowercase",
                List.of("AN_ASSERTION")))
                .isInstanceOf(T23EvaluationException.class);
        assertThatThrownBy(() -> new T23EvaluationCase("valid-id", "IDENTITY", "ACCEPTED", List.of()))
                .isInstanceOf(T23EvaluationException.class);
        assertThatThrownBy(() -> new T23EvaluationCase("valid-id", "IDENTITY", "ACCEPTED",
                List.of("SAME", "SAME")))
                .isInstanceOf(T23EvaluationException.class);
    }

    @Test
    void theCasesDocumentRejectsARelabelledProductionGateFlagOrADuplicateCase() {
        T23EvaluationCase evaluationCase = new T23EvaluationCase(
                "valid-id", "IDENTITY", "ACCEPTED", List.of("AN_ASSERTION"));
        assertThatThrownBy(() -> new T23EvaluationCases(
                "1.0", "action-t23-cases", "t23-v1", true, List.of(evaluationCase)))
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.CORPUS_INVALID);
        assertThatThrownBy(() -> new T23EvaluationCases(
                "1.0", "action-t23-cases", "t23-v1", false, List.of(evaluationCase, evaluationCase)))
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.CORPUS_INVALID);
    }

    @Test
    void theManifestRejectsARelabelledProductionGateFlag() {
        assertThatThrownBy(() -> new T23EvaluationManifest(
                "1.0", "action-t23-manifest", "t23-v1", true,
                T23EvaluationLoader.CASES_REFERENCE,
                com.company.campaign.shared.canonical.ContentHash.sha256("cases".getBytes(
                        StandardCharsets.UTF_8)),
                57,
                com.company.campaign.shared.canonical.ContentHash.sha256("manifest".getBytes(
                        StandardCharsets.UTF_8))))
                .isInstanceOf(T23EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T23EvaluationErrorCodes.CORPUS_INVALID);
    }

    private static void copyCorpus(Path root) throws IOException {
        Path target = root.resolve("evaluation/action/t23-v1");
        Files.createDirectories(target);
        Files.copy(REPOSITORY_ROOT.resolve(T23EvaluationLoader.MANIFEST_REFERENCE),
                target.resolve("manifest.json"));
        Files.copy(REPOSITORY_ROOT.resolve(T23EvaluationLoader.CASES_REFERENCE), target.resolve("cases.json"));
    }
}