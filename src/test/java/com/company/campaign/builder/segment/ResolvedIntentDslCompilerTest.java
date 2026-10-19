package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic resolved-Intent to DSL compiler: exact trees, stable node ids, preserved grouping and
 * negation, exact value conversion, and every re-proof failure.
 */
class ResolvedIntentDslCompilerTest {

    private static final String TIER_UTTERANCE = "Gold会员";
    private static final String MONEY_UTTERANCE = "过去90天消费超过1000元";

    @Test
    void anEnumAttributeClauseCompilesToTheExactTreeAndStableNodeId() {
        SegmentIntent intent = SliceTestFixtures.intent(TIER_UTTERANCE, SliceTestFixtures.enumTierJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new AttributePredicate(
                "n-c-c1", "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD")));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
        assertThat(result.document().editableDslHash()).isEqualTo(expectedDocument(expected).editableDslHash());
        assertThat(resolution.clause("c1").accepted()).isEqualTo("member.tier");
    }

    @Test
    void aMoneyMetricClauseUsesTheExplicitCurrency() {
        SegmentIntent intent = SliceTestFixtures.intent(MONEY_UTTERANCE, SliceTestFixtures.moneySpendJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new MetricPredicate(
                "n-c-c1", "order.net_paid_amount", new RollingWindow(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new MoneyValue("1000", "CNY")));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void aMoneyMetricClauseUsesTheExactRegisteredReportingCurrencyDefault() {
        String json = SliceTestFixtures.registeredCurrencySpendJson();
        SegmentIntent intent = SliceTestFixtures.intent(MONEY_UTTERANCE, json);
        ResolutionResult resolution = resolve(
                intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policyWithCurrencyDefault());

        DslCompilationResult result = compile(
                intent, resolution, SliceTestFixtures.snapshot(), SliceTestFixtures.policyWithCurrencyDefault());

        Predicate expected = new MetricPredicate(
                "n-c-c1", "order.net_paid_amount", new RollingWindow(90, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new MoneyValue("1000", "CNY")));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void anIntegerMetricClauseKeepsItsExplicitLifetimeWindow() {
        SegmentIntent intent = SliceTestFixtures.intent("订单数超过3", SliceTestFixtures.integerCountJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new MetricPredicate(
                "n-c-c1", "order.count", new LifetimeWindow(), ComparisonOperator.GREATER_THAN,
                ConditionValue.of(new IntegerValue(3L)));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void declaredAllAnyNotGroupingIsPreservedExactlyWithoutFlattening() {
        String utterance = SliceTestFixtures.NESTED_UTTERANCE;
        SegmentIntent intent = SliceTestFixtures.intent(utterance, SliceTestFixtures.nestedGroupingJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new GroupPredicate("n-g-r", PredicateType.ALL, List.of(
                new AttributePredicate(
                        "n-c-c1", "member.tier", ComparisonOperator.EQUAL,
                        ConditionValue.of(new EnumValue("GOLD"))),
                new GroupPredicate("n-g-r.1", PredicateType.ANY, List.of(
                        new MetricPredicate(
                                "n-c-c2", "order.count", new LifetimeWindow(), ComparisonOperator.GREATER_THAN,
                                ConditionValue.of(new IntegerValue(3L))),
                        new NotPredicate("n-not-r.1.1", new AttributePredicate(
                                "n-c-c3", "member.code", ComparisonOperator.EQUAL,
                                ConditionValue.of(new StringValue("ABC"))))))));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void clauseLevelNegationBecomesOneNotNodeAndIsNeverFolded() {
        SegmentIntent intent = SliceTestFixtures.intent(TIER_UTTERANCE, SliceTestFixtures.negatedTierJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new NotPredicate("n-neg-c1", new AttributePredicate(
                "n-c-c1", "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD"))));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void expressionLevelAndClauseLevelNegationBothSurvive() {
        SegmentIntent intent = SliceTestFixtures.intent(TIER_UTTERANCE, SliceTestFixtures.doubleNegationJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new NotPredicate("n-not-r", new NotPredicate("n-neg-c1", new AttributePredicate(
                "n-c-c1", "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(new EnumValue("GOLD")))));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void aListClausePreservesItsScalarItems() {
        SegmentIntent intent = SliceTestFixtures.intent(TIER_UTTERANCE, SliceTestFixtures.tierListJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new AttributePredicate(
                "n-c-c1", "member.tier", ComparisonOperator.IN,
                ConditionValue.of(new ListValue(List.of(new EnumValue("GOLD"), new EnumValue("SILVER")))));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void aRangeClausePreservesItsBoundsAndInclusivity() {
        SegmentIntent intent = SliceTestFixtures.intent("订单数介于1和9", SliceTestFixtures.countRangeJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        Predicate expected = new MetricPredicate(
                "n-c-c1", "order.count", new LifetimeWindow(), ComparisonOperator.BETWEEN,
                ConditionValue.of(new RangeValue(new IntegerValue(1L), new IntegerValue(9L), true, false)));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void aNullOperatorProducesNoValueCondition() {
        SegmentIntent intent = SliceTestFixtures.intent(
                SliceTestFixtures.NULL_UTTERANCE, SliceTestFixtures.nullSemanticsJson());
        ResolutionResult resolution = resolve(
                intent, SliceTestFixtures.codeOnlySnapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(
                intent, resolution, SliceTestFixtures.codeOnlySnapshot(), SliceTestFixtures.policy());

        Predicate expected = new GroupPredicate("n-g-r", PredicateType.ALL, List.of(
                new AttributePredicate("n-c-c1", "member.code", ComparisonOperator.IS_NULL, ConditionValue.none()),
                new AttributePredicate(
                        "n-c-c2", "member.code", ComparisonOperator.NOT_EQUAL,
                        ConditionValue.of(new StringValue("ABC")))));
        assertThat(result.isResolved()).isTrue();
        assertThat(result.document().predicate()).isEqualTo(expected);
    }

    @Test
    void aRelationClauseIsRefusedRatherThanFlattened() {
        SegmentIntent intent = SliceTestFixtures.intent("订单", SliceTestFixtures.relationJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());
        assertThat(resolution.isResolved()).isTrue();

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        assertThat(result.isResolved()).isFalse();
        assertThat(result.disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(result.failureCode())
                .isEqualTo(SegmentDslCompilationErrorCodes.RELATION_NESTING_NOT_REPRESENTABLE);
        assertThat(result.failureClauseId()).isEqualTo("c1");
        assertThat(result.document()).isNull();
    }

    @Test
    void anEnumLabelMatchingNoCodeFailsClosedDuringResolution() {
        SegmentIntent intent = SliceTestFixtures.intent("Bronze会员", SliceTestFixtures.unknownEnumJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());
        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.disposition()).isEqualTo(Disposition.UNSUPPORTED);
        assertThat(resolution.clarification()).isNull();
    }

    @Test
    void anEnumLabelMatchingTwoCodesFailsClosed() {
        SegmentIntent intent = SliceTestFixtures.intent("GOLD会员", SliceTestFixtures.enumGoldCodeJson());
        MetadataSnapshot snapshot = SliceTestFixtures.ambiguousEnumSnapshot();
        ResolutionResult resolution = resolve(intent, snapshot, SliceTestFixtures.policy());
        assertThat(resolution.isResolved()).isTrue();

        DslCompilationResult result = compile(intent, resolution, snapshot, SliceTestFixtures.policy());

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.ENUM_AMBIGUOUS);
    }

    @Test
    void aStaleResolutionPolicyFailsClosed() {
        SegmentIntent intent = SliceTestFixtures.intent(TIER_UTTERANCE, SliceTestFixtures.enumTierJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.otherPolicy());

        assertThat(result.isResolved()).isFalse();
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.RESOLUTION_STALE);
    }

    @Test
    void aStaleMetadataSnapshotFailsClosed() {
        SegmentIntent intent = SliceTestFixtures.intent(TIER_UTTERANCE, SliceTestFixtures.enumTierJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult result = compile(intent, resolution, SliceTestFixtures.codeOnlySnapshot(),
                SliceTestFixtures.policy());

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.RESOLUTION_STALE);
    }

    @Test
    void aNonResolvedIntentNeverCompiles() {
        SegmentIntent intent = SliceTestFixtures.intent(MONEY_UTTERANCE, SliceTestFixtures.ambiguousSpendJson());
        ResolutionResult resolution = resolve(
                intent, SliceTestFixtures.ambiguousSpendSnapshot(), SliceTestFixtures.policy());
        assertThat(resolution.disposition()).isEqualTo(Disposition.NEEDS_INPUT);

        DslCompilationResult result = compile(
                intent, resolution, SliceTestFixtures.ambiguousSpendSnapshot(), SliceTestFixtures.policy());

        assertThat(result.isResolved()).isFalse();
        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.RESOLUTION_NOT_RESOLVED);
    }

    @Test
    void aTamperedIntentHashIsRejectedBeforeAnyCompilation() {
        SegmentIntent intent = SliceTestFixtures.intent(TIER_UTTERANCE, SliceTestFixtures.enumTierJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());
        ResolutionResult tampered = new ResolutionResult(
                resolution.disposition(), ContentHash.sha256("tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                resolution.pins(), resolution.clauses(), resolution.clarification());

        DslCompilationResult result = compile(
                intent, tampered, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.INTENT_HASH_MISMATCH);
    }

    @Test
    void identicalInputsProduceByteIdenticalDocuments() {
        SegmentIntent intent = SliceTestFixtures.intent(SliceTestFixtures.NESTED_UTTERANCE,
                SliceTestFixtures.nestedGroupingJson());
        ResolutionResult resolution = resolve(intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());

        DslCompilationResult first = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());
        DslCompilationResult second = compile(intent, resolution, SliceTestFixtures.snapshot(),
                SliceTestFixtures.policy());

        assertThat(first.document().predicate()).isEqualTo(second.document().predicate());
        assertThat(first.document().editableDslHash()).isEqualTo(second.document().editableDslHash());
    }

    // ------------------------------------------------------------------ helpers

    private static ResolutionResult resolve(
            SegmentIntent intent, MetadataSnapshot snapshot, ResolutionPolicy policy) {
        return SliceTestFixtures.resolve(intent, snapshot, policy);
    }

    private static DslCompilationResult compile(
            SegmentIntent intent, ResolutionResult resolution, MetadataSnapshot snapshot, ResolutionPolicy policy) {
        return new ResolvedIntentDslCompiler(policy)
                .compile(intent, resolution, snapshot, SliceTestFixtures.registry());
    }

    private static SegmentDsl expectedDocument(Predicate predicate) {
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(SliceTestFixtures.snapshot()), predicate);
    }
}
