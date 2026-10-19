package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.intent.IntentAmbiguity;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.LogicalExpression;
import com.company.campaign.segment.intent.LogicalOperator;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 10: ambiguous mixed AND/OR, missing money currency, ambiguous metric meaning and ambiguous
 * NULL inclusion all return {@code NEEDS_INPUT} with a stable question code.
 */
class IntentResolutionAmbiguityTest {

    private final IntentResolver resolver = new IntentResolver(
            ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());

    @Test
    void eachRecordedAmbiguityReturnsNeedsInputWithItsStableQuestion() {
        for (IntentAmbiguity ambiguity : List.of(
                IntentAmbiguity.AMBIGUOUS_GROUPING,
                IntentAmbiguity.MISSING_CURRENCY,
                IntentAmbiguity.AMBIGUOUS_METRIC_MEANING,
                IntentAmbiguity.AMBIGUOUS_NULL_INCLUSION,
                IntentAmbiguity.AMBIGUOUS_RELATION_SCOPE,
                IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE,
                IntentAmbiguity.MISSING_METADATA_DEFINITION,
                IntentAmbiguity.AMBIGUOUS_WINDOW)) {
            SegmentIntent intent = new SegmentIntent(
                    ContractVersions.CURRENT, SubjectKind.MEMBER,
                    List.of(ResolutionFixtures.tierClause(), ResolutionFixtures.spendClause()),
                    new LogicalExpression.Group(LogicalOperator.ALL, List.of(
                            new LogicalExpression.Clause("c1"), new LogicalExpression.Clause("c2"))),
                    List.of(ambiguity), List.of());
            ResolutionResult result = resolver.resolve(intent);
            assertThat(result.disposition()).as("ambiguity %s", ambiguity).isEqualTo(Disposition.NEEDS_INPUT);
            assertThat(result.clarification().questionCode())
                    .as("question for %s", ambiguity)
                    .isEqualTo(QuestionCode.forAmbiguity(ambiguity));
        }
    }

    @Test
    void ambiguousMixedAndOrGroupingReturnsNeedsInput() {
        IntentClause first = IntentFixtures.clause("c1", "A", IntentFixtures.FULL_SPAN);
        IntentClause second = IntentFixtures.clause("c2", "B", IntentFixtures.FULL_SPAN);
        IntentClause third = IntentFixtures.clause("c3", "C", IntentFixtures.FULL_SPAN);
        LogicalExpression mixed = new LogicalExpression.Group(LogicalOperator.ALL, List.of(
                new LogicalExpression.Clause("c1"),
                new LogicalExpression.Group(LogicalOperator.ANY, List.of(
                        new LogicalExpression.Clause("c2"), new LogicalExpression.Clause("c3")))));
        SegmentIntent intent = new SegmentIntent(
                ContractVersions.CURRENT, SubjectKind.MEMBER, List.of(first, second, third), mixed,
                List.of(IntentAmbiguity.AMBIGUOUS_GROUPING), List.of());

        ResolutionResult result = resolver.resolve(intent);
        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.clarification().questionCode()).isEqualTo(QuestionCode.AMBIGUOUS_GROUPING);
        assertThat(result.clarification().clauseId()).isEqualTo("c1");
    }

    @Test
    void moneyWithoutCurrencyIntentReturnsNeedsInputForMissingCurrency() {
        IntentClause spend = IntentFixtures.withCurrency(
                IntentFixtures.withOperator(ResolutionFixtures.spendClause(),
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL),
                null);
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.emptyRegistry())
                .resolve(IntentFixtures.intent(spend));
        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.clarification().questionCode()).isEqualTo(QuestionCode.MISSING_CURRENCY);
    }

    @Test
    void ambiguousNullInclusionReturnsNeedsInputFromTheRejectionPath() {
        IntentClause clause = IntentFixtures.withOperator(
                ResolutionFixtures.tierClause(), com.company.campaign.metadata.domain.ComparisonOperator.NOT_EQUAL);
        ResolutionResult result = resolver.resolve(IntentFixtures.intent(clause));
        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.clause("c1").questionCodes()).contains(QuestionCode.AMBIGUOUS_NULL_INCLUSION);
    }
}
