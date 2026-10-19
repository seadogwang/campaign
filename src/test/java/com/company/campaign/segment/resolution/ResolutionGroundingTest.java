package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction 1: structural compatibility alone can never produce {@code RESOLVED}. A candidate is accepted
 * automatically only with exact identification evidence — a stable-id hint, a display/alias/example match or
 * the policy's versioned concept mapping — and the tag "the only structurally compatible definition" is not
 * such evidence.
 */
class ResolutionGroundingTest {

    @Test
    void aSoleUnrelatedStructuralMatchIsNotAccepted() {
        IntentClause clause = IntentFixtures.withConcept(
                ResolutionFixtures.spendClauseWithUnrelatedSpan(), "UNRELATED_THING");
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(clause));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        ClauseResolution resolved = result.clause("c2");
        assertThat(resolved.accepted()).isNull();
        assertThat(resolved.candidates()).hasSize(1);
        assertThat(resolved.candidates().get(0).definitionId()).isEqualTo("order.net_paid_amount");
        assertThat(resolved.candidates().get(0).tier()).isEqualTo(CandidateTier.STRUCTURAL_MATCH);
        assertThat(resolved.candidates().get(0).tier().isIdentificationEvidence()).isFalse();
        assertThat(resolved.candidates().get(0).evidence())
                .doesNotContain(
                        EvidenceCode.EXACT_STABLE_ID_HINT,
                        EvidenceCode.CONCEPT_POLICY_MATCH,
                        EvidenceCode.EXACT_DISPLAY_MATCH,
                        EvidenceCode.EXACT_ALIAS_MATCH,
                        EvidenceCode.POSITIVE_EXAMPLE_MATCH);
        assertThat(resolved.questionCodes()).contains(QuestionCode.SELECT_DEFINITION);
        assertThat(result.clarification().questionCode()).isEqualTo(QuestionCode.SELECT_DEFINITION);
    }

    @Test
    void conceptPolicyMappingResolvesTheExactPermittedDefinition() {
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(ResolutionFixtures.spendClauseWithUnrelatedSpan()));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        ClauseResolution resolved = result.clause("c2");
        assertThat(resolved.accepted()).isEqualTo(ResolutionFixtures.SPEND_DEFINITION_ID);
        assertThat(resolved.candidates().get(0).tier()).isEqualTo(CandidateTier.EXACT_CONCEPT_POLICY_MATCH);
        assertThat(resolved.candidates().get(0).evidence()).contains(EvidenceCode.CONCEPT_POLICY_MATCH);
    }

    @Test
    void aConceptMappingThatDiffersChangesThePolicyPinAndMakesAClarificationStale() {
        ResolutionPolicy mappedToNetPaid = ResolutionFixtures.policyWithConceptMapping(
                Map.of(ResolutionFixtures.SPEND_CONCEPT, ResolutionFixtures.SPEND_DEFINITION_ID));
        ResolutionPolicy mappedToGross = ResolutionFixtures.policyWithConceptMapping(
                Map.of(ResolutionFixtures.SPEND_CONCEPT, "order.gross_amount"));

        assertThat(mappedToGross.contentHash()).isNotEqualTo(mappedToNetPaid.contentHash());
        assertThat(mappedToGross.hashMaterial()).isNotEqualTo(mappedToNetPaid.hashMaterial());
        assertThat(mappedToGross.conceptDefinitionId(ResolutionFixtures.SPEND_CONCEPT))
                .isEqualTo("order.gross_amount");
        assertThat(mappedToNetPaid.conceptDefinitionId("UNMAPPED_CONCEPT")).isNull();

        IntentClause spend = IntentFixtures.withOperator(
                ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL);
        IntentResolver original = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), mappedToNetPaid, ResolutionFixtures.registry());
        ResolutionResult result = original.resolve(IntentFixtures.intent(spend));
        ClarificationOption option = result.clarification().options().get(0);
        ClarificationChoice choice = new ClarificationChoice(
                result.clarification().resolutionId(), option.optionId(), result.baseIntentHash(),
                result.pins(), result.clarification().questionCode(), result.clarification().clauseId());

        IntentResolver changedPolicy = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), mappedToGross, ResolutionFixtures.registry());
        assertThat(changedPolicy.resolve(IntentFixtures.intent(spend)).pins().policyHash())
                .isNotEqualTo(result.pins().policyHash());
        assertFailsWithCode(
                () -> new ClarificationChoiceService(changedPolicy).apply(IntentFixtures.intent(spend), choice),
                ResolutionErrorCodes.STALE_CLARIFICATION);
    }

    @Test
    void aConceptMappingToAnAbsentOrFilteredDefinitionDoesNotDiscloseIt() {
        String absentDefinitionId = "order.secret.denied";
        ResolutionPolicy policy = ResolutionFixtures.policyWithConceptMapping(
                Map.of(ResolutionFixtures.SPEND_CONCEPT, absentDefinitionId));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), policy, ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(ResolutionFixtures.spendClauseWithUnrelatedSpan()));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        ClauseResolution resolved = result.clause("c2");
        assertThat(resolved.candidates()).hasSize(1);
        assertThat(resolved.candidates().get(0).definitionId()).isEqualTo("order.net_paid_amount");
        assertThat(resolved.candidates().get(0).tier()).isEqualTo(CandidateTier.STRUCTURAL_MATCH);
        assertThat(resolved.candidates()).noneMatch(candidate -> candidate.definitionId().equals(absentDefinitionId));
        assertThat(resolved.rejected()).noneMatch(rejected -> rejected.definitionId().equals(absentDefinitionId));
        assertThat(result.toString()).doesNotContain(absentDefinitionId);
        assertThat(result.clarification().toString()).doesNotContain(absentDefinitionId);
    }

    @Test
    void anUnknownHintNeverGroundsOrDisclosesADefinition() {
        IntentClause clause = IntentFixtures.withHints(
                IntentFixtures.withConcept(ResolutionFixtures.spendClauseWithUnrelatedSpan(), "UNRELATED_THING"),
                List.of("order.secret.denied"));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(clause));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.clause("c2").candidates().get(0).tier()).isEqualTo(CandidateTier.STRUCTURAL_MATCH);
        assertThat(result.toString()).doesNotContain("order.secret.denied");
        assertThat(result.clarification().toString()).doesNotContain("order.secret.denied");
    }

    @Test
    void multipleGroundedCompatibleCandidatesStillNeedInput() {
        IntentClause spend = IntentFixtures.withHints(
                IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL),
                List.of("order.net_paid_amount", "order.gross_amount"));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(spend));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        ClauseResolution clause = result.clause("c2");
        assertThat(clause.accepted()).isNull();
        assertThat(clause.candidates()).hasSize(2);
        assertThat(clause.candidates()).allMatch(candidate -> candidate.tier().isIdentificationEvidence());
        assertThat(clause.questionCodes()).contains(QuestionCode.DISAMBIGUATE_DEFINITION);
        assertThat(result.clarification().options()).hasSize(2);
    }
}