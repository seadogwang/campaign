package com.company.campaign.evaluation.readiness.t25;

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
 * Focused T25 readiness evaluation tests: the frozen corpus loads, covers every required category and
 * assertion, is strict canonical JSON with closed category and outcome vocabularies, and refuses a tampered
 * case, an extra file, a stray whitespace byte, an unknown field, an unsupported enum value, a duplicate case
 * id, a relabelled production flag and a manifest whose self-hash was not updated.
 *
 * <p>The corpus is regression evidence only. It cannot satisfy any readiness requirement, and no case in it
 * claims that a browser, PostgreSQL or production infrastructure run happened.
 */
class T25EvaluationLoaderTest {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

    private static T25EvaluationLoader committedLoader() {
        return new T25EvaluationLoader(REPOSITORY_ROOT);
    }

    // ------------------------------------------------------------------ the committed corpus

    @Test
    void loadsTheFrozenCanonicalCorpus() {
        T25EvaluationCorpus corpus = committedLoader().loadCorpus();

        assertThat(T25EvaluationLoader.ASSET_VERSION).isEqualTo("t25-v1");
        assertThat(corpus.manifest().assetId()).isEqualTo("readiness-t25-manifest");
        assertThat(corpus.cases().assetId()).isEqualTo("readiness-t25-cases");
        assertThat(corpus.manifest().productionGateEvidence()).isFalse();
        assertThat(corpus.cases().productionGateEvidence()).isFalse();
        assertThat(corpus.manifest().caseCount()).isEqualTo(corpus.cases().cases().size());
        assertThat(corpus.cases().cases()).hasSize(63);
        assertThat(corpus.cases().cases()).allSatisfy(evaluationCase ->
                assertThat(evaluationCase.assertions()).isNotEmpty());
    }

    @Test
    void theCorpusCoversEveryRequiredRequirementCategory() {
        T25EvaluationCorpus corpus = committedLoader().loadCorpus();

        assertThat(corpus.cases().categories()).containsExactlyInAnyOrder(
                "LOCAL", "AI", "LIVE", "EVIDENCE", "ORDERING", "MIGRATION",
                "PERSISTENCE", "AUDIT", "GAPS", "ISOLATION", "INFRASTRUCTURE");
    }

    @Test
    void theCorpusDeclaresEveryRequiredCaseArea() {
        T25EvaluationCases cases = committedLoader().loadCorpus().cases();

        assertDeclares(cases, "local-assessment-ready-from-accepted-evidence", "STATUS_READY");
        assertDeclares(cases, "local-assessment-blocked-when-requirement-absent", "NO_OPTIMISTIC_DEFAULT");
        assertDeclares(cases, "local-assessment-invalid-on-contradictory-evidence", "STATUS_INVALID");
        assertDeclares(cases, "ai-gate-missing-blocks-readiness", "AI_GATE_APPROVAL_MISSING");
        assertDeclares(cases, "ai-gate-unapproved-blocks-readiness", "AI_GATE_NOT_APPROVED");
        assertDeclares(cases, "ai-gate-failed-blocks-readiness", "OUTCOME_FAILED");
        assertDeclares(cases, "ai-gate-invalid-blocks-readiness", "OUTCOME_INVALID");
        assertDeclares(cases, "ai-gate-development-only-blocks-readiness", "DEVELOPMENT_ONLY_CORPUS");
        assertDeclares(cases, "ai-gate-stale-pins-block-readiness", "PIN_CHANGE_REQUIRES_EVALUATION");
        assertDeclares(cases, "ai-gate-current-approved-passed-satisfies", "EXACT_PINS_CURRENT");
        assertDeclares(cases, "live-refuses-simulated-provider", "SIMULATED_ORIGIN_REFUSED");
        assertDeclares(cases, "live-refuses-fixture-evidence", "FIXTURE_ORIGIN_REFUSED");
        assertDeclares(cases, "live-refuses-synthetic-evidence", "SYNTHETIC_ORIGIN_REFUSED");
        assertDeclares(cases, "live-refuses-development-only-evidence", "DEVELOPMENT_ONLY_REFUSED");
        assertDeclares(cases, "live-refuses-evaluation-assets", "EVALUATION_ASSET_REFUSED");
        assertDeclares(cases, "live-requires-production-gate-evidence-stamp",
                "PRODUCTION_ONLY_REQUIRES_EXPLICIT_STAMP");
        assertDeclares(cases, "expired-evidence-never-satisfies", "EXPIRED_EVIDENCE_BLOCKED");
        assertDeclares(cases, "unknown-requirement-code-refused", "CLOSED_CATALOG");
        assertDeclares(cases, "duplicate-evidence-makes-level-invalid", "DUPLICATE_DETECTED");
        assertDeclares(cases, "conflicting-identity-is-a-stable-conflict",
                "IDENTITY_REUSED_WITH_DIFFERENT_MATERIAL");
        assertDeclares(cases, "lower-level-inheritance-cannot-be-skipped", "LOCAL_REQUIRED_FOR_AI");
        assertDeclares(cases, "blocker-order-is-deterministic", "CATALOG_ORDER");
        assertDeclares(cases, "migration-checksum-change-refused", "CHECKSUM_MISMATCH_REFUSED");
        assertDeclares(cases, "migration-destructive-statement-refused", "DROP_REFUSED");
        assertDeclares(cases, "migration-set-is-ordered-and-pinned", "CHECKSUMS_PINNED");
        assertDeclares(cases, "append-only-store-writes-nothing-on-stale-revision", "STALE_REVISION");
        assertDeclares(cases, "idempotent-replay-writes-nothing", "SAME_IDENTITY_SAME_MATERIAL");
        assertDeclares(cases, "audit-excludes-payloads-and-identifiers", "NO_MEMBER_IDENTIFIER");
        assertDeclares(cases, "audit-rolls-back-with-the-transaction", "AUDIT_FAILURE_ROLLS_BACK");
        assertDeclares(cases, "observability-labels-are-closed-codes", "LOW_CARDINALITY");
        assertDeclares(cases, "timeout-and-query-cost-gaps-are-blockers", "TIMEOUT_GAP");
        assertDeclares(cases, "schedule-coverage-local-only-blocks-live", "SCHEDULE_COVERAGE_LOCAL_ONLY");
        assertDeclares(cases, "rollback-proof-gap-blocks-live", "ROLLBACK_PROOF_ABSENT");
        assertDeclares(cases, "provider-idempotency-gap-blocks-live", "PROVIDER_CAPABILITY_INCOMPLETE");
        assertDeclares(cases, "runtime-imports-no-builder-or-llm-client", "NO_MODEL_CLIENT");
        assertDeclares(cases, "builder-outage-cannot-stop-persisted-scenario", "NO_BUILDER_DEPENDENCY");
        assertDeclares(cases, "readiness-evidence-is-not-a-model-callable-tool", "NO_HTTP_CONTROLLER");
        assertDeclares(cases, "t24-frontend-cannot-assert-trusted-evidence", "READINESS_CLAIM_REFUSED");
        assertDeclares(cases, "no-browser-claim-when-no-run-occurred", "BROWSER_RUN_ABSENT");
        assertDeclares(cases, "no-postgresql-claim-when-no-run-occurred", "POSTGRESQL_NOT_EXERCISED");
        assertDeclares(cases, "no-production-claim-outside-production", "PRODUCTION_ENVIRONMENT_REQUIRED");
        assertDeclares(cases, "evaluation-assets-are-not-production-evidence", "PRODUCTION_GATE_EVIDENCE_FALSE");
    }

    private static void assertDeclares(T25EvaluationCases cases, String caseId, String assertion) {
        assertThat(cases.declares(caseId, assertion))
                .describedAs("%s declares %s", caseId, assertion)
                .isTrue();
    }

    @Test
    void theCommittedCasesDocumentIsStrictCanonicalJson() throws IOException {
        byte[] bytes = Files.readAllBytes(REPOSITORY_ROOT.resolve(T25EvaluationLoader.CASES_REFERENCE));
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
        Files.writeString(root.resolve("evaluation/readiness/t25-v1/extra.json"), "{}");

        assertThatThrownBy(() -> new T25EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.ASSET_SET_INVALID));
    }

    @Test
    void aMissingAssetIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Files.delete(root.resolve(T25EvaluationLoader.CASES_REFERENCE));

        assertThatThrownBy(() -> new T25EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.ASSET_SET_INVALID));
    }

    @Test
    void aTamperedCasesDocumentIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T25EvaluationLoader.CASES_REFERENCE);
        Files.writeString(cases, Files.readString(cases, StandardCharsets.UTF_8)
                .replace("\"id\":\"no-browser-claim-when-no-run-occurred\"",
                        "\"id\":\"no-browser-claim-when-no-run-occurred2\""));

        assertThatThrownBy(() -> new T25EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.ASSET_HASH_MISMATCH));
    }

    @Test
    void nonCanonicalWhitespaceIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T25EvaluationLoader.CASES_REFERENCE);
        Files.writeString(cases, Files.readString(cases, StandardCharsets.UTF_8) + "\n");

        assertThatThrownBy(() -> new T25EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.ASSET_NON_CANONICAL));
    }

    @Test
    void anUnknownFieldIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path cases = root.resolve(T25EvaluationLoader.CASES_REFERENCE);
        String text = Files.readString(cases, StandardCharsets.UTF_8);
        Files.writeString(cases, text.replaceFirst("\\{\"assetId\":",
                "{\"additional\":true,\"assetId\":"));

        assertThatThrownBy(() -> new T25EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.ASSET_NON_CANONICAL));
    }

    @Test
    void aRelabelledProductionGateFlagIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path manifest = root.resolve(T25EvaluationLoader.MANIFEST_REFERENCE);
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"productionGateEvidence\":false", "\"productionGateEvidence\":true"));

        assertThatThrownBy(() -> new T25EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T25EvaluationException.class);
    }

    @Test
    void aManifestWhoseSelfHashWasNotUpdatedIsRefused(@TempDir Path root) throws IOException {
        copyCorpus(root);
        Path manifest = root.resolve(T25EvaluationLoader.MANIFEST_REFERENCE);
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"caseCount\":63", "\"caseCount\":62"));

        assertThatThrownBy(() -> new T25EvaluationLoader(root).loadCorpus())
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.MANIFEST_HASH_MISMATCH));
    }

    // ------------------------------------------------------------------ bounded records

    @Test
    void aCaseRecordRejectsAnUnknownCategoryAnUnknownOutcomeARepeatedAssertionOrAnUnboundedId() {
        assertThatThrownBy(() -> new T25EvaluationCase(
                "valid-id", "NOT_A_CATEGORY", "ACCEPTED", List.of("AN_ASSERTION")))
                .isInstanceOf(T25EvaluationException.class);
        assertThatThrownBy(() -> new T25EvaluationCase(
                "valid-id", "LOCAL", "NOT_AN_OUTCOME", List.of("AN_ASSERTION")))
                .isInstanceOf(T25EvaluationException.class);
        assertThatThrownBy(() -> new T25EvaluationCase(
                "Case-With-Capitals", "LOCAL", "ACCEPTED", List.of("AN_ASSERTION")))
                .isInstanceOf(T25EvaluationException.class);
        assertThatThrownBy(() -> new T25EvaluationCase(
                "valid-id", "LOCAL", "ACCEPTED", List.of()))
                .isInstanceOf(T25EvaluationException.class);
        assertThatThrownBy(() -> new T25EvaluationCase(
                "valid-id", "LOCAL", "ACCEPTED", List.of("SAME", "SAME")))
                .isInstanceOf(T25EvaluationException.class);
        assertThat(T25EvaluationCase.CATEGORIES).contains("LIVE");
        assertThat(T25EvaluationCase.OUTCOMES).contains("BLOCKED");
    }

    @Test
    void theCasesDocumentRejectsARelabelledProductionGateFlagOrADuplicateCase() {
        T25EvaluationCase evaluationCase = new T25EvaluationCase(
                "valid-id", "LOCAL", "ACCEPTED", List.of("AN_ASSERTION"));

        assertThatThrownBy(() -> new T25EvaluationCases(
                "1.0", "readiness-t25-cases", "t25-v1", true, List.of(evaluationCase)))
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.CORPUS_INVALID));
        assertThatThrownBy(() -> new T25EvaluationCases(
                "1.0", "readiness-t25-cases", "t25-v1", false,
                List.of(evaluationCase, evaluationCase)))
                .isInstanceOf(T25EvaluationException.class);
    }

    @Test
    void theManifestRejectsARelabelledProductionGateFlag() {
        assertThatThrownBy(() -> new T25EvaluationManifest(
                "1.0", "readiness-t25-manifest", "t25-v1", true,
                T25EvaluationLoader.CASES_REFERENCE,
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "cases".getBytes(StandardCharsets.UTF_8)),
                63,
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "manifest".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(T25EvaluationException.class)
                .satisfies(thrown -> assertThat(((T25EvaluationException) thrown).code())
                        .isEqualTo(T25EvaluationErrorCodes.CORPUS_INVALID));
    }

    private static void copyCorpus(Path root) throws IOException {
        Path target = root.resolve("evaluation/readiness/t25-v1");
        Files.createDirectories(target);
        Files.copy(REPOSITORY_ROOT.resolve(T25EvaluationLoader.MANIFEST_REFERENCE),
                target.resolve("manifest.json"));
        Files.copy(REPOSITORY_ROOT.resolve(T25EvaluationLoader.CASES_REFERENCE),
                target.resolve("cases.json"));
    }
}