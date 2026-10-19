package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.FixtureDescriptor;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks what a corpus case <em>means</em>, separately from whether its files are intact.
 *
 * <p>The loader already proved that every file exists, matches its hash and pairs with its counterpart.
 * This validator covers the part that only makes sense once a case, its expected result, the catalogs and
 * the trusted execution context are all visible at once: does the case reference fixtures that exist, does
 * every referenced fixture have an expected member set, are those members inside the fixture's declared
 * universe, is a grounding claim actually backed by utterance text, and does the expected disposition
 * permit what the expectation says it should produce.
 *
 * <p>It intentionally does <em>not</em> decide Gate readiness. A category may hold one case, a complex
 * case may be the only one in the corpus, and the report will still be clean — because those are corpus
 * <em>size</em> questions, and answering them here would make a reviewed example indistinguishable from a
 * Gate corpus.
 *
 * <p>Nothing here reads a clock or a default locale, so the same bundle always yields the same report.
 */
public final class SegmentEvaluationValidator {

    /** Validates the semantic coherence of a loaded corpus. */
    public EvaluationValidationReport validate(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        List<Issue> issues = new ArrayList<>();
        List<CaseInput> cases = bundle.cases();
        List<ExpectedResult> expectedResults = bundle.expectedResults();
        for (int index = 0; index < cases.size(); index++) {
            validateCase(bundle, index, cases.get(index), expectedResults.get(index), issues);
        }
        return EvaluationValidationReport.semanticsOnly(issues);
    }

    private void validateCase(
            CorpusBundle bundle,
            int index,
            CaseInput caseInput,
            ExpectedResult expected,
            List<Issue> issues) {

        String path = "/cases/" + index + "/" + caseInput.caseId();

        validateCaseContent(caseInput, path, issues);
        validateMetadataSnapshot(bundle, caseInput, path, issues);
        FixtureDescriptor contextFixture = validateExecutionContext(bundle, caseInput, path, issues);
        validateReferencedFixtures(bundle, caseInput, contextFixture, path, issues);
        validateComplexSemantics(bundle, caseInput, path, issues);
        validateExpectedMemberSets(bundle, caseInput, expected, path, issues);
        validateIntentSlots(caseInput, expected, path, issues);
        validateDispositionCoherence(expected, path, issues);
    }

    private void validateCaseContent(CaseInput caseInput, String path, List<Issue> issues) {
        if (caseInput.locales().isEmpty()) {
            issues.add(issue(EvaluationErrorCodes.CASE_LOCALES_MISSING, path + "/locales",
                    "Case '" + caseInput.caseId() + "' declares no locale"));
        }
        if (caseInput.utterance().isBlank()) {
            issues.add(issue(EvaluationErrorCodes.UTTERANCE_BLANK, path + "/utterance",
                    "Case '" + caseInput.caseId() + "' has an empty utterance"));
        }
        if (caseInput.fixtures().isEmpty()) {
            issues.add(issue(EvaluationErrorCodes.FIXTURES_MISSING, path + "/fixtures",
                    "Case '" + caseInput.caseId() + "' references no fixture"));
        }
    }

    private void validateMetadataSnapshot(
            CorpusBundle bundle, CaseInput caseInput, String path, List<Issue> issues) {

        MetadataSnapshotDescriptor snapshot = null;
        for (MetadataSnapshotDescriptor candidate : bundle.metadataCatalog().snapshots()) {
            if (candidate.snapshotId().equals(caseInput.metadataSnapshot())) {
                snapshot = candidate;
                break;
            }
        }
        if (snapshot == null) {
            issues.add(issue(EvaluationErrorCodes.METADATA_SNAPSHOT_UNKNOWN,
                    path + "/metadataSnapshot",
                    "Case '" + caseInput.caseId() + "' references Metadata snapshot '"
                            + caseInput.metadataSnapshot() + "', which the catalog does not declare"));
        }
    }

    private FixtureDescriptor validateExecutionContext(
            CorpusBundle bundle, CaseInput caseInput, String path, List<Issue> issues) {

        FixtureDescriptor fixture = bundle.fixture(caseInput.executionContextFixture());
        if (fixture == null) {
            issues.add(issue(EvaluationErrorCodes.EXECUTION_CONTEXT_FIXTURE_INVALID,
                    path + "/executionContextFixture",
                    "Case '" + caseInput.caseId() + "' references execution-context fixture '"
                            + caseInput.executionContextFixture() + "', which the catalog does not declare"));
            return null;
        }
        if (fixture.role() != FixtureRole.EXECUTION_CONTEXT) {
            issues.add(issue(EvaluationErrorCodes.EXECUTION_CONTEXT_FIXTURE_INVALID,
                    path + "/executionContextFixture",
                    "Fixture '" + fixture.fixtureId() + "' is declared as " + fixture.role()
                            + " but is referenced as an execution context"));
            return null;
        }

        TrustedExecutionContextRef context = bundle.executionContext(fixture.fixtureId());
        if (context == null) {
            issues.add(issue(EvaluationErrorCodes.EXECUTION_CONTEXT_FIXTURE_INVALID,
                    path + "/executionContextFixture",
                    "Execution-context fixture '" + fixture.fixtureId() + "' has no parsed context"));
            return fixture;
        }
        if (!context.metadataSnapshotId().equals(caseInput.metadataSnapshot())) {
            issues.add(issue(EvaluationErrorCodes.CONTEXT_SNAPSHOT_MISMATCH,
                    path + "/executionContextFixture",
                    "Trusted context names Metadata snapshot '" + context.metadataSnapshotId()
                            + "' but the case names '" + caseInput.metadataSnapshot() + "'"));
        }
        if (!sameTimeContext(context, fixture)) {
            issues.add(issue(EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_MISMATCH,
                    path + "/executionContextFixture",
                    "Execution-context fixture '" + fixture.fixtureId()
                            + "' declares a time context that disagrees with its payload"));
        }
        return fixture;
    }

    private void validateReferencedFixtures(
            CorpusBundle bundle,
            CaseInput caseInput,
            FixtureDescriptor contextFixture,
            String path,
            List<Issue> issues) {

        for (String fixtureId : caseInput.fixtures()) {
            FixtureDescriptor fixture = bundle.fixture(fixtureId);
            if (fixture == null) {
                issues.add(issue(EvaluationErrorCodes.FIXTURE_UNKNOWN, path + "/fixtures/" + fixtureId,
                        "Case '" + caseInput.caseId() + "' references fixture '" + fixtureId
                                + "', which the catalog does not declare"));
                continue;
            }
            if (!fixture.role().isMemberSetFixture()) {
                issues.add(issue(EvaluationErrorCodes.FIXTURE_ROLE_MISMATCH, path + "/fixtures/" + fixtureId,
                        "Fixture '" + fixtureId + "' is an execution context and cannot carry a member set"));
                continue;
            }
            if (contextFixture != null && !sameTimeContext(fixture, contextFixture)) {
                issues.add(issue(EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_MISMATCH, path + "/fixtures/" + fixtureId,
                        "Fixture '" + fixtureId + "' is not evaluated at the same asOf, timezone and watermark"
                                + " as the case's execution-context fixture"));
            }
        }
    }

    private void validateComplexSemantics(
            CorpusBundle bundle, CaseInput caseInput, String path, List<Issue> issues) {

        if (!caseInput.isComplexSemantics()) {
            return;
        }
        long counterexamples = caseInput.fixtures().stream()
                .map(bundle::fixture)
                .filter(fixture -> fixture != null && fixture.role() == FixtureRole.COUNTEREXAMPLE)
                .count();
        if (caseInput.fixtures().size() < 2 || counterexamples == 0) {
            issues.add(issue(EvaluationErrorCodes.COMPLEX_FIXTURES_INSUFFICIENT, path + "/fixtures",
                    "Complex case '" + caseInput.caseId() + "' must reference at least two fixtures,"
                            + " including one counterexample; it references " + caseInput.fixtures().size()
                            + " with " + counterexamples + " counterexample(s)"));
        }
    }

    private void validateExpectedMemberSets(
            CorpusBundle bundle,
            CaseInput caseInput,
            ExpectedResult expected,
            String path,
            List<Issue> issues) {

        Map<String, MemberIdSet> expectedSets = expected.expectedMemberIdsByFixture();

        for (String fixtureId : caseInput.fixtures()) {
            if (!expectedSets.containsKey(fixtureId)) {
                issues.add(issue(EvaluationErrorCodes.EXPECTED_SET_MISSING,
                        path + "/expectedMemberIdsByFixture/" + fixtureId,
                        "Fixture '" + fixtureId + "' is referenced by case '" + caseInput.caseId()
                                + "' but has no expected member set"));
            }
        }
        for (Map.Entry<String, MemberIdSet> entry : expectedSets.entrySet()) {
            String fixtureId = entry.getKey();
            if (!caseInput.fixtures().contains(fixtureId)) {
                issues.add(issue(EvaluationErrorCodes.EXPECTED_SET_ORPHANED,
                        path + "/expectedMemberIdsByFixture/" + fixtureId,
                        "Case '" + caseInput.caseId() + "' declares an expected member set for fixture '"
                                + fixtureId + "', which the case does not reference"));
                continue;
            }
            FixtureDescriptor fixture = bundle.fixture(fixtureId);
            if (fixture == null) {
                // The unknown fixture is already reported; a universe check would be meaningless.
                continue;
            }
            for (String memberId : entry.getValue().memberIds()) {
                if (!fixture.memberUniverse().contains(memberId)) {
                    issues.add(issue(EvaluationErrorCodes.MEMBER_ID_OUTSIDE_UNIVERSE,
                            path + "/expectedMemberIdsByFixture/" + fixtureId,
                            "Expected member '" + memberId + "' is outside the declared universe of fixture '"
                                    + fixtureId + "'"));
                }
            }
        }
    }

    private void validateIntentSlots(CaseInput caseInput, ExpectedResult expected, String path, List<Issue> issues) {
        for (ExpectedIntentSlot slot : expected.expectedIntentSlots()) {
            String slotPath = path + "/expectedIntentSlots/" + slot.slotId();
            SourceSpan span = slot.sourceSpan();
            if (slot.requiresSourceEvidence() && (span == null || span.isEmpty())) {
                issues.add(issue(EvaluationErrorCodes.SOURCE_SPAN_MISSING, slotPath + "/sourceSpan",
                        "Intent slot '" + slot.slotId() + "' claims grounding but carries no source span"));
            }
            if (span == null) {
                continue;
            }
            int utteranceLength = caseInput.utterance().length();
            if (span.start() > utteranceLength || span.end() > utteranceLength) {
                issues.add(issue(EvaluationErrorCodes.SOURCE_SPAN_OUT_OF_BOUNDS, slotPath + "/sourceSpan",
                        "Source span [" + span.start() + ", " + span.end() + ") falls outside the utterance of "
                                + utteranceLength + " UTF-16 code units"));
            } else if (!span.text().equals(caseInput.utterance().substring(span.start(), span.end()))) {
                issues.add(issue(EvaluationErrorCodes.SOURCE_SPAN_TEXT_MISMATCH, slotPath + "/sourceSpan",
                        "Source span text '" + span.text() + "' does not match the utterance range ["
                                + span.start() + ", " + span.end() + ")"));
            }
        }
    }

    private void validateDispositionCoherence(ExpectedResult expected, String path, List<Issue> issues) {
        if (expected.expectedDisposition() == Disposition.NEEDS_INPUT
                && expected.expectedClarificationQuestionCodes().isEmpty()) {
            issues.add(issue(EvaluationErrorCodes.CLARIFICATION_CODES_MISSING,
                    path + "/expectedClarificationQuestionCodes",
                    "Case '" + expected.caseId()
                            + "' expects NEEDS_INPUT but declares no clarification question code"));
        }
        if (expected.expectedCanonicalAst() != null && !expected.permitsCanonicalAst()) {
            issues.add(issue(EvaluationErrorCodes.EXPECTED_AST_NOT_PERMITTED, path + "/expectedCanonicalAst",
                    "Case '" + expected.caseId() + "' expects " + expected.expectedDisposition()
                            + " and must not declare an expected canonical AST"));
        }
    }

    private static boolean sameTimeContext(FixtureDescriptor left, FixtureDescriptor right) {
        return left.asOf().equals(right.asOf())
                && left.timezone().equals(right.timezone())
                && left.dataWatermark().equals(right.dataWatermark());
    }

    private static boolean sameTimeContext(TrustedExecutionContextRef context, FixtureDescriptor fixture) {
        return context.asOf().equals(fixture.asOf())
                && context.timezone().equals(fixture.timezone())
                && context.dataWatermark().equals(fixture.dataWatermark());
    }

    /** A blocking issue with a location path and no restricted payload. */
    static Issue issue(String code, String path, String message) {
        return new Issue(code, IssueSeverity.ERROR, path, null, null, message, Map.of());
    }
}