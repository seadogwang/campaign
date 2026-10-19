package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Proves that a corpus's Metadata payloads really are T04 snapshots, and that every selected or rejected
 * candidate names a definition the snapshot declares.
 *
 * <p>T03 treated the payload as opaque bytes, which was correct while Metadata semantics belonged to T04.
 * T06 consumes T04 as a typed input, so a candidate corpus has to carry a real snapshot: the payload must
 * parse as a {@code MetadataSnapshot} (which recomputes its own content hash at construction, so a
 * hand-authored hash cannot survive), its identity must match the catalog descriptor that points at it, and
 * the file bytes must be the canonical serialization of the value they contain.
 *
 * <p>Operator and data-type compatibility is deliberately <em>not</em> re-validated here: that is T04's and
 * T07's contract. The only Metadata-level checks are the ones needed to keep a corpus internally
 * consistent — a definition id a case references must exist, or the fixture is impossible.
 */
public final class MetadataPayloadValidator {

    /** Reports every Metadata payload and candidate-reference problem, in catalog and manifest order. */
    public List<Issue> validate(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        CorpusMetadataView view = CorpusMetadataView.of(bundle);
        List<Issue> issues = new ArrayList<>(view.issues());

        List<CaseInput> cases = bundle.cases();
        List<ExpectedResult> expectedResults = bundle.expectedResults();
        for (int index = 0; index < cases.size(); index++) {
            CaseInput caseInput = cases.get(index);
            ExpectedResult expected = expectedResults.get(index);
            Set<String> declared = view.definitionIds(caseInput.metadataSnapshot());
            if (declared == null) {
                // The payload problem is already reported; a membership check would be meaningless.
                continue;
            }
            checkCandidates(index, caseInput, declared, "expectedSelectedCandidateIds",
                    expected.expectedSelectedCandidateIds(), EvaluationErrorCodes.SELECTED_CANDIDATE_UNKNOWN,
                    issues);
            checkCandidates(index, caseInput, declared, "expectedRejectedCandidateIds",
                    expected.expectedRejectedCandidateIds(), EvaluationErrorCodes.REJECTED_CANDIDATE_UNKNOWN,
                    issues);
        }
        return issues;
    }

    private void checkCandidates(
            int index,
            CaseInput caseInput,
            Set<String> declared,
            String field,
            List<String> candidateIds,
            String code,
            List<Issue> issues) {

        for (String candidateId : candidateIds) {
            if (!declared.contains(candidateId)) {
                issues.add(SegmentEvaluationValidator.issue(
                        code, "/cases/" + index + "/" + caseInput.caseId() + "/" + field + "/" + candidateId,
                        "Case '" + caseInput.caseId() + "' references Metadata candidate '" + candidateId
                                + "', which snapshot '" + caseInput.metadataSnapshot() + "' does not declare"));
            }
        }
    }
}