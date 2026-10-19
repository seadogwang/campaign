package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The expected answer for one case, held in a file separate from the case input.
 *
 * <p>Separation is the point. A reviewed corpus needs the question and the answer to be independently
 * replaceable and independently hashed, and the Gate compares the answer the system produced with the
 * answer that was frozen — not with a field the system itself wrote into the question.
 *
 * <p>Set-like expectations (forbidden assumptions, clarification question codes, grounded claim ids,
 * selected and rejected candidate ids, and each expected member set) are normalized once at construction,
 * so two files that say the same thing serialize to the same canonical bytes. The expected validation
 * issue list keeps its declared order because issue ordering is itself an expectation, and Segment
 * specification section 23.1 requires it to be reproducible.
 *
 * <p>{@code expectedCanonicalAst} is a canonical JSON tree placeholder until T05 owns the typed DSL. It is
 * present only for dispositions that can legitimately produce an AST; the validator refuses one on
 * {@code NEEDS_INPUT}, {@code UNSUPPORTED} and {@code DENIED}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpectedResult(
        SchemaVersion schemaVersion,
        String caseId,
        Disposition expectedDisposition,
        List<ExpectedIntentSlot> expectedIntentSlots,
        List<String> expectedSelectedCandidateIds,
        List<String> expectedRejectedCandidateIds,
        JsonNode expectedCanonicalAst,
        List<String> expectedValidationIssueCodes,
        List<String> expectedClarificationQuestionCodes,
        Map<String, MemberIdSet> expectedMemberIdsByFixture,
        List<String> groundedExplanationClaimIds,
        RefinementTargetPlaceholder refinementTarget,
        List<String> forbiddenAssumptions
) {
    /**
     * Dispositions that must not carry an expected canonical AST.
     *
     * <p>These three terminate before a typed predicate exists: {@code NEEDS_INPUT} has not been resolved
     * yet, {@code UNSUPPORTED} has no capability to compile, and {@code DENIED} is refused before semantics
     * are considered. v1.3 defines no future-safe exception to this rule, so the set is closed; a later
     * version that needs one must add an explicit, named field rather than relax the check.
     */
    public static final Set<Disposition> AST_PROHIBITED_DISPOSITIONS =
            Set.of(Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED);

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public ExpectedResult {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Expected result must declare a schema version");
        }
        if (caseId == null || !REFERENCE_ID.matcher(caseId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Expected result caseId '" + caseId + "' is not a valid reference identifier");
        }
        if (expectedDisposition == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Expected result for case '" + caseId + "' must declare the expected disposition");
        }
        if (expectedIntentSlots == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Expected result for case '" + caseId + "' must declare its Intent slot list");
        }
        Set<String> slotIds = new HashSet<>(expectedIntentSlots.size());
        for (ExpectedIntentSlot slot : expectedIntentSlots) {
            if (slot == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ASSET_INVALID,
                        "Expected result for case '" + caseId + "' contains a null Intent slot");
            }
            if (!slotIds.add(slot.slotId())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_ID,
                        "Expected result for case '" + caseId + "' declares Intent slot '"
                                + slot.slotId() + "' more than once",
                        Map.of("caseId", caseId, "slotId", slot.slotId()));
            }
        }
        expectedIntentSlots = ImmutableValues.freezeList(expectedIntentSlots);

        expectedSelectedCandidateIds = EvaluationCollections.require(
                expectedSelectedCandidateIds, "expectedSelectedCandidateIds", REFERENCE_ID, true);
        expectedRejectedCandidateIds = EvaluationCollections.require(
                expectedRejectedCandidateIds, "expectedRejectedCandidateIds", REFERENCE_ID, true);
        expectedValidationIssueCodes = EvaluationCollections.require(
                expectedValidationIssueCodes, "expectedValidationIssueCodes", MACHINE_CODE, false);
        expectedClarificationQuestionCodes = EvaluationCollections.require(
                expectedClarificationQuestionCodes, "expectedClarificationQuestionCodes", MACHINE_CODE, true);
        groundedExplanationClaimIds = EvaluationCollections.require(
                groundedExplanationClaimIds, "groundedExplanationClaimIds", MACHINE_CODE, true);
        forbiddenAssumptions = EvaluationCollections.require(
                forbiddenAssumptions, "forbiddenAssumptions", MACHINE_CODE, true);

        if (expectedMemberIdsByFixture == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Expected result for case '" + caseId + "' must declare its member sets, even when empty");
        }
        for (Map.Entry<String, MemberIdSet> entry : expectedMemberIdsByFixture.entrySet()) {
            if (entry.getKey() == null || !REFERENCE_ID.matcher(entry.getKey()).matches()) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ID_INVALID,
                        "Expected result for case '" + caseId + "' keys a member set by invalid fixture id '"
                                + entry.getKey() + "'");
            }
            if (entry.getValue() == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ASSET_INVALID,
                        "Expected result for case '" + caseId + "' has a missing member set for fixture '"
                                + entry.getKey() + "'");
            }
        }
        expectedMemberIdsByFixture = ImmutableValues.freezeMap(expectedMemberIdsByFixture);
    }

    /** True when this expectation claims a canonical AST is permitted for the expected disposition. */
    public boolean permitsCanonicalAst() {
        return !AST_PROHIBITED_DISPOSITIONS.contains(expectedDisposition);
    }

    /**
     * True when this expectation requires a genuinely valid canonical AST and Query Plan.
     *
     * <p>Only a {@code RESOLVED} expectation claims a resolved document, so only it must carry reviewed
     * evidence the genuine T07 validator accepts and the genuine T08 planner can plan. An {@code INVALID}
     * expectation is a deterministic validation refusal instead: it carries an expected DSL and the exact
     * issue-code sequence, and it never carries a Query Plan.
     */
    public boolean requiresResolvedEvidence() {
        return expectedDisposition == Disposition.RESOLVED;
    }

    /**
     * True when this expectation claims a deterministic validation/planning refusal.
     *
     * <p>Such an expectation must carry the reviewed invalid DSL plus the exact deterministic issue-code
     * sequence genuine T07 produces from it. A reviewed {@code INVALID} expectation with no DSL is missing
     * evidence rather than a legitimate refusal.
     */
    public boolean expectsInvalidEvidence() {
        return expectedDisposition == Disposition.INVALID;
    }
}