package com.company.campaign.evaluation.runtime.t22;

import com.company.campaign.shared.canonical.ContentHash;
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

/** Focused T22 evaluation tests: the strict, hash-pinned, explicitly non-production phase-A corpus. */
class T22EvaluationLoaderTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

    private static T22EvaluationLoader committedLoader() {
        return new T22EvaluationLoader(REPOSITORY_ROOT);
    }

    // ------------------------------------------------------------------ the committed corpus

    @Test
    void loadsTheFrozenCanonicalCorpus() {
        T22EvaluationCorpus corpus = committedLoader().loadCorpus();

        assertThat(corpus.manifest().assetId()).isEqualTo(T22EvaluationLoader.MANIFEST_ASSET_ID);
        assertThat(corpus.manifest().assetVersion()).isEqualTo(T22EvaluationLoader.ASSET_VERSION);
        assertThat(corpus.manifest().schemaVersion()).isEqualTo(T22EvaluationLoader.SCHEMA_VERSION);
        assertThat(corpus.manifest().productionGateEvidence())
                .as("T22 evaluation evidence is never production Gate evidence")
                .isFalse();
        assertThat(corpus.cases().productionGateEvidence()).isFalse();
        assertThat(corpus.cases().cases()).hasSize(corpus.manifest().caseCount());
        assertThat(corpus.cases().cases()).allSatisfy(evaluationCase -> {
            assertThat(evaluationCase.expectedOutcome())
                    .isIn("ACCEPTED", "DUPLICATE", "CONFLICT", "REJECTED");
            assertThat(evaluationCase.assertions()).isNotEmpty();
        });
    }

    @Test
    void theCorpusCoversEveryPhaseAAndPhaseBRequirementCategory() {
        T22EvaluationCases cases = committedLoader().loadCorpus().cases();

        assertThat(cases.categories()).containsExactlyInAnyOrder(
                "DELIVERY", "MATERIALIZATION", "ENROLLMENT", "ATOMICITY", "CONCURRENCY", "TENANCY", "BOUNDARY",
                "LEASE", "PROGRESSION", "WAIT", "RETRY", "PAUSE", "VERSION", "AGGREGATION", "HANDOFF");
    }

    @Test
    void theCorpusDeclaresTheRequiredPhaseBAssertions() {
        T22EvaluationCases cases = committedLoader().loadCorpus().cases();

        assertThat(cases.declares("claim-is-deterministic-and-attempt-counted",
                "DETERMINISTIC_AVAILABLE_AT_TASK_ID_ORDER")).isTrue();
        assertThat(cases.declares("unexpired-lease-cannot-be-stolen", "UNEXPIRED_LEASE_NOT_STOLEN")).isTrue();
        assertThat(cases.declares("expired-lease-is-reclaimed-as-a-new-attempt",
                "EXPIRED_LEASE_RECLAIMED")).isTrue();
        assertThat(cases.declares("stale-lease-token-completion-refused",
                "EXACT_LEASE_TOKEN_REQUIRED")).isTrue();
        assertThat(cases.declares("stale-participant-cas-refused",
                "PARTICIPANT_REVISION_CAS_ENFORCED")).isTrue();
        assertThat(cases.declares("duplicate-completion-reuses-the-committed-result",
                "COMMITTED_RESULT_REUSED")).isTrue();
        assertThat(cases.declares("restart-reclaims-expired-lease-and-finishes",
                "WORK_COMPLETES_AFTER_RESTART")).isTrue();
        assertThat(cases.declares("concurrent-claims-have-one-winner", "ONE_ACTIVE_LEASE")).isTrue();
        assertThat(cases.declares("concurrent-completions-move-the-participant-once",
                "ONE_PARTICIPANT_MOVE")).isTrue();
        assertThat(cases.declares("condition-unresolved-never-becomes-false", "UNKNOWN_NEVER_FALSE")).isTrue();
        assertThat(cases.declares("frozen-node-capability-version-and-payload-reproved",
                "PAYLOAD_HASH_REPROVED")).isTrue();
        assertThat(cases.declares("wait-stores-one-deadline-and-one-wake-task",
                "ONE_DEADLINE_FROM_FROZEN_DURATION_AND_ANCHOR")).isTrue();
        assertThat(cases.declares("wait-deadline-is-never-recomputed",
                "DEADLINE_NOT_RECOMPUTED_ON_DUPLICATE_WAKE")).isTrue();
        assertThat(cases.declares("wait-deadline-is-never-recomputed",
                "DEADLINE_NOT_SHIFTED_BY_PAUSE")).isTrue();
        assertThat(cases.declares("technical-failure-consumes-bounded-backoff",
                "FIVE_SECOND_THEN_THIRTY_SECOND_BACKOFF")).isTrue();
        assertThat(cases.declares("exhausted-attempts-dead-letter-and-need-review",
                "DEAD_LETTER_AFTER_EXHAUSTION")).isTrue();
        assertThat(cases.declares("non-retryable-business-failure-is-not-retried",
                "BUSINESS_FAILURE_NOT_RETRIED")).isTrue();
        assertThat(cases.declares("pause-guards-the-next-task-and-resume-releases-it",
                "NEXT_TASK_GUARDED_DEFERRED")).isTrue();
        assertThat(cases.declares("pause-guards-the-next-task-and-resume-releases-it",
                "RESUME_RELEASES_DUE_WORK")).isTrue();
        assertThat(cases.declares("existing-execution-keeps-its-frozen-version",
                "EXISTING_EXECUTION_KEEPS_FROZEN_VERSION")).isTrue();
        assertThat(cases.declares("aggregate-is-deterministic-for-every-terminal-mix",
                "PARTIAL_FAILED_MIX_DETERMINISTIC")).isTrue();
        assertThat(cases.declares("aggregate-refuses-completion-over-unresolved-work",
                "NO_COMPLETION_OVER_UNRESOLVED_PARTICIPANT")).isTrue();
        assertThat(cases.declares("message-and-reward-create-only-action-ready-work",
                "MESSAGE_CREATES_ONLY_ACTION_READY")).isTrue();
        assertThat(cases.declares("action-ready-work-is-not-claimed-locally",
                "ACTION_READY_NOT_LOCALLY_CLAIMABLE")).isTrue();
        assertThat(cases.declares("cross-tenant-claim-and-advance-isolated",
                "CROSS_TENANT_EXECUTION_NOT_ADVANCED")).isTrue();
        assertThat(cases.declares("no-provider-outcome-or-budget-side-effect",
                "NO_BUDGET_RESERVATION")).isTrue();
    }

    @Test
    void theCorpusDeclaresTheRequiredPhaseAAssertions() {
        T22EvaluationCases cases = committedLoader().loadCorpus().cases();

        assertThat(cases.declares("registration-delivery-recorded", "INBOX_RECEIPT_WRITTEN")).isTrue();
        assertThat(cases.declares("registration-exact-duplicate", "NOTHING_WRITTEN")).isTrue();
        assertThat(cases.declares("occurrence-changed-hash-conflict", "NO_SECOND_EXECUTION")).isTrue();
        assertThat(cases.declares("occurrence-skipped-creates-no-execution", "NO_EXECUTION_CREATED")).isTrue();
        assertThat(cases.declares("fixed-asof-not-delivery-time", "AS_OF_EQUALS_NOMINAL_INSTANT")).isTrue();
        assertThat(cases.declares("fixed-asof-not-delivery-time",
                "DELAYED_DELIVERY_DOES_NOT_EXPAND_AUDIENCE")).isTrue();
        assertThat(cases.declares("stale-audience-fails-closed", "ZERO_SNAPSHOT_MEMBERS")).isTrue();
        assertThat(cases.declares("recipient-limit-overflow-refused", "MEMBER_SET_NOT_TRUNCATED")).isTrue();
        assertThat(cases.declares("limit-resolved-from-frozen-governance",
                "MAX_RECIPIENTS_FROM_FROZEN_COMPONENT")).isTrue();
        assertThat(cases.declares("zero-audience-completes", "EXECUTION_COMPLETED")).isTrue();
        assertThat(cases.declares("enrollment-dedupe-across-versions",
                "ENROLLMENT_UNIQUE_ACROSS_PUBLISHED_VERSIONS")).isTrue();
        assertThat(cases.declares("injected-store-failure-rolls-back", "NOTHING_COMMITTED")).isTrue();
        assertThat(cases.declares("injected-audit-failure-rolls-back", "NO_AUDIT_RECORD")).isTrue();
        assertThat(cases.declares("concurrent-materialization-single-winner", "CAS_REVISION_ENFORCED")).isTrue();
        assertThat(cases.declares("no-builder-or-llm-dependency", "NO_LLM_DEPENDENCY")).isTrue();
        assertThat(cases.declares("pii-redaction", "MEMBER_VALUE_NOT_IN_AUDIT")).isTrue();
    }

    @Test
    void theCommittedCasesDocumentIsStrictCanonicalJson() throws IOException {
        byte[] bytes = Files.readAllBytes(REPOSITORY_ROOT.resolve(T22EvaluationLoader.CASES_REFERENCE));
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertThat(text).doesNotContain("\n").doesNotContain(": ").doesNotContain(", ");
        assertThat(text).startsWith("{\"assetId\":");
    }

    // ------------------------------------------------------------------ tamper detection

    @Test
    void anExtraAssetIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Files.writeString(root.resolve(T22EvaluationLoader.ROOT_REFERENCE).resolve("extra.json"), "{}");

        assertThatThrownBy(() -> new T22EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.ASSET_SET_INVALID);
    }

    @Test
    void aTamperedCasesDocumentIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T22EvaluationLoader.CASES_REFERENCE);
        String text = Files.readString(cases, StandardCharsets.UTF_8);
        Files.writeString(cases, text.replace("TENANCY", "TENANCX"));

        assertThatThrownBy(() -> new T22EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.ASSET_HASH_MISMATCH);
    }

    @Test
    void nonCanonicalWhitespaceIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T22EvaluationLoader.CASES_REFERENCE);
        Files.writeString(cases, Files.readString(cases, StandardCharsets.UTF_8) + "\n");

        assertThatThrownBy(() -> new T22EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void anUnknownFieldIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T22EvaluationLoader.CASES_REFERENCE);
        String text = Files.readString(cases, StandardCharsets.UTF_8);
        Files.writeString(cases, text.replaceFirst("\\{\"assetId\"", "{\"additional\":true,\"assetId\""));

        assertThatThrownBy(() -> new T22EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.ASSET_NON_CANONICAL);
    }

    @Test
    void aManifestWhoseSelfHashWasNotUpdatedIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path manifest = root.resolve(T22EvaluationLoader.MANIFEST_REFERENCE);
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(manifest, text.replace("\"caseCount\":63", "\"caseCount\":62"));

        assertThatThrownBy(() -> new T22EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.MANIFEST_HASH_MISMATCH);
    }

    @Test
    void aMissingAssetIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Files.delete(root.resolve(T22EvaluationLoader.CASES_REFERENCE));

        assertThatThrownBy(() -> new T22EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.ASSET_SET_INVALID);
    }

    // ------------------------------------------------------------------ bounded records

    @Test
    void aCaseRecordRejectsAnUnboundedIdentifierOrOutcome() {
        assertThatThrownBy(() -> new T22EvaluationCase(
                "Not A Kebab Id", "DELIVERY", "ACCEPTED", List.of("INBOX_RECEIPT_WRITTEN")))
                .isInstanceOf(T22EvaluationException.class);
        assertThatThrownBy(() -> new T22EvaluationCase(
                "valid-id", "delivery", "ACCEPTED", List.of("INBOX_RECEIPT_WRITTEN")))
                .isInstanceOf(T22EvaluationException.class);
        assertThatThrownBy(() -> new T22EvaluationCase(
                "valid-id", "DELIVERY", "accepted", List.of("INBOX_RECEIPT_WRITTEN")))
                .isInstanceOf(T22EvaluationException.class);
        assertThatThrownBy(() -> new T22EvaluationCase("valid-id", "DELIVERY", "ACCEPTED", List.of()))
                .isInstanceOf(T22EvaluationException.class);
        assertThatThrownBy(() -> new T22EvaluationCase(
                "valid-id", "DELIVERY", "ACCEPTED",
                List.of("INBOX_RECEIPT_WRITTEN", "INBOX_RECEIPT_WRITTEN")))
                .isInstanceOf(T22EvaluationException.class);
    }

    @Test
    void theCasesDocumentRejectsARelabelledProductionGateFlagOrADuplicateCase() {
        T22EvaluationCase one = new T22EvaluationCase(
                "case-one", "DELIVERY", "ACCEPTED", List.of("INBOX_RECEIPT_WRITTEN"));

        assertThatThrownBy(() -> new T22EvaluationCases(
                T22EvaluationLoader.SCHEMA_VERSION, T22EvaluationLoader.CASES_ASSET_ID,
                T22EvaluationLoader.ASSET_VERSION, true, List.of(one)))
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.CORPUS_INVALID);
        assertThatThrownBy(() -> new T22EvaluationCases(
                T22EvaluationLoader.SCHEMA_VERSION, T22EvaluationLoader.CASES_ASSET_ID,
                T22EvaluationLoader.ASSET_VERSION, false, List.of(one, one)))
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.CORPUS_INVALID);
    }

    @Test
    void theManifestRejectsARelabelledProductionGateFlag() {
        assertThatThrownBy(() -> new T22EvaluationManifest(
                T22EvaluationLoader.SCHEMA_VERSION, T22EvaluationLoader.MANIFEST_ASSET_ID,
                T22EvaluationLoader.ASSET_VERSION, true, T22EvaluationLoader.CASES_REFERENCE,
                ContentHash.sha256("x".getBytes(StandardCharsets.UTF_8)), 1,
                ContentHash.sha256("y".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(T22EvaluationException.class)
                .hasFieldOrPropertyWithValue("code", T22EvaluationErrorCodes.CORPUS_INVALID);
    }

    // ------------------------------------------------------------------ helpers

    private static void copyCorpus(Path root) throws IOException {
        Path target = root.resolve(T22EvaluationLoader.ROOT_REFERENCE);
        Files.createDirectories(target);
        for (String file : Set.of("manifest.json", "cases.json")) {
            Files.copy(REPOSITORY_ROOT.resolve(T22EvaluationLoader.ROOT_REFERENCE).resolve(file),
                    target.resolve(file));
        }
    }
}