package com.company.campaign.evaluation.segment;

import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.validation.EvaluationValidationReport;
import com.company.campaign.evaluation.segment.validation.GateReadinessValidator;
import com.company.campaign.evaluation.testsupport.EvaluationCorpusFixture;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review evidence as a first-class asset: the strict contract, the loader binding and the two readiness
 * verdicts a corpus can receive.
 */
class SegmentEvaluationReviewTest {

    private static final Instant DECISION_TIME = Instant.parse("2026-10-02T09:00:00Z");

    @TempDir
    Path repositoryRoot;

    // --- the strict contract ----------------------------------------------------------------------

    @Test
    void pendingEntriesForbidDecisionFieldsAndDecidedEntriesRequireThem() {
        CaseReviewEntry pending = CaseReviewEntry.pending(ReviewRole.PRODUCT, "Awaiting product review.");
        assertThat(pending.isDecided()).isFalse();
        assertThat(pending.reviewerRef()).isNull();
        assertThat(pending.evidenceRef()).isNull();
        assertThat(pending.decisionTime()).isNull();

        ContractAssertions.assertFailsWithCode(
                () -> new CaseReviewEntry(ReviewRole.PRODUCT, ReviewStatus.PENDING, "reviewer-p-01", null,
                        null, "Fabricated decision."),
                EvaluationErrorCodes.REVIEW_DECISION_FABRICATED);
        ContractAssertions.assertFailsWithCode(
                () -> new CaseReviewEntry(ReviewRole.DATA, ReviewStatus.APPROVED, "reviewer-d-01",
                        "review-evidence-d-001", null, "Missing decision time."),
                EvaluationErrorCodes.REVIEW_ENTRY_INVALID);
    }

    @Test
    void reviewerReferencesAreBoundedAndRejectSensitiveShapes() {
        // An email address cannot be stored: '@' is outside the reference grammar.
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEntry.decided(ReviewRole.TEST, ReviewStatus.APPROVED,
                        "reviewer@example.com", "review-evidence-test-001", DECISION_TIME, "Approved."),
                EvaluationErrorCodes.REVIEW_REFERENCE_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEntry.decided(ReviewRole.TEST, ReviewStatus.APPROVED,
                        "reviewer-test-01", "", DECISION_TIME, "Approved."),
                EvaluationErrorCodes.REVIEW_REFERENCE_INVALID);
    }

    @Test
    void reviewNotesAreBoundedAndCannotCarryStructuredPayloads() {
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEntry.pending(ReviewRole.PRODUCT, "  "),
                EvaluationErrorCodes.REVIEW_ENTRY_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEntry.pending(ReviewRole.PRODUCT, "{\"raw\":\"payload\"}"),
                EvaluationErrorCodes.REVIEW_NOTES_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEntry.pending(ReviewRole.PRODUCT, "x".repeat(400)),
                EvaluationErrorCodes.REVIEW_NOTES_INVALID);
    }

    @Test
    void evidenceRequiresOneEntryPerRoleInDeterministicOrder() {
        ContentHash hash = ContentHash.sha256("case".getBytes(StandardCharsets.UTF_8));
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEvidence.of(EvaluationVersions.CURRENT, "case-a", hash, hash,
                        List.of(CaseReviewEntry.pending(ReviewRole.PRODUCT, "Only one role."))),
                EvaluationErrorCodes.REVIEW_ROLE_MISSING);

        CaseReviewEntry duplicated = CaseReviewEntry.pending(ReviewRole.PRODUCT, "Duplicated role.");
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEvidence.of(EvaluationVersions.CURRENT, "case-a", hash, hash, List.of(
                        duplicated, duplicated, CaseReviewEntry.pending(ReviewRole.TEST, "Test role."))),
                EvaluationErrorCodes.REVIEW_ROLE_DUPLICATE);

        // Declared out of order, still stored in role order and hashed canonically.
        CaseReviewEvidence review = CaseReviewEvidence.of(EvaluationVersions.CURRENT, "case-a", hash, hash,
                List.of(CaseReviewEntry.pending(ReviewRole.TEST, "Test role."),
                        CaseReviewEntry.pending(ReviewRole.DATA, "Data role."),
                        CaseReviewEntry.pending(ReviewRole.PRODUCT, "Product role.")));
        assertThat(review.reviews()).extracting(CaseReviewEntry::role)
                .containsExactly(ReviewRole.PRODUCT, ReviewRole.DATA, ReviewRole.TEST);
        review.requireValidContentHash();

        CaseReviewEvidence tampered = review.withReviewHash(ContentHash.sha256("other".getBytes()));
        ContractAssertions.assertFailsWithCode(
                tampered::requireValidContentHash, EvaluationErrorCodes.REVIEW_HASH_MISMATCH);
    }

    // --- the loader binding and readiness ---------------------------------------------------------

    @Test
    void loaderBindsReviewEvidenceForEveryCase() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        CorpusBundle bundle = loader().loadCorpus();

        CaseReviewEvidence review = bundle.reviewFor("case-attr-001");
        assertThat(review).isNotNull();
        assertThat(review.isFullyApproved()).isTrue();
        assertThat(bundle.manifest().cases().get(0).hasReviewEvidence()).isTrue();
    }

    @Test
    void aCorpusWithoutReviewEvidenceIsNotGateReadyHoweverManyCasesItHas() {
        EvaluationCorpusFixture.in(repositoryRoot).omitReviews()
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        EvaluationValidationReport report = new GateReadinessValidator().validate(loader().loadCorpus());

        assertThat(report.isGateReady()).isFalse();
        assertThat(report.issueCodes()).contains(EvaluationErrorCodes.REVIEW_EVIDENCE_MISSING);
    }

    @Test
    void pendingReviewsMakeTheCandidateCompleteButNotGateReady() {
        EvaluationCorpusFixture.in(repositoryRoot).reviewStatus(ReviewStatus.PENDING)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        CorpusBundle bundle = loader().loadCorpus();
        GateReadinessValidator validator = new GateReadinessValidator();

        // Pending review is structurally valid, so candidate completeness does not report it.
        assertThat(validator.validateCandidate(bundle).issueCodes())
                .doesNotContain(EvaluationErrorCodes.REVIEW_PENDING);
        EvaluationValidationReport gate = validator.validate(bundle);
        assertThat(gate.isGateReady()).isFalse();
        assertThat(gate.issueCodes()).contains(EvaluationErrorCodes.REVIEW_PENDING);
        assertThat(gate.issueCodes()).doesNotContain(EvaluationErrorCodes.REVIEW_EVIDENCE_MISSING);
    }

    @Test
    void rejectedReviewsPreventGateReadiness() {
        EvaluationCorpusFixture.in(repositoryRoot).reviewStatus(ReviewStatus.REJECTED)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        EvaluationValidationReport report = new GateReadinessValidator().validate(loader().loadCorpus());

        assertThat(report.isGateReady()).isFalse();
        assertThat(report.issueCodes()).contains(EvaluationErrorCodes.REVIEW_REJECTED);
    }

    @Test
    void aReviewThatBindsAnotherCaseIsReported() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();
        CorpusBundle bundle = loader().loadCorpus();
        CaseReviewEvidence review = bundle.reviewFor("case-attr-001");
        CaseReviewEvidence wrongCase = CaseReviewEvidence.of(EvaluationVersions.CURRENT, "case-other-001",
                review.caseHash(), review.expectedHash(), review.reviews());

        List<CaseReviewEvidence> reviews = new ArrayList<>();
        reviews.add(wrongCase);
        CorpusBundle mismatched = rebuild(bundle, reviews);

        assertThat(new GateReadinessValidator().validateCandidate(mismatched).issueCodes())
                .contains(EvaluationErrorCodes.REVIEW_CASE_MISMATCH);
    }

    // --- file integrity ---------------------------------------------------------------------------

    @Test
    void aReviewFileWhoseBytesDoNotMatchItsDeclaredHashIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot).reviewStatus(ReviewStatus.PENDING)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();
        Path review = repositoryRoot.resolve(EvaluationCorpusFixture.reference(
                "reviews/case-attr-001.json"));
        write(review, read(review).replace("\"PENDING\"", "\"REJECTED\""));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadCorpus(), EvaluationErrorCodes.HASH_MISMATCH);
    }

    @Test
    void aReviewFileThatIsNotItsOwnCanonicalSerializationIsRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();
        Path review = repositoryRoot.resolve(EvaluationCorpusFixture.reference(
                "reviews/case-attr-001.json"));
        write(review, " " + read(review));

        ContractAssertions.assertFailsWithCode(
                () -> loader().loadReview(EvaluationCorpusFixture.reference("reviews/case-attr-001.json"),
                        null),
                EvaluationErrorCodes.ASSET_NOT_CANONICAL);
    }

    @Test
    void unknownFieldsAndDuplicateKeysInAReviewFileAreRejected() {
        EvaluationCorpusFixture.in(repositoryRoot)
                .addCase(EvaluationCorpusFixture.caseSpec("case-attr-001",
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();
        String reference = EvaluationCorpusFixture.reference("reviews/case-attr-001.json");
        Path review = repositoryRoot.resolve(reference);
        String canonical = read(review);

        write(review, canonical.substring(0, canonical.length() - 1) + ",\"injected\":true}");
        ContractAssertions.assertFailsWithCode(
                () -> loader().loadReview(reference, null), ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);

        write(review, "{\"caseId\":\"injected\"," + canonical.substring(1));
        ContractAssertions.assertFailsWithCode(
                () -> loader().loadReview(reference, null), ContractErrorCodes.JSON_DUPLICATE_KEY);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private SegmentEvaluationLoader loader() {
        return new SegmentEvaluationLoader(repositoryRoot);
    }

    private static CorpusBundle rebuild(CorpusBundle bundle, List<CaseReviewEvidence> reviews) {
        return new CorpusBundle(bundle.manifest(), bundle.metadataCatalog(), bundle.fixtureCatalog(),
                bundle.cases(), bundle.expectedResults(), reviews, bundle.metadataPayloads(),
                bundle.executionContexts());
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
}