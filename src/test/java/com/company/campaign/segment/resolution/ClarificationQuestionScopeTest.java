package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.intent.IntentAmbiguity;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction 3: only definition-selection questions may expose definition options or be applied by
 * {@link ClarificationChoiceService}. A grouping, currency, NULL inclusion, window or relation-scope question
 * carries no definition options, and a definition choice offered against it fails as stale without changing
 * the resolution — so a global ambiguity can never be "answered" for its first clause.
 */
class ClarificationQuestionScopeTest {

    private final IntentResolver resolver = new IntentResolver(
            ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());
    private final ClarificationChoiceService service = new ClarificationChoiceService(resolver);

    @Test
    void nonDefinitionQuestionsExposeNoDefinitionOptions() {
        for (IntentAmbiguity ambiguity : List.of(
                IntentAmbiguity.AMBIGUOUS_GROUPING,
                IntentAmbiguity.MISSING_CURRENCY,
                IntentAmbiguity.AMBIGUOUS_NULL_INCLUSION,
                IntentAmbiguity.AMBIGUOUS_WINDOW,
                IntentAmbiguity.AMBIGUOUS_RELATION_SCOPE,
                IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE,
                IntentAmbiguity.MISSING_METADATA_DEFINITION)) {
            SegmentIntent intent = IntentFixtures.intent(
                    List.of(ResolutionFixtures.tierClause(), ResolutionFixtures.spendClause()),
                    null, List.of(ambiguity), List.of());
            ResolutionResult result = resolver.resolve(intent);

            assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
            assertThat(result.clarification().questionCode())
                    .as("question for %s", ambiguity)
                    .isEqualTo(QuestionCode.forAmbiguity(ambiguity));
            assertThat(result.clarification().questionCode().isDefinitionSelection())
                    .as("%s is not a definition selection", ambiguity)
                    .isFalse();
            assertThat(result.clarification().options())
                    .as("options for %s must not leak definitions", ambiguity)
                    .isEmpty();
        }
    }

    @Test
    void aDefinitionChoiceAgainstAnyNonDefinitionGlobalQuestionFailsStale() {
        for (IntentAmbiguity ambiguity : List.of(
                IntentAmbiguity.AMBIGUOUS_GROUPING,
                IntentAmbiguity.MISSING_CURRENCY,
                IntentAmbiguity.AMBIGUOUS_NULL_INCLUSION,
                IntentAmbiguity.AMBIGUOUS_WINDOW,
                IntentAmbiguity.AMBIGUOUS_RELATION_SCOPE,
                IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE,
                IntentAmbiguity.MISSING_METADATA_DEFINITION)) {
            // A global ambiguity is most fundamental; selecting the first clause's candidate must never
            // "answer" it, even when that clause would otherwise resolve.
            SegmentIntent intent = IntentFixtures.intent(
                    List.of(ResolutionFixtures.tierClause(), ResolutionFixtures.spendClause()),
                    null, List.of(ambiguity), List.of());
            ResolutionResult result = resolver.resolve(intent);
            Clarification clarification = result.clarification();
            assertThat(clarification.questionCode()).isEqualTo(QuestionCode.forAmbiguity(ambiguity));
            assertThat(clarification.options()).isEmpty();

            ClarificationChoice choice = new ClarificationChoice(
                    clarification.resolutionId(),
                    ClarificationOption.deriveOptionId(clarification.clauseId(), "member.tier"),
                    result.baseIntentHash(), result.pins(), clarification.questionCode(),
                    clarification.clauseId());

            assertFailsWithCode(
                    () -> service.apply(intent, choice),
                    ResolutionErrorCodes.STALE_CLARIFICATION);
            assertThat(resolver.resolve(intent))
                    .as("the resolution for %s is unchanged", ambiguity)
                    .isEqualTo(result);
        }
    }

    @Test
    void aDefinitionChoiceAgainstAMissingCurrencyQuestionFailsStale() {
        IntentClause spend = IntentFixtures.withCurrency(
                IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL), null);
        IntentResolver moneyResolver = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.emptyRegistry());
        SegmentIntent intent = IntentFixtures.intent(spend);
        ResolutionResult result = moneyResolver.resolve(intent);
        assertThat(result.clarification().questionCode()).isEqualTo(QuestionCode.MISSING_CURRENCY);
        assertThat(result.clarification().options()).isEmpty();

        ClarificationChoice choice = new ClarificationChoice(
                result.clarification().resolutionId(),
                ClarificationOption.deriveOptionId("c2", "order.net_paid_amount"),
                result.baseIntentHash(), result.pins(), result.clarification().questionCode(),
                result.clarification().clauseId());

        assertFailsWithCode(
                () -> new ClarificationChoiceService(moneyResolver).apply(intent, choice),
                ResolutionErrorCodes.STALE_CLARIFICATION);
        assertThat(moneyResolver.resolve(intent)).as("the resolution is unchanged").isEqualTo(result);
    }

    @Test
    void aDefinitionSelectionQuestionStillExposesOptionsAndApplies() {
        IntentClause spend = IntentFixtures.withOperator(
                ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL);
        IntentResolver ambiguous = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry());
        SegmentIntent intent = IntentFixtures.intent(spend);
        ResolutionResult result = ambiguous.resolve(intent);

        assertThat(result.clarification().questionCode().isDefinitionSelection()).isTrue();
        assertThat(result.clarification().options()).hasSize(2);
        ClarificationOption option = result.clarification().options().get(0);
        ClarificationChoice choice = new ClarificationChoice(
                result.clarification().resolutionId(), option.optionId(), result.baseIntentHash(),
                result.pins(), result.clarification().questionCode(), result.clarification().clauseId());

        ClarificationChoiceResult applied = new ClarificationChoiceService(ambiguous).apply(intent, choice);
        assertThat(applied.resolution().disposition()).isEqualTo(Disposition.RESOLVED);
    }
}
