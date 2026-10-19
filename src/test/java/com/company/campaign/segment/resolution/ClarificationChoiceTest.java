package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 12: a clarification choice succeeds for the exact current resolution and becomes stale after
 * any Intent, Metadata, policy, permitted-catalog/default-registry, question or option change.
 */
class ClarificationChoiceTest {

    private final IntentResolver resolver = new IntentResolver(
            ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());
    private final ClarificationChoiceService service = new ClarificationChoiceService(resolver);

    private SegmentIntent ambiguousIntent() {
        IntentClause spend = IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL);
        return IntentFixtures.intent(spend);
    }

    private ResolutionResult ambiguousResolution() {
        return resolver.resolve(ambiguousIntent());
    }

    private ClarificationChoice choiceFor(ResolutionResult result, ClarificationOption option) {
        return new ClarificationChoice(
                result.clarification().resolutionId(), option.optionId(), result.baseIntentHash(),
                result.pins(), result.clarification().questionCode(), result.clarification().clauseId());
    }

    @Test
    void applyingTheExactCurrentChoiceReturnsANewDeterministicResolution() {
        ResolutionResult result = ambiguousResolution();
        ClarificationOption option = result.clarification().options().stream()
                .filter(candidate -> candidate.definitionId().equals("order.gross_amount"))
                .findFirst().orElseThrow();
        ClarificationChoice choice = choiceFor(result, option);

        ClarificationChoiceResult applied = service.apply(ambiguousIntent(), choice);
        assertThat(applied.appliedClauseId()).isEqualTo("c2");
        assertThat(applied.appliedOptionId()).isEqualTo(option.optionId());
        assertThat(applied.resolution().disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(applied.resolution().clause("c2").accepted()).isEqualTo("order.gross_amount");
        assertThat(applied.resolution()).isEqualTo(service.apply(ambiguousIntent(), choice).resolution());
    }

    @Test
    void aChangedIntentMakesTheChoiceStale() {
        ResolutionResult result = ambiguousResolution();
        ClarificationChoice choice = choiceFor(result, result.clarification().options().get(0));
        IntentClause changed = IntentFixtures.withHints(
                IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL),
                List.of("order.net_paid_amount"));
        assertFailsWithCode(
                () -> service.apply(IntentFixtures.intent(changed), choice),
                ResolutionErrorCodes.STALE_CLARIFICATION);
    }

    @Test
    void changedMetadataPolicyAndRegistryPinsMakeTheChoiceStale() {
        ResolutionResult result = ambiguousResolution();
        ClarificationChoice choice = choiceFor(result, result.clarification().options().get(0));

        IntentResolver differentPolicy = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(),
                ResolutionPolicy.publish("resolution-policy/2.0", null),
                ResolutionFixtures.registry());
        assertFailsWithCode(
                () -> new ClarificationChoiceService(differentPolicy).apply(ambiguousIntent(), choice),
                ResolutionErrorCodes.STALE_CLARIFICATION);

        IntentResolver differentCatalog = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());
        assertFailsWithCode(
                () -> new ClarificationChoiceService(differentCatalog).apply(ambiguousIntent(), choice),
                ResolutionErrorCodes.STALE_CLARIFICATION);

        IntentResolver differentRegistry = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(),
                IntentFixtures.driftedRegistry());
        assertFailsWithCode(
                () -> new ClarificationChoiceService(differentRegistry).apply(ambiguousIntent(), choice),
                ResolutionErrorCodes.STALE_CLARIFICATION);
    }

    @Test
    void aChangedQuestionOrClauseMakesTheChoiceStale() {
        ResolutionResult result = ambiguousResolution();
        ClarificationChoice base = choiceFor(result, result.clarification().options().get(0));
        ClarificationChoice changedQuestion = new ClarificationChoice(
                base.resolutionId(), base.optionId(), base.baseIntentHash(), base.pins(),
                QuestionCode.MISSING_CURRENCY, base.clauseId());
        assertFailsWithCode(
                () -> service.apply(ambiguousIntent(), changedQuestion),
                ResolutionErrorCodes.STALE_CLARIFICATION);
    }

    @Test
    void anUnknownOptionMakesTheChoiceStale() {
        ResolutionResult result = ambiguousResolution();
        ClarificationChoice base = choiceFor(result, result.clarification().options().get(0));
        ClarificationChoice unknownOption = new ClarificationChoice(
                base.resolutionId(),
                ClarificationOption.deriveOptionId("c2", "not.a.permitted.definition"),
                base.baseIntentHash(), base.pins(), base.questionCode(), base.clauseId());
        assertFailsWithCode(
                () -> service.apply(ambiguousIntent(), unknownOption),
                ResolutionErrorCodes.STALE_CLARIFICATION);
    }

    @Test
    void aChangedOptionSetMakesTheChoiceStale() {
        ResolutionResult twoOption = ambiguousResolution();
        ClarificationChoice choice = choiceFor(twoOption, twoOption.clarification().options().get(0));

        IntentResolver threeOptionResolver = new IntentResolver(
                ResolutionFixtures.threeMetricSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());
        ResolutionResult threeOption = threeOptionResolver.resolve(ambiguousIntent());
        assertThat(threeOption.clarification().options()).hasSize(3);
        assertThat(threeOption.clarification().resolutionId()).isNotEqualTo(twoOption.clarification().resolutionId());

        assertFailsWithCode(
                () -> new ClarificationChoiceService(threeOptionResolver).apply(ambiguousIntent(), choice),
                ResolutionErrorCodes.STALE_CLARIFICATION);
    }
}