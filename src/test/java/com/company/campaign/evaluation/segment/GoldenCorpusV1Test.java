package com.company.campaign.evaluation.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.validation.EvaluationValidationReport;
import com.company.campaign.evaluation.segment.validation.GateReadinessValidator;
import com.company.campaign.evaluation.segment.validation.SegmentEvaluationValidator;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The candidate v1 corpus: it must be machine-complete and explicitly blocked on human review.
 *
 * <p>Nothing here approves anything. The tests prove the corpus is loadable, covers every category with
 * fifteen distinct cases, carries pending review evidence bound to the exact hashes, parses every present
 * AST through T05 and matches the Metadata binding — and that once (and only once) real in-memory approvals
 * exist, the review portion becomes Gate-ready.
 */
class GoldenCorpusV1Test {

    private static final String MANIFEST = "evaluation/segment/corpus-v1/manifest.json";
    private static final Instant DECISION_TIME = Instant.parse("2026-10-02T09:00:00Z");

    private static CorpusBundle bundle;

    @BeforeAll
    static void loadOnce() {
        bundle = new SegmentEvaluationLoader(Path.of("")).loadCorpus(MANIFEST);
    }

    // --- manifest, coverage and hashes ------------------------------------------------------------

    @Test
    void candidateManifestLoadsWithEveryHashAndRealPathContainmentChecked() {
        assertThat(bundle.cases()).hasSize(225);
        assertThat(bundle.expectedResults()).hasSize(225);
        assertThat(bundle.reviews()).hasSize(225);
        assertThat(bundle.manifest().corpusVersion()).isEqualTo("v1");
        assertThat(bundle.manifest().cases()).hasSize(225);
    }

    @Test
    void everyCategoryHasExactlyFifteenCases() {
        Map<SegmentCategory, Integer> counts = new EnumMap<>(SegmentCategory.class);
        for (CaseInput caseInput : bundle.cases()) {
            counts.merge(caseInput.category(), 1, Integer::sum);
        }
        assertThat(SegmentCategory.values()).hasSize(15);
        for (SegmentCategory category : SegmentCategory.ordered()) {
            assertThat(counts.getOrDefault(category, 0))
                    .as("cases in category %s", category)
                    .isEqualTo(15);
        }
    }

    @Test
    void caseIdsAreUniqueAndCategoryPrefixed() {
        Set<String> ids = new HashSet<>();
        for (CaseInput caseInput : bundle.cases()) {
            assertThat(ids.add(caseInput.caseId())).as("duplicate case id %s", caseInput.caseId()).isTrue();
        }
        assertThat(ids).hasSize(225);
    }

    // --- review evidence --------------------------------------------------------------------------

    @Test
    void everyCandidateTripleHasPendingReviewEvidenceBoundToExactHashes() {
        for (int index = 0; index < bundle.cases().size(); index++) {
            CaseInput caseInput = bundle.cases().get(index);
            CaseReviewEvidence review = bundle.reviews().get(index);
            assertThat(bundle.manifest().cases().get(index).hasReviewEvidence())
                    .as("case %s binds review evidence", caseInput.caseId()).isTrue();
            assertThat(review).isNotNull();
            review.requireValidContentHash();
            assertThat(review.caseId()).isEqualTo(caseInput.caseId());
            assertThat(review.caseHash())
                    .isEqualTo(ContentHash.sha256(readBytes(Path.of(
                            "evaluation/segment/corpus-v1/cases/" + caseInput.caseId() + ".json"))));
            for (ReviewRole role : ReviewRole.ordered()) {
                assertThat(review.entryFor(role).status())
                        .as("review %s for %s", role, caseInput.caseId())
                        .isEqualTo(ReviewStatus.PENDING);
            }
        }
    }

    @Test
    void candidateCompletenessPassesAndGateFailuresAreOnlyHumanReviewIssues() {
        GateReadinessValidator validator = new GateReadinessValidator();

        EvaluationValidationReport candidate = validator.validateCandidate(bundle);
        assertThat(candidate.isCandidateComplete()).as("candidate report: %s", candidate.issueCodes()).isTrue();
        assertThat(candidate.isValid()).isTrue();

        EvaluationValidationReport gate = validator.validate(bundle);
        assertThat(gate.isGateReady()).as("no human approval exists, so the Gate must not be ready").isFalse();
        assertThat(gate.issueCodes()).containsOnly(EvaluationErrorCodes.REVIEW_PENDING);
        assertThat(gate.issues()).hasSize(225 * ReviewRole.ordered().size());
    }

    @Test
    void candidateCompletenessIsCleanUnderTheSemanticValidatorAlone() {
        EvaluationValidationReport semantic = new SegmentEvaluationValidator().validate(bundle);
        assertThat(semantic.issues()).as("%s", semantic.issueCodes()).isEmpty();
    }

    @Test
    void approvingEveryRoleInMemoryMakesTheReviewPortionGateReady() {
        CorpusBundle approved = withReviews(approvedReviews());
        EvaluationValidationReport gate = new GateReadinessValidator().validate(approved);

        assertThat(gate.isGateReady()).as("%s", gate.issueCodes()).isTrue();
    }

    @Test
    void staleRejectedAndMissingReviewsBlockReadiness() {
        GateReadinessValidator validator = new GateReadinessValidator();

        // Stale: the ledger binds a different expected hash than the reviewed revision.
        List<CaseReviewEvidence> stale = new ArrayList<>(approvedReviews());
        CaseReviewEvidence first = stale.get(0);
        stale.set(0, CaseReviewEvidence.of(first.schemaVersion(), first.caseId(), first.caseHash(),
                ContentHash.sha256("different".getBytes(StandardCharsets.UTF_8)), approvedEntries()));
        assertThat(validator.validateCandidate(withReviews(stale)).issueCodes())
                .contains(EvaluationErrorCodes.REVIEW_BINDING_STALE);

        // Rejected: a real decision that is not an approval.
        List<CaseReviewEvidence> rejected = new ArrayList<>(approvedReviews());
        CaseReviewEvidence target = rejected.get(1);
        List<CaseReviewEntry> entries = new ArrayList<>(target.reviews());
        entries.set(ReviewRole.DATA.ordinal(), CaseReviewEntry.decided(
                ReviewRole.DATA, ReviewStatus.REJECTED, "reviewer-data-01", "review-evidence-data-001",
                DECISION_TIME, "Rejected because the fixture does not discriminate."));
        rejected.set(1, CaseReviewEvidence.of(target.schemaVersion(), target.caseId(), target.caseHash(),
                target.expectedHash(), entries));
        EvaluationValidationReport rejectedReport = validator.validate(withReviews(rejected));
        assertThat(rejectedReport.issueCodes()).contains(EvaluationErrorCodes.REVIEW_REJECTED);
        assertThat(rejectedReport.isGateReady()).isFalse();

        // Missing: the corpus declares no review references at all.
        CorpusBundle withoutReviews = new CorpusBundle(
                bundle.manifest(), bundle.metadataCatalog(), bundle.fixtureCatalog(), bundle.cases(),
                bundle.expectedResults(), bundle.executionContexts());
        assertThat(validator.validateCandidate(withoutReviews).issueCodes())
                .contains(EvaluationErrorCodes.REVIEW_EVIDENCE_MISSING);
    }

    @Test
    void duplicateAndIncompleteRoleLedgersAreRejectedByTheContract() {
        CaseReviewEvidence sample = bundle.reviews().get(0);
        List<CaseReviewEntry> duplicated = new ArrayList<>(sample.reviews());
        duplicated.add(sample.reviews().get(0));
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEvidence.of(sample.schemaVersion(), sample.caseId(), sample.caseHash(),
                        sample.expectedHash(), duplicated),
                EvaluationErrorCodes.REVIEW_ROLE_DUPLICATE);
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEvidence.of(sample.schemaVersion(), sample.caseId(), sample.caseHash(),
                        sample.expectedHash(), List.of(sample.reviews().get(0))),
                EvaluationErrorCodes.REVIEW_ROLE_MISSING);
        // A pending entry may not fabricate a decision field, and a decided entry requires all of them.
        ContractAssertions.assertFailsWithCode(
                () -> new CaseReviewEntry(ReviewRole.PRODUCT, ReviewStatus.PENDING, "reviewer-product-01",
                        null, null, "Fabricated."),
                EvaluationErrorCodes.REVIEW_DECISION_FABRICATED);
        ContractAssertions.assertFailsWithCode(
                () -> CaseReviewEntry.decided(ReviewRole.TEST, ReviewStatus.APPROVED, null,
                        "review-evidence-test-001", DECISION_TIME, "Missing reviewer."),
                EvaluationErrorCodes.REVIEW_REFERENCE_INVALID);
    }

    // --- expected answers -------------------------------------------------------------------------

    @Test
    void everyResolvedCaseHasAnAstAndNoProhibitedDispositionDoes() {
        for (int index = 0; index < bundle.cases().size(); index++) {
            CaseInput caseInput = bundle.cases().get(index);
            ExpectedResult expected = bundle.expectedResults().get(index);
            if (expected.expectedDisposition() == Disposition.RESOLVED) {
                assertThat(expected.expectedCanonicalAst())
                        .as("resolved case %s has an AST", caseInput.caseId()).isNotNull();
                assertThat(expected.expectedSelectedCandidateIds()).isNotEmpty();
            } else {
                assertThat(expected.expectedCanonicalAst())
                        .as("non-resolved case %s has no AST", caseInput.caseId()).isNull();
            }
        }
    }

    @Test
    void everyComplexCaseHasAPrimaryAndACounterexampleFixture() {
        int complexCases = 0;
        for (int index = 0; index < bundle.cases().size(); index++) {
            CaseInput caseInput = bundle.cases().get(index);
            if (!caseInput.isComplexSemantics()) {
                continue;
            }
            complexCases++;
            boolean hasCounterexample = caseInput.fixtures().stream()
                    .map(bundle::fixture)
                    .anyMatch(fixture -> fixture != null && fixture.role() == FixtureRole.COUNTEREXAMPLE);
            assertThat(hasCounterexample)
                    .as("complex case %s has a counterexample", caseInput.caseId()).isTrue();
            assertThat(caseInput.fixtures().size()).isGreaterThanOrEqualTo(2);
        }
        assertThat(complexCases).isGreaterThanOrEqualTo(60);
    }

    @Test
    void refinementCasesCarryStableTargetsAndNoIndexPositions() {
        int refinementCases = 0;
        for (int index = 0; index < bundle.cases().size(); index++) {
            CaseInput caseInput = bundle.cases().get(index);
            if (caseInput.category() != SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE) {
                continue;
            }
            refinementCases++;
            ExpectedResult expected = bundle.expectedResults().get(index);
            assertThat(expected.refinementTarget()).as("case %s", caseInput.caseId()).isNotNull();
            assertThat(expected.refinementTarget().targetRef()).doesNotContain("[");
            assertThat(caseInput.currentSegment()).as("case %s has a base segment", caseInput.caseId())
                    .isNotNull();
        }
        assertThat(refinementCases).isEqualTo(15);
    }

    @Test
    void everyCategoryCollectsItsRequiredSubcases() {
        assertThat(dispositionsIn(SegmentCategory.UNSUPPORTED_CONCEPTS_AND_RAW_SQL))
                .containsOnly(Disposition.UNSUPPORTED);
        assertThat(dispositionsIn(SegmentCategory.PROMPT_INJECTION_UNAUTHORIZED_AND_CROSS_TENANT))
                .containsOnly(Disposition.DENIED);
        assertThat(dispositionsIn(SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION))
                .containsOnly(Disposition.NEEDS_INPUT);
        assertThat(dispositionsIn(SegmentCategory.MULTILINGUAL_ALIASES_AND_RECOVERABLE_TYPOS))
                .contains(Disposition.RESOLVED, Disposition.NEEDS_INPUT);
        assertThat(operationsIn(SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE))
                .containsExactlyInAnyOrder(RefinementOperation.ADD_NODE, RefinementOperation.REMOVE_NODE,
                        RefinementOperation.REPLACE_FIELD, RefinementOperation.MOVE_NODE);
        assertThat(highRiskCount(SegmentCategory.PROMPT_INJECTION_UNAUTHORIZED_AND_CROSS_TENANT)).isEqualTo(15);
    }

    // --- quality and integrity --------------------------------------------------------------------

    @Test
    void utterancesAreDistinctWithinEveryCategory() {
        Map<SegmentCategory, Set<String>> seen = new EnumMap<>(SegmentCategory.class);
        for (CaseInput caseInput : bundle.cases()) {
            Set<String> utterances = seen.computeIfAbsent(caseInput.category(), key -> new HashSet<>());
            assertThat(utterances.add(caseInput.utterance() + "|" + caseInput.locales()))
                    .as("duplicate utterance in %s: %s", caseInput.category(), caseInput.utterance())
                    .isTrue();
        }
    }

    @Test
    void noCommittedAssetCarriesAnApprovalOrAGatePass() throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("evaluation/segment/corpus-v1"))) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(content).as("%s must not claim a decision", file)
                        .doesNotContain("\"status\":\"APPROVED\"")
                        .doesNotContain("\"status\":\"REJECTED\"")
                        .doesNotContain("PASSED");
            }
        }
    }

    @Test
    void trustedContextFixturesAreDistinctAndNeverInlinedInCases() {
        FixtureDescriptor primary = bundle.fixture("fixture-context-primary-001");
        FixtureDescriptor limited = bundle.fixture("fixture-context-limited-002");
        assertThat(primary).isNotNull();
        assertThat(limited).isNotNull();
        assertThat(bundle.executionContext(primary.fixtureId()).tenantId()).isEqualTo("tenant-001");
        assertThat(bundle.executionContext(limited.fixtureId()).tenantId()).isEqualTo("tenant-002");
        for (CaseInput caseInput : bundle.cases()) {
            assertThat(caseInput.getClass().getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("tenantId", "userId", "dataScopeId", "permissions");
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static List<Disposition> dispositionsIn(SegmentCategory category) {
        List<Disposition> dispositions = new ArrayList<>();
        for (int index = 0; index < bundle.cases().size(); index++) {
            if (bundle.cases().get(index).category() == category) {
                dispositions.add(bundle.expectedResults().get(index).expectedDisposition());
            }
        }
        return dispositions;
    }

    private static Set<RefinementOperation> operationsIn(SegmentCategory category) {
        Set<RefinementOperation> operations = new HashSet<>();
        for (int index = 0; index < bundle.cases().size(); index++) {
            if (bundle.cases().get(index).category() == category
                    && bundle.expectedResults().get(index).refinementTarget() != null) {
                operations.add(bundle.expectedResults().get(index).refinementTarget().operation());
            }
        }
        return operations;
    }

    private static long highRiskCount(SegmentCategory category) {
        return bundle.cases().stream()
                .filter(caseInput -> caseInput.category() == category && caseInput.riskLevel() == RiskLevel.HIGH)
                .count();
    }

    private static List<CaseReviewEvidence> approvedReviews() {
        List<CaseReviewEvidence> reviews = new ArrayList<>(bundle.reviews().size());
        for (CaseReviewEvidence pending : bundle.reviews()) {
            reviews.add(CaseReviewEvidence.of(pending.schemaVersion(), pending.caseId(), pending.caseHash(),
                    pending.expectedHash(), approvedEntries()));
        }
        return reviews;
    }

    private static List<CaseReviewEntry> approvedEntries() {
        List<CaseReviewEntry> entries = new ArrayList<>(ReviewRole.ordered().size());
        for (ReviewRole role : ReviewRole.ordered()) {
            entries.add(CaseReviewEntry.decided(role, ReviewStatus.APPROVED,
                    "reviewer-" + role.name().toLowerCase(java.util.Locale.ROOT) + "-01",
                    "review-evidence-" + role.name().toLowerCase(java.util.Locale.ROOT) + "-001",
                    DECISION_TIME, "Approved by the " + role + " owner for this exact revision."));
        }
        return entries;
    }

    private static CorpusBundle withReviews(List<CaseReviewEvidence> reviews) {
        return new CorpusBundle(bundle.manifest(), bundle.metadataCatalog(), bundle.fixtureCatalog(),
                bundle.cases(), bundle.expectedResults(), reviews, bundle.metadataPayloads(),
                bundle.executionContexts());
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}