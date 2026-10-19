package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentCurrency;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.IntentWindow;
import com.company.campaign.segment.intent.IntentWindowUnit;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentHasher;
import com.company.campaign.segment.intent.SegmentIntentCodec;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 5, 6, 7, 8, 9 and 11: determinism, exactly-one acceptance, conflicting candidates, absent or
 * denied definitions, per-slot incompatibility reasons, and unsupported/bypass intents.
 */
class IntentResolutionTest {

    @Test
    void identicalInputsYieldIdenticalHashesOrderingAndIds() {
        SegmentIntent intent = IntentFixtures.standardIntent();
        IntentResolver resolver = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());

        ResolutionResult first = resolver.resolve(intent);
        ResolutionResult second = resolver.resolve(intent);
        SegmentIntent rebuiltIntent = IntentFixtures.standardIntent();
        ResolutionResult rebuilt = resolver.resolve(rebuiltIntent);

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(rebuilt);
        assertThat(SegmentIntentHasher.hash(intent)).isEqualTo(SegmentIntentHasher.hash(rebuiltIntent));
        assertThat(first.clauses().stream().map(ClauseResolution::candidates).toList())
                .isEqualTo(second.clauses().stream().map(ClauseResolution::candidates).toList());

        SegmentIntentCodec codec = new SegmentIntentCodec();
        SegmentIntent reparsed = codec.parse(
                codec.write(intent), IntentFixtures.UTTERANCE, ResolutionFixtures.registry());
        assertThat(resolver.resolve(reparsed)).isEqualTo(first);
    }

    @Test
    void exactlyOneFullyCompatibleDefinitionResolvesEveryClause() {
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(IntentFixtures.standardIntent());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.isResolved()).isTrue();
        assertThat(result.clarification()).isNull();
        assertThat(result.clause("c1").accepted()).isEqualTo("member.tier");
        assertThat(result.clause("c2").accepted()).isEqualTo("order.net_paid_amount");
    }

    @Test
    void singleHintAndConceptPolicyConsensusResolveAmongCompatibleCandidates() {
        IntentClause spend = IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL);
        spend = IntentFixtures.withHints(spend, List.of("order.net_paid_amount"));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(spend));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        ClauseResolution clause = result.clause("c2");
        assertThat(clause.accepted()).isEqualTo("order.net_paid_amount");
        assertThat(clause.candidates()).hasSize(2);
        assertThat(clause.candidates().get(0).definitionId()).isEqualTo("order.net_paid_amount");
        assertThat(clause.candidates().get(0).tier()).isEqualTo(CandidateTier.EXACT_STABLE_ID_HINT);
        assertThat(clause.candidates().get(0).evidence())
                .contains(EvidenceCode.EXACT_STABLE_ID_HINT, EvidenceCode.CONCEPT_POLICY_MATCH);
        assertThat(clause.candidates().get(1).score()).isLessThan(clause.candidates().get(0).score());
        assertThat(clause.questionCodes()).isEmpty();
        assertThat(result.clarification()).isNull();
    }

    @Test
    void hintAndConceptPolicyConflictStillRequiresClarification() {
        IntentClause spend = IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL);
        spend = IntentFixtures.withHints(spend, List.of("order.gross_amount"));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.ambiguousSpendSnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(spend));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        ClauseResolution clause = result.clause("c2");
        assertThat(clause.accepted()).isNull();
        assertThat(clause.candidates()).hasSize(2);
        assertThat(clause.questionCodes()).contains(QuestionCode.DISAMBIGUATE_DEFINITION);
        assertThat(result.clarification().options()).hasSize(2);
    }

    @Test
    void absentAndDeniedDefinitionsAreNonDisclosingAndNeverAccepted() {
        IntentClause clause = IntentFixtures.withHints(
                ResolutionFixtures.tierClause(), List.of("secret.denied.definition"));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.relationsOnlySnapshot(), ResolutionFixtures.policy(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(clause));

        ClauseResolution resolved = result.clause("c1");
        assertThat(resolved.accepted()).isNull();
        assertThat(resolved.candidates()).isEmpty();
        assertThat(resolved.rejected()).as("absence and denial are indistinguishable").isEmpty();
        assertThat(resolved.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(resolved.questionCodes()).contains(QuestionCode.SELECT_DEFINITION);
        assertThat(result.toString()).doesNotContain("secret.denied.definition");
        assertThat(result.clarification().toString()).doesNotContain("secret.denied.definition");
    }

    @Test
    void operatorTypeShapeWindowAggregationRelationAndCurrencyIncompatibilitiesHaveDistinctReasons() {
        assertOnlyReason(
                IntentFixtures.withOperator(IntentFixtures.withValue(
                        ResolutionFixtures.tierClause(), new IntentValue.StringValue("x")), ComparisonOperator.CONTAINS),
                RejectionReason.OPERATOR_NOT_ALLOWED);
        assertOnlyReason(
                IntentFixtures.withValue(ResolutionFixtures.tierClause(), new IntentValue.StringValue("Gold")),
                RejectionReason.VALUE_TYPE_INCOMPATIBLE);
        assertOnlyReason(
                IntentFixtures.withOperator(ResolutionFixtures.tierClause(), ComparisonOperator.IN),
                RejectionReason.VALUE_SHAPE_INCOMPATIBLE);
        assertOnlyReason(
                IntentFixtures.withOperator(ResolutionFixtures.tierClause(), ComparisonOperator.NOT_EQUAL),
                RejectionReason.NULL_INCLUSION_AMBIGUOUS);
        assertOnlyReason(
                IntentFixtures.withWindow(ResolutionFixtures.tierClause(),
                        new IntentWindow.Rolling(30, IntentWindowUnit.DAY)),
                RejectionReason.WINDOW_UNSUPPORTED);
        assertOnlyReason(
                IntentFixtures.withCurrency(ResolutionFixtures.tierClause(), new IntentCurrency.Explicit("CNY")),
                RejectionReason.CURRENCY_INCOMPATIBLE);
        assertOnlyReason(
                IntentFixtures.withAggregation(ResolutionFixtures.tierClause(), AggregateFunction.SUM),
                RejectionReason.AGGREGATION_MISMATCH);
        assertOnlyReason(
                IntentFixtures.withRelationScope(ResolutionFixtures.tierClause(),
                        new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "order.member_id")),
                RejectionReason.RELATION_MISMATCH);
    }

    @Test
    void aMetricWindowThatTheDefinitionDoesNotSupportIsRejected() {
        IntentClause lifetimeOnly = new IntentClause(
                "c2", "CHANNEL", MetadataKind.METRIC, false, ComparisonOperator.EQUAL,
                new IntentValue.StringValue("app"),
                new IntentWindow.Rolling(30, IntentWindowUnit.DAY),
                null, AggregateFunction.MIN, null,
                IntentFixtures.span("过去90天消费超过1000元", 7, 21), List.of(), List.of());
        var snapshot = com.company.campaign.metadata.testsupport.MetadataFixtures.snapshot(
                List.of(), List.of(com.company.campaign.metadata.testsupport.MetadataFixtures.textMinMetric()),
                List.of(com.company.campaign.metadata.testsupport.MetadataFixtures.ordersRelation()));
        ResolutionResult result = new IntentResolver(snapshot, ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(lifetimeOnly));

        assertThat(result.clause("c2").rejected())
                .anyMatch(rejected -> rejected.reason() == RejectionReason.WINDOW_UNSUPPORTED);
    }

    @Test
    void unsupportedAndBypassIntentsNeverBecomeCandidates() {
        SegmentIntent intent = IntentFixtures.intent(
                List.of(ResolutionFixtures.tierClause(), ResolutionFixtures.spendClause()),
                null, List.of(),
                List.of(com.company.campaign.segment.intent.UnsupportedRequirement.PERMISSION_BYPASS));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(intent);

        assertThat(result.disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(result.clarification()).isNull();
        for (ClauseResolution clause : result.clauses()) {
            assertThat(clause.disposition()).isEqualTo(Disposition.UNSUPPORTED);
            assertThat(clause.accepted()).isNull();
            assertThat(clause.candidates()).isEmpty();
            assertThat(clause.rejected()).isEmpty();
        }
    }

    @Test
    void moneyWithoutAnExplicitOrRegisteredCurrencyNeedsInput() {
        IntentClause spend = IntentFixtures.withCurrency(
                IntentFixtures.withOperator(ResolutionFixtures.spendClause(), ComparisonOperator.EQUAL),
                null);
        ResolutionResult withoutDefault = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.emptyRegistry())
                .resolve(IntentFixtures.intent(spend));
        assertThat(withoutDefault.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(withoutDefault.clause("c2").questionCodes()).contains(QuestionCode.MISSING_CURRENCY);
        assertThat(withoutDefault.clause("c2").rejected())
                .anyMatch(rejected -> rejected.reason() == RejectionReason.CURRENCY_MISSING);

        ResolutionResult withRegisteredDefault = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policyWithCurrencyDefault(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(spend));
        assertThat(withRegisteredDefault.clause("c2").accepted()).isEqualTo("order.net_paid_amount");
    }

    private void assertOnlyReason(IntentClause clause, RejectionReason expected) {
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(clause));
        ClauseResolution resolved = result.clause(clause.clauseId());
        assertThat(resolved.rejected())
                .as("rejection for %s", expected)
                .anyMatch(rejected -> rejected.reason() == expected);
        assertThat(resolved.accepted()).isNull();
    }
}
