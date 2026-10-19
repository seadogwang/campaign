package com.company.campaign.evaluation.testsupport;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link ExpectedResult} instances for tests without repeating every component.
 *
 * <p>The defaults describe one fully valid expectation, so a test that wants to exercise a single defect
 * changes one field and leaves the rest correct — which is what makes a failure attributable.
 */
public final class ExpectedResultBuilder {

    private String caseId = "case-attr-001";
    private Disposition disposition = Disposition.RESOLVED;
    private List<ExpectedIntentSlot> intentSlots = new ArrayList<>(List.of(
            new ExpectedIntentSlot("c1", "MEMBER_TIER", "EQUAL", true,
                    new SourceSpan("Gold", 0, 4), TextNode.valueOf("GOLD"))));
    private List<String> selectedCandidates = new ArrayList<>(List.of("member.tier"));
    private List<String> rejectedCandidates = new ArrayList<>();
    private JsonNode canonicalAst;
    private List<String> validationIssueCodes = new ArrayList<>();
    private List<String> clarificationCodes = new ArrayList<>();
    private Map<String, MemberIdSet> memberSets = new LinkedHashMap<>();
    private List<String> claimIds = new ArrayList<>();
    private List<String> forbiddenAssumptions = new ArrayList<>();

    public static ExpectedResultBuilder expected() {
        return new ExpectedResultBuilder();
    }

    /** Starts from an existing expectation, so only the tested field has to change. */
    public static ExpectedResultBuilder from(ExpectedResult source) {
        ExpectedResultBuilder builder = new ExpectedResultBuilder();
        builder.caseId = source.caseId();
        builder.disposition = source.expectedDisposition();
        builder.intentSlots = new ArrayList<>(source.expectedIntentSlots());
        builder.selectedCandidates = new ArrayList<>(source.expectedSelectedCandidateIds());
        builder.rejectedCandidates = new ArrayList<>(source.expectedRejectedCandidateIds());
        builder.canonicalAst = source.expectedCanonicalAst();
        builder.validationIssueCodes = new ArrayList<>(source.expectedValidationIssueCodes());
        builder.clarificationCodes = new ArrayList<>(source.expectedClarificationQuestionCodes());
        builder.memberSets = new LinkedHashMap<>(source.expectedMemberIdsByFixture());
        builder.claimIds = new ArrayList<>(source.groundedExplanationClaimIds());
        builder.forbiddenAssumptions = new ArrayList<>(source.forbiddenAssumptions());
        return builder;
    }

    public ExpectedResultBuilder caseId(String value) {
        this.caseId = value;
        return this;
    }

    public ExpectedResultBuilder disposition(Disposition value) {
        this.disposition = value;
        return this;
    }

    public ExpectedResultBuilder intentSlots(List<ExpectedIntentSlot> value) {
        this.intentSlots = new ArrayList<>(value);
        return this;
    }

    public ExpectedResultBuilder selectedCandidates(List<String> value) {
        this.selectedCandidates = new ArrayList<>(value);
        return this;
    }

    public ExpectedResultBuilder rejectedCandidates(List<String> value) {
        this.rejectedCandidates = new ArrayList<>(value);
        return this;
    }

    public ExpectedResultBuilder canonicalAst(JsonNode value) {
        this.canonicalAst = value;
        return this;
    }

    public ExpectedResultBuilder validating(String... codes) {
        this.validationIssueCodes = new ArrayList<>(List.of(codes));
        return this;
    }

    public ExpectedResultBuilder clarificationCodes(List<String> value) {
        this.clarificationCodes = new ArrayList<>(value);
        return this;
    }

    public ExpectedResultBuilder memberSet(String fixtureId, List<String> members) {
        this.memberSets.put(fixtureId, new MemberIdSet(members));
        return this;
    }

    public ExpectedResultBuilder memberSets(Map<String, MemberIdSet> value) {
        this.memberSets = new LinkedHashMap<>(value);
        return this;
    }

    public ExpectedResultBuilder claimIds(List<String> value) {
        this.claimIds = new ArrayList<>(value);
        return this;
    }

    public ExpectedResultBuilder forbiddenAssumptions(List<String> value) {
        this.forbiddenAssumptions = new ArrayList<>(value);
        return this;
    }

    public ExpectedResult build() {
        return new ExpectedResult(EvaluationVersions.CURRENT, caseId, disposition, intentSlots,
                selectedCandidates, rejectedCandidates, canonicalAst, validationIssueCodes,
                clarificationCodes, memberSets, claimIds, null, forbiddenAssumptions);
    }
}