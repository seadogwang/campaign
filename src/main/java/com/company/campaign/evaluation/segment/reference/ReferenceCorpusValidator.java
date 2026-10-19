package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.validation.EvaluationStage;
import com.company.campaign.evaluation.segment.validation.EvaluationValidationReport;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.reference.ReferenceErrorCodes;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks whether the reference corpus is more than loadable: whether it is complete, honest and reviewable.
 *
 * <p>Counts alone cannot separate a reviewed corpus from a padded one, so this validator looks for the shapes
 * an unearned corpus takes, and it looks for them in three passes that are applied in one deterministic
 * order:
 *
 * <ul>
 *   <li><strong>coverage</strong> — every required category is present with at least the required number of
 *       cases, no case sits in a category the manifest does not require, and a corpus that asks for the T18
 *       floor is refused below forty cases;</li>
 *   <li><strong>shape</strong> — a case declares exactly the request parts and fixtures its scenario needs, it
 *       names fixtures the catalog declares with the role the slot implies, its declared sites are unique and
 *       its pins are bounded, and its expectation is neither under- nor over-specified for its outcome;</li>
 *   <li><strong>evidence</strong> — an expectation only names issue and question codes the reference core
 *       owns, no code is repeated, and the per-case review ledger is present, bound to these exact revisions
 *       and consistent with the review status the manifest claims.</li>
 * </ul>
 *
 * <p>Most important of all, a corpus that claims human review complete while any required owner role is still
 * pending, or that records a decision while the manifest says review is pending, is refused. Nothing here
 * manufactures an approval, and nothing here can be relaxed into one: the release status a manifest may spell
 * has no {@code PASSED} or {@code ACTIVE} constant at all.
 */
public final class ReferenceCorpusValidator {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** Every stable code the reference core may put on an outward finding. */
    private static final Set<String> REFERENCE_ISSUE_CODES = Set.of(
            ReferenceErrorCodes.CONTEXT_MISSING,
            ReferenceErrorCodes.VERSION_NOT_FOUND,
            ReferenceErrorCodes.VERSION_CONFLICT,
            ReferenceErrorCodes.VERSION_UNAVAILABLE,
            ReferenceErrorCodes.PROGRAM_NOT_ALLOWED,
            ReferenceErrorCodes.RESOURCE_NOT_ACCESSIBLE,
            ReferenceErrorCodes.DATASCOPE_INTERSECTION_EMPTY,
            ReferenceErrorCodes.DATASCOPE_UNAVAILABLE,
            ReferenceErrorCodes.SUBJECT_MISMATCH,
            ReferenceErrorCodes.CYCLE_DETECTED,
            ReferenceErrorCodes.DEPTH_EXCEEDED,
            ReferenceErrorCodes.DISTINCT_VERSIONS_EXCEEDED,
            ReferenceErrorCodes.EXPANDED_COMPLEXITY_EXCEEDED,
            ReferenceErrorCodes.POLICY_INVALID,
            ReferenceErrorCodes.INPUT_INVALID,
            ReferenceErrorCodes.RETENTION_TARGET_UNKNOWN,
            SegmentDslErrorCodes.AUDIENCE_REFERENCE_INVALID);

    /** Every stable question or reason code a reference resolution may carry. */
    private static final Set<String> REFERENCE_QUESTION_CODES = Set.of(
            "SELECT_AUDIENCE_REFERENCE",
            "SELECT_AUDIENCE_VERSION",
            "AUDIENCE_REFERENCE_UNAVAILABLE");

    private static final List<String> PLACEHOLDER_TOKENS = List.of(
            "todo", "tbd", "fixme", "placeholder", "lorem ipsum", "dummy", "change me", "xxx");

    /** Validates the corpus as a candidate: structure, meaning and recorded review, but not gate approval. */
    public EvaluationValidationReport validateCandidate(ReferenceCorpusBundle bundle) {
        return validate(bundle, false);
    }

    /** Validates the corpus as a Gate corpus: the candidate rules plus the human-approval readiness rules. */
    public EvaluationValidationReport validateGate(ReferenceCorpusBundle bundle) {
        return validate(bundle, true);
    }

    /** Validates the corpus, applying the Gate readiness rules only when asked for them. */
    public EvaluationValidationReport validate(ReferenceCorpusBundle bundle, boolean gateEvaluated) {
        Objects.requireNonNull(bundle, "bundle");
        List<Issue> issues = new ArrayList<>();

        validateCoverage(bundle, issues);
        Map<String, String> signatureOwner = new LinkedHashMap<>();
        for (int index = 0; index < bundle.cases().size(); index++) {
            validateCase(bundle, index, signatureOwner, issues);
        }
        validateReviews(bundle, gateEvaluated, issues);

        return new EvaluationValidationReport(issues, gateEvaluated ? EvaluationStage.GATE : EvaluationStage.CANDIDATE);
    }

    // ------------------------------------------------------------------ coverage

    private void validateCoverage(ReferenceCorpusBundle bundle, List<Issue> issues) {
        ReferenceCorpusManifest manifest = bundle.manifest();
        String path = "/manifest";

        if (manifest.minimumCases() < ReferenceCorpusVersions.GATE_MINIMUM_CASES) {
            issues.add(issue(ReferenceCorpusErrorCodes.GATE_MINIMUM_TOO_LOW, path + "/minimumCases",
                    "A Gate-eligible reference corpus must ask for at least "
                            + ReferenceCorpusVersions.GATE_MINIMUM_CASES + " cases"));
        }
        if (bundle.cases().size() < manifest.minimumCases()) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_COUNT_BELOW_MINIMUM, path + "/cases",
                    "The reference corpus declares fewer cases than its own manifest minimum of "
                            + manifest.minimumCases()));
        }

        Map<ReferenceCategory, Integer> counts = new LinkedHashMap<>();
        for (ReferenceCategory category : ReferenceCategory.ordered()) {
            counts.put(category, 0);
        }
        for (ReferenceCorpusCase caseInput : bundle.cases()) {
            counts.merge(caseInput.category(), 1, Integer::sum);
        }
        for (ReferenceCategory category : manifest.requiredCategories()) {
            int count = counts.getOrDefault(category, 0);
            if (count == 0) {
                issues.add(issue(ReferenceCorpusErrorCodes.CATEGORY_MISSING, path + "/requiredCategories",
                        "The reference corpus has no case in the required category " + category));
            } else if (count < manifest.minimumCasesPerCategory()) {
                issues.add(issue(ReferenceCorpusErrorCodes.CATEGORY_UNDER_MINIMUM,
                        path + "/requiredCategories",
                        "The required category " + category + " has " + count + " cases but the corpus asks for "
                                + manifest.minimumCasesPerCategory()));
            }
        }
        for (ReferenceCorpusCase caseInput : bundle.cases()) {
            if (!manifest.requiredCategories().contains(caseInput.category())) {
                issues.add(issue(ReferenceCorpusErrorCodes.CATEGORY_UNEXPECTED,
                        "/cases/" + caseInput.caseId() + "/category",
                        "Case '" + caseInput.caseId() + "' declares category " + caseInput.category()
                                + " which the manifest does not require"));
            }
        }
    }

    // ------------------------------------------------------------------ cases and expectations

    private void validateCase(
            ReferenceCorpusBundle bundle, int index, Map<String, String> signatureOwner, List<Issue> issues) {
        ReferenceCorpusCase caseInput = bundle.cases().get(index);
        ReferenceCorpusExpected expected = bundle.expectedResults().get(index);
        String path = "/cases/" + caseInput.caseId();

        validateCaseShape(bundle, caseInput, path, signatureOwner, issues);
        validateExpected(caseInput, expected, path, issues);
    }

    private void validateCaseShape(
            ReferenceCorpusBundle bundle, ReferenceCorpusCase caseInput, String path,
            Map<String, String> signatureOwner, List<Issue> issues) {
        String caseId = caseInput.caseId();
        ReferenceScenario scenario = caseInput.scenario();
        ReferenceCaseRequest request = caseInput.request();

        boolean documentScenario = scenario == ReferenceScenario.REFERENCE_DOCUMENT;
        if (documentScenario != (request.document() != null)) {
            issues.add(issue(documentScenario
                            ? ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED
                            : ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED,
                    path + "/request/document",
                    "Case '" + caseId + "' declares an embedded document exactly for a document scenario"));
        }
        if (documentScenario && request.declaredSites().isEmpty()) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED, path + "/request/references",
                    "Case '" + caseId + "' must declare the reference sites its document carries"));
        }
        if (!documentScenario && !request.declaredSites().isEmpty()) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED, path + "/request/references",
                    "Case '" + caseId + "' must not declare reference sites outside a document scenario"));
        }
        if ((request.utterance() != null) != (scenario == ReferenceScenario.REFERENCE_RESOLUTION)) {
            issues.add(issue(scenario == ReferenceScenario.REFERENCE_RESOLUTION
                            ? ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED
                            : ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED,
                    path + "/request/utterance",
                    "Case '" + caseId + "' declares a request wording exactly for a resolution scenario"));
        }
        if (scenario == ReferenceScenario.REFERENCE_RESOLUTION && request.declaredCandidateTexts().isEmpty()) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED, path + "/request/candidateTexts",
                    "Case '" + caseId + "' must offer the candidate texts its discovery is given"));
        }
        if (scenario != ReferenceScenario.REFERENCE_RESOLUTION
                && (!request.declaredCandidateTexts().isEmpty()
                        || request.audienceSelection() != null
                        || request.versionSelection() != null)) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED, path + "/request",
                    "Case '" + caseId + "' must not declare resolution wording or answers outside a"
                            + " resolution scenario"));
        }
        if ((!request.declaredPins().isEmpty()) != (scenario == ReferenceScenario.REFERENCE_MEMBERSHIP)) {
            issues.add(issue(scenario == ReferenceScenario.REFERENCE_MEMBERSHIP
                            ? ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED
                            : ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED,
                    path + "/request/pins",
                    "Case '" + caseId + "' declares membership pins exactly for a membership scenario"));
        }
        if ((request.retention() != null) != (scenario == ReferenceScenario.REFERENCE_RETENTION)) {
            issues.add(issue(scenario == ReferenceScenario.REFERENCE_RETENTION
                            ? ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED
                            : ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED,
                    path + "/request/retention",
                    "Case '" + caseId + "' declares a retention program exactly for a retention scenario"));
        }
        if (request.recheck() != null && !documentScenario) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED, path + "/request/recheck",
                    "Case '" + caseId + "' must not declare a drift re-check outside a document scenario"));
        }
        if (!documentScenario && request.candidateRootVersion() != null) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_OVER_SPECIFIED,
                    path + "/request/candidateRootVersion",
                    "Case '" + caseId + "' must not declare a candidate root version outside a document"
                            + " scenario"));
        }

        Set<String> siteKeys = new LinkedHashSet<>();
        for (String key : caseInput.siteKeys()) {
            if (!siteKeys.add(key)) {
                issues.add(issue(ReferenceCorpusErrorCodes.SITE_ID_DUPLICATE, path + "/request/references",
                        "Case '" + caseId + "' declares node identity '" + key + "' more than once"));
            }
        }

        validateFixtureSlots(bundle, caseInput, path, issues);

        for (String tag : caseInput.tags()) {
            requireNoPlaceholder(caseId, path + "/tags", tag, issues);
        }
        requireNoPlaceholder(caseId, path + "/notes", caseInput.notes(), issues);

        String signature = CANONICAL_JSON.writeString(Map.of(
                "category", caseInput.category().name(),
                "scenario", scenario.name(),
                "fixtures", caseInput.fixtures(),
                "request", request));
        String owner = signatureOwner.putIfAbsent(signature, caseId);
        if (owner != null) {
            issues.add(issue(ReferenceCorpusErrorCodes.DUPLICATE_CASE_SEMANTICS, path,
                    "Case '" + caseId + "' asks exactly what case '" + owner + "' asks"));
        }
    }

    private void validateFixtureSlots(
            ReferenceCorpusBundle bundle, ReferenceCorpusCase caseInput, String path, List<Issue> issues) {
        String caseId = caseInput.caseId();
        ReferenceScenario scenario = caseInput.scenario();
        Map<ReferenceFixtureRole, String> declared = caseInput.fixtures().declared();
        for (Map.Entry<ReferenceFixtureRole, String> entry : declared.entrySet()) {
            ReferenceFixtureDescriptor fixture = bundle.fixtureCatalog().find(entry.getValue());
            if (fixture == null) {
                issues.add(issue(ReferenceCorpusErrorCodes.FIXTURE_UNKNOWN,
                        path + "/fixtures/" + entry.getKey().name(),
                        "Case '" + caseId + "' names reference fixture '" + entry.getValue()
                                + "' which the fixture catalog does not declare"));
            } else if (fixture.role() != entry.getKey()) {
                issues.add(issue(ReferenceCorpusErrorCodes.FIXTURE_ROLE_MISMATCH,
                        path + "/fixtures/" + entry.getKey().name(),
                        "Case '" + caseId + "' uses fixture '" + entry.getValue() + "' as "
                                + entry.getKey() + " but the catalog declares it as " + fixture.role()));
            }
        }

        // The reference document path always needs a trusted context; the catalog is absent only for the one
        // case that proves a reference without trusted resolution context fails closed.
        if (scenario == ReferenceScenario.REFERENCE_DOCUMENT
                && caseInput.fixtureFor(ReferenceFixtureRole.EXECUTION_CONTEXT) == null) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED, path + "/fixtures/contextFixture",
                    "Case '" + caseId + "' must name the trusted execution context a document case runs in"));
        }
        if (scenario == ReferenceScenario.REFERENCE_DOCUMENT
                && caseInput.fixtureFor(ReferenceFixtureRole.VERSION_CATALOG) != null
                && caseInput.fixtureFor(ReferenceFixtureRole.REFERENCE_POLICY) == null) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    path + "/fixtures/referencePolicyFixture",
                    "Case '" + caseId + "' must name the bounded reference policy its document is judged"
                            + " against"));
        }
        if (scenario == ReferenceScenario.REFERENCE_RESOLUTION
                && caseInput.fixtureFor(ReferenceFixtureRole.DISCOVERY_DIRECTORY) == null) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED, path + "/fixtures/directoryFixture",
                    "Case '" + caseId + "' must name the host discovery directory a resolution reads"));
        }
        if (scenario == ReferenceScenario.REFERENCE_MEMBERSHIP
                && caseInput.fixtureFor(ReferenceFixtureRole.MEMBERSHIP_DATASET) == null) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    path + "/fixtures/membershipFixture",
                    "Case '" + caseId + "' must name the membership dataset it looks up"));
        }
        if (scenario == ReferenceScenario.REFERENCE_RETENTION
                && caseInput.fixtureFor(ReferenceFixtureRole.RETENTION_POLICY) == null) {
            issues.add(issue(ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    path + "/fixtures/retentionPolicyFixture",
                    "Case '" + caseId + "' must name the host retention policy it exercises"));
        }
    }

    private void validateExpected(
            ReferenceCorpusCase caseInput, ReferenceCorpusExpected expected, String path, List<Issue> issues) {
        String caseId = caseInput.caseId();
        String expectedPath = path + "/expected";

        if (!expected.expectedOutcome().appliesTo(caseInput.scenario())) {
            issues.add(issue(ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE, expectedPath + "/expectedOutcome",
                    "Case '" + caseId + "' expects outcome " + expected.expectedOutcome()
                            + " which its scenario cannot produce"));
        }

        Set<String> seenCodes = new LinkedHashSet<>();
        for (String code : expected.expectedIssueCodes()) {
            if (!REFERENCE_ISSUE_CODES.contains(code)) {
                issues.add(issue(ReferenceCorpusErrorCodes.EXPECTED_ISSUE_CODE_UNKNOWN,
                        expectedPath + "/expectedIssueCodes",
                        "Case '" + caseId + "' expects issue code '" + code
                                + "' which the reference core does not own"));
            }
            if (!seenCodes.add(code)) {
                issues.add(issue(ReferenceCorpusErrorCodes.EXPECTED_ISSUE_CODE_DUPLICATE,
                        expectedPath + "/expectedIssueCodes",
                        "Case '" + caseId + "' repeats expected issue code '" + code
                                + "' in an ordered, de-duplicated finding list"));
            }
        }
        if (expected.resolution() != null) {
            for (String code : expected.resolution().questionCodes()) {
                if (!REFERENCE_QUESTION_CODES.contains(code)) {
                    issues.add(issue(ReferenceCorpusErrorCodes.EXPECTED_QUESTION_CODE_UNKNOWN,
                            expectedPath + "/resolution/questionCodes",
                            "Case '" + caseId + "' expects question code '" + code
                                    + "' which the reference resolver does not own"));
                }
            }
        }
        if (expected.recheck() != null) {
            if (caseInput.request().recheck() == null) {
                issues.add(issue(ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED, expectedPath + "/recheck",
                        "Case '" + caseId + "' expects a re-check it never declares"));
            } else if (caseInput.request().recheck().kind() == ReferenceRecheckKind.WITHDRAW_AVAILABILITY
                    && expected.recheck().outcome() == ReferenceRecheckOutcome.CLEAN) {
                issues.add(issue(ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE, expectedPath + "/recheck",
                        "Case '" + caseId + "' claims a withdrawn version left the reference clean"));
            }
        } else if (caseInput.request().recheck() != null) {
            issues.add(issue(ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE, expectedPath + "/recheck",
                    "Case '" + caseId + "' declares a re-check its expectation does not state"));
        }
    }

    // ------------------------------------------------------------------ review evidence

    private void validateReviews(
            ReferenceCorpusBundle bundle, boolean gateEvaluated, List<Issue> issues) {
        ReferenceCorpusManifest manifest = bundle.manifest();
        boolean claimsComplete = manifest.claimsHumanReviewComplete();
        List<ReferenceCorpusCase> cases = bundle.cases();

        for (int index = 0; index < cases.size(); index++) {
            ReferenceCorpusCase caseInput = cases.get(index);
            String path = "/cases/" + caseInput.caseId() + "/review";
            CaseReviewEvidence review = bundle.reviews().isEmpty() ? null : bundle.reviews().get(index);
            if (review == null) {
                issues.add(issue(ReferenceCorpusErrorCodes.REVIEW_EVIDENCE_MISSING, path,
                        "Case '" + caseInput.caseId() + "' declares no review-evidence ledger"));
                continue;
            }
            ContentHash caseHash = bundle.manifest().cases().get(index).caseHash();
            ContentHash expectedHash = bundle.manifest().cases().get(index).expectedHash();
            if (!review.caseHash().equals(caseHash) || !review.expectedHash().equals(expectedHash)) {
                issues.add(issue(ReferenceCorpusErrorCodes.REVIEW_BINDING_STALE, path,
                        "The review ledger for case '" + caseInput.caseId()
                                + "' is bound to revisions other than the ones the manifest pins"));
            }
            boolean anyDecided = false;
            for (CaseReviewEntry entry : review.reviews()) {
                anyDecided |= entry.isDecided();
            }
            if (anyDecided && !claimsComplete) {
                issues.add(issue(ReferenceCorpusErrorCodes.REVIEW_DECISION_FABRICATED, path,
                        "The review ledger for case '" + caseInput.caseId()
                                + "' records a decision while the manifest declares review pending"));
            }
            if (claimsComplete) {
                if (!review.isFullyApproved()) {
                    issues.add(issue(ReferenceCorpusErrorCodes.REVIEW_INCOMPLETE, path,
                            "The manifest declares human review complete but case '" + caseInput.caseId()
                                    + "' is not approved by every required owner role"));
                }
                if (gateEvaluated) {
                    Set<ReviewRole> decidedRoles = new LinkedHashSet<>();
                    for (CaseReviewEntry entry : review.reviews()) {
                        if (entry.status() != ReviewStatus.PENDING) {
                            decidedRoles.add(entry.role());
                        }
                    }
                    if (decidedRoles.size() != ReviewRole.ordered().size()) {
                        issues.add(issue(ReferenceCorpusErrorCodes.REVIEW_INCOMPLETE, path,
                                "The Gate pass requires a real decision from every owner role for case '"
                                        + caseInput.caseId() + "'"));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void requireNoPlaceholder(
            String caseId, String path, String value, List<Issue> issues) {
        String lowered = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        for (String token : PLACEHOLDER_TOKENS) {
            if (lowered.contains(token)) {
                issues.add(issue(ReferenceCorpusErrorCodes.PLACEHOLDER_TEXT, path,
                        "Case '" + caseId + "' carries placeholder text rather than a reviewable statement"));
                return;
            }
        }
    }

    static Issue issue(String code, String path, String message) {
        return new Issue(code, IssueSeverity.ERROR, path, null, null, message, Map.of());
    }
}