package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.validation.EvaluationStage;
import com.company.campaign.evaluation.segment.validation.EvaluationValidationReport;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused validator tests for the committed T18 Existing Audience Reference corpus.
 *
 * <p>They prove four things. The checked-in corpus is candidate-complete under its own rules, with every
 * required category covered at or above the declared minimum and at least the T18 floor of forty cases. A
 * corpus that asks for less than the floor, that declares fewer cases than it asks for, that under-fills a
 * required category or that carries a case in a category it does not require is refused with a specific code.
 * A case that does not declare what its scenario needs, or that asks exactly what another case asks, is
 * refused. And no review state can be overstated: a manifest that claims human review complete over pending
 * ledgers, a ledger that records a decision under a pending manifest, a ledger bound to another revision and a
 * case with no ledger at all are each refused, so an unearned approval is reportable rather than trusted.
 */
class ReferenceCorpusValidatorTest {

    @Test
    void theCommittedCorpusIsCandidateComplete() {
        EvaluationValidationReport report =
                new ReferenceCorpusValidator().validateCandidate(bundle());

        assertThat(report.issues()).isEmpty();
        assertThat(report.stage()).isEqualTo(EvaluationStage.CANDIDATE);
        assertThat(report.isCandidateComplete()).isTrue();
    }

    @Test
    void everyRequiredCategoryIsCoveredAtOrAboveTheDeclaredMinimum() {
        ReferenceCorpusBundle bundle = bundle();
        Map<ReferenceCategory, Integer> counts = countsByCategory(bundle);

        assertThat(bundle.manifest().requiredCategories())
                .containsExactlyElementsOf(ReferenceCategory.ordered());
        assertThat(bundle.cases()).hasSizeGreaterThanOrEqualTo(ReferenceCorpusVersions.GATE_MINIMUM_CASES);
        assertThat(bundle.manifest().minimumCases())
                .isGreaterThanOrEqualTo(ReferenceCorpusVersions.GATE_MINIMUM_CASES);
        for (ReferenceCategory category : bundle.manifest().requiredCategories()) {
            assertThat(counts.getOrDefault(category, 0)).as(category.name())
                    .isGreaterThanOrEqualTo(bundle.manifest().minimumCasesPerCategory());
        }
    }

    @Test
    void aCorpusThatAsksForLessThanTheGateFloorIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceCorpusBundle belowFloor = withManifest(bundle, manifestWith(
                bundle.manifest(), ReferenceCorpusVersions.GATE_MINIMUM_CASES - 1, null, null, null));

        assertThat(new ReferenceCorpusValidator().validateCandidate(belowFloor).issueCodes())
                .containsExactly(ReferenceCorpusErrorCodes.GATE_MINIMUM_TOO_LOW);
    }

    @Test
    void aCorpusWithFewerCasesThanItsOwnMinimumIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceCorpusBundle shortCorpus = withManifest(bundle, manifestWith(
                bundle.manifest(), bundle.cases().size() + 1, null, null, null));
        ReferenceCorpusManifest manifest = shortCorpus.manifest();
        assertThat(manifest.minimumCases())
                .as("the manifest must still ask for the Gate floor, so only the count rule fires")
                .isGreaterThanOrEqualTo(ReferenceCorpusVersions.GATE_MINIMUM_CASES);

        assertThat(new ReferenceCorpusValidator().validateCandidate(shortCorpus).issueCodes())
                .containsExactly(ReferenceCorpusErrorCodes.CASE_COUNT_BELOW_MINIMUM);
    }

    @Test
    void aRequiredCategoryBelowItsMinimumIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        int observedMinimum = countsByCategory(bundle).values().stream()
                .mapToInt(Integer::intValue)
                .min()
                .orElseThrow();
        ReferenceCorpusBundle demanding = withManifest(bundle, manifestWith(
                bundle.manifest(), null, observedMinimum + 1, null, null));

        assertThat(new ReferenceCorpusValidator().validateCandidate(demanding).issueCodes())
                .contains(ReferenceCorpusErrorCodes.CATEGORY_UNDER_MINIMUM);
    }

    @Test
    void aCaseInAnUnrequiredCategoryIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        List<ReferenceCategory> all = ReferenceCategory.ordered();
        List<ReferenceCategory> reduced = all.subList(0, all.size() - 1);
        ReferenceCorpusBundle narrowed = withManifest(bundle, manifestWith(
                bundle.manifest(), null, null, null, reduced));

        assertThat(new ReferenceCorpusValidator().validateCandidate(narrowed).issueCodes())
                .contains(ReferenceCorpusErrorCodes.CATEGORY_UNEXPECTED)
                .doesNotContain(ReferenceCorpusErrorCodes.CATEGORY_MISSING);
    }

    @Test
    void anUnderSpecifiedDocumentCaseIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        int index = indexOf(bundle, caseInput -> caseInput.scenario() == ReferenceScenario.REFERENCE_DOCUMENT
                && caseInput.request().recheck() == null
                && bundle.expectedFor(caseInput.caseId()).recheck() == null);
        assertThat(index).as("the corpus must carry a plain document case").isGreaterThanOrEqualTo(0);

        ReferenceCorpusCase original = bundle.cases().get(index);
        ReferenceCaseRequest request = original.request();
        ReferenceCaseRequest withoutDocument = new ReferenceCaseRequest(
                null, request.declaredSites(), request.candidateRootVersion(), request.utterance(),
                request.declaredCandidateTexts(), request.audienceSelection(), request.versionSelection(),
                request.declaredPins(), request.retention(), request.recheck());
        ReferenceCorpusCase underSpecified = new ReferenceCorpusCase(
                original.schemaVersion(), original.caseId(), original.category(), original.scenario(),
                original.riskLevel(), original.tags(), original.notes(), original.fixtures(), withoutDocument);

        ReferenceCorpusBundle mutated = withCase(bundle, index, underSpecified);

        assertThat(new ReferenceCorpusValidator().validateCandidate(mutated).issueCodes())
                .containsExactly(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED);
    }

    @Test
    void aCaseThatAsksExactlyWhatAnotherCaseAsksIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceCorpusBundle duplicated = withCaseAndExpected(
                bundle, 1, bundle.cases().get(0), bundle.expectedResults().get(0));

        assertThat(new ReferenceCorpusValidator().validateCandidate(duplicated).issueCodes())
                .containsExactly(ReferenceCorpusErrorCodes.DUPLICATE_CASE_SEMANTICS);
    }

    @Test
    void aManifestClaimingReviewCompleteOverPendingLedgersIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceCorpusBundle claimingComplete = withManifest(bundle, manifestWith(
                bundle.manifest(), null, null, ReferenceHumanReviewStatus.COMPLETE, null));

        List<String> codes = new ReferenceCorpusValidator().validateCandidate(claimingComplete).issueCodes();
        assertThat(codes).contains(ReferenceCorpusErrorCodes.REVIEW_INCOMPLETE);
        assertThat(codes).doesNotContain(ReferenceCorpusErrorCodes.REVIEW_DECISION_FABRICATED);
        assertThat(new ReferenceCorpusValidator().validateCandidate(claimingComplete).isCandidateComplete())
                .isFalse();
    }

    @Test
    void aLedgerRecordingADecisionUnderAPendingManifestIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        CaseReviewEvidence pending = bundle.reviews().get(0);
        CaseReviewEntry product = CaseReviewEntry.decided(
                ReviewRole.PRODUCT, ReviewStatus.APPROVED, "owner-product-1", "review-evidence-product-001",
                Instant.parse("2026-10-07T09:00:00Z"), "Approved by a real owner.");
        CaseReviewEvidence decided = CaseReviewEvidence.of(
                pending.schemaVersion(), pending.caseId(), pending.caseHash(), pending.expectedHash(),
                List.of(product, pending.reviews().get(1), pending.reviews().get(2)));

        ReferenceCorpusBundle mutated = withReview(bundle, 0, decided);

        assertThat(new ReferenceCorpusValidator().validateCandidate(mutated).issueCodes())
                .containsExactly(ReferenceCorpusErrorCodes.REVIEW_DECISION_FABRICATED);
    }

    @Test
    void aReviewLedgerBoundToAnotherRevisionIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        CaseReviewEvidence pending = bundle.reviews().get(0);
        CaseReviewEvidence stale = CaseReviewEvidence.of(
                pending.schemaVersion(), pending.caseId(),
                ContentHash.sha256("some-other-case-revision".getBytes(StandardCharsets.UTF_8)),
                pending.expectedHash(), pending.reviews());

        ReferenceCorpusBundle mutated = withReview(bundle, 0, stale);

        assertThat(new ReferenceCorpusValidator().validateCandidate(mutated).issueCodes())
                .containsExactly(ReferenceCorpusErrorCodes.REVIEW_BINDING_STALE);
    }

    @Test
    void aCorpusWithNoReviewLedgersIsRefused() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceCorpusBundle withoutReviews = new ReferenceCorpusBundle(
                bundle.manifest(), bundle.fixtureCatalog(), bundle.fixturePayloads(),
                bundle.cases(), bundle.expectedResults(), List.of());

        assertThat(new ReferenceCorpusValidator().validateCandidate(withoutReviews).issueCodes())
                .contains(ReferenceCorpusErrorCodes.REVIEW_EVIDENCE_MISSING);
    }

    @Test
    void theGatePassNeverInventsAnApprovalForTheCommittedCorpus() {
        ReferenceCorpusBundle bundle = bundle();
        EvaluationValidationReport gate = new ReferenceCorpusValidator().validateGate(bundle);

        assertThat(gate.stage()).isEqualTo(EvaluationStage.GATE);
        assertThat(gate.issueCodes())
                .doesNotContain(ReferenceCorpusErrorCodes.REVIEW_DECISION_FABRICATED)
                .doesNotContain(ReferenceCorpusErrorCodes.RELEASE_STATUS_FABRICATED)
                .doesNotContain(ReferenceCorpusErrorCodes.MODEL_EVIDENCE_FABRICATED);
        for (CaseReviewEvidence review : bundle.reviews()) {
            for (CaseReviewEntry entry : review.reviews()) {
                assertThat(entry.isDecided()).as("case %s role %s", review.caseId(), entry.role()).isFalse();
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private static ReferenceCorpusBundle bundle() {
        return new ReferenceCorpusLoader(Path.of("")).loadCorpus();
    }

    private static Map<ReferenceCategory, Integer> countsByCategory(ReferenceCorpusBundle bundle) {
        Map<ReferenceCategory, Integer> counts = new LinkedHashMap<>();
        for (ReferenceCorpusCase caseInput : bundle.cases()) {
            counts.merge(caseInput.category(), 1, Integer::sum);
        }
        return counts;
    }

    private static int indexOf(ReferenceCorpusBundle bundle, Predicate<ReferenceCorpusCase> match) {
        for (int index = 0; index < bundle.cases().size(); index++) {
            if (match.test(bundle.cases().get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static ReferenceCorpusBundle withManifest(
            ReferenceCorpusBundle bundle, ReferenceCorpusManifest manifest) {
        return new ReferenceCorpusBundle(
                manifest, bundle.fixtureCatalog(), bundle.fixturePayloads(), bundle.cases(),
                bundle.expectedResults(), bundle.reviews());
    }

    private static ReferenceCorpusBundle withCase(
            ReferenceCorpusBundle bundle, int index, ReferenceCorpusCase replacement) {
        List<ReferenceCorpusCase> cases = new ArrayList<>(bundle.cases());
        cases.set(index, replacement);
        return new ReferenceCorpusBundle(
                bundle.manifest(), bundle.fixtureCatalog(), bundle.fixturePayloads(), cases,
                bundle.expectedResults(), bundle.reviews());
    }

    private static ReferenceCorpusBundle withCaseAndExpected(
            ReferenceCorpusBundle bundle, int index, ReferenceCorpusCase caseInput,
            ReferenceCorpusExpected expected) {
        List<ReferenceCorpusCase> cases = new ArrayList<>(bundle.cases());
        List<ReferenceCorpusExpected> expectedResults = new ArrayList<>(bundle.expectedResults());
        cases.set(index, caseInput);
        expectedResults.set(index, expected);
        return new ReferenceCorpusBundle(
                bundle.manifest(), bundle.fixtureCatalog(), bundle.fixturePayloads(), cases,
                expectedResults, bundle.reviews());
    }

    private static ReferenceCorpusBundle withReview(
            ReferenceCorpusBundle bundle, int index, CaseReviewEvidence review) {
        List<CaseReviewEvidence> reviews = new ArrayList<>(bundle.reviews());
        reviews.set(index, review);
        return new ReferenceCorpusBundle(
                bundle.manifest(), bundle.fixtureCatalog(), bundle.fixturePayloads(), bundle.cases(),
                bundle.expectedResults(), reviews);
    }

    private static ReferenceCorpusManifest manifestWith(
            ReferenceCorpusManifest source, Integer minimumCases, Integer minimumCasesPerCategory,
            ReferenceHumanReviewStatus humanReviewStatus, List<ReferenceCategory> requiredCategories) {
        ReferenceCorpusManifest withoutHash = new ReferenceCorpusManifest(
                source.schemaVersion(), source.corpusVersion(), source.fixtureCatalog(),
                minimumCases == null ? source.minimumCases() : minimumCases,
                minimumCasesPerCategory == null
                        ? source.minimumCasesPerCategory() : minimumCasesPerCategory,
                humanReviewStatus == null ? source.humanReviewStatus() : humanReviewStatus,
                source.modelEvidenceStatus(), source.modelEvidenceReference(), source.releaseStatus(),
                requiredCategories == null ? source.requiredCategories() : requiredCategories,
                source.cases(), null);
        return withoutHash.withManifestHash(withoutHash.recomputeContentHash());
    }
}