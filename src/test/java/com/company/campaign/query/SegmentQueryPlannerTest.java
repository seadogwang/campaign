package com.company.campaign.query;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.query.plan.CurrencyBinding;
import com.company.campaign.query.plan.DataScopePredicate;
import com.company.campaign.query.plan.ResolvedDependency;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.SemanticQueryPlanCodec;
import com.company.campaign.query.plan.TrustedPlanContext;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 1, 2, 14 and 15: a genuine T07 result plus its exact Snapshot and context yields one immutable,
 * fully pinned, deterministically ordered and repeatedly reproducible plan that downstream code can read.
 */
class SegmentQueryPlannerTest {

    private static final SemanticQueryPlanCodec CODEC = new SemanticQueryPlanCodec();
    private static final CanonicalSegmentCodec CANONICAL = new CanonicalSegmentCodec();

    @Test
    void aGenuineResultYieldsAnImmutableFullyPinnedPlan() {
        SegmentValidationResult result =
                SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        SemanticQueryPlan plan = QueryPlanFixtures.planner().plan(
                result, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context());

        assertThat(plan.planFormatVersion()).isEqualTo("1.1");
        assertThat(plan.plannerVersion()).isEqualTo("segment-query-planner/1.1");
        assertThat(plan.compilerVersion()).isEqualTo("segment-query-compiler/1.1");
        assertThat(plan.queryAstVersion()).isEqualTo("segment-query-ast/1.1");
        assertThat(plan.dslSchemaVersion()).isEqualTo(SegmentValidationFixtures.dslVersion());
        assertThat(plan.canonicalizerVersion()).isEqualTo(result.canonicalizerVersion());
        assertThat(plan.subject()).isEqualTo(SubjectKind.MEMBER);
        assertThat(plan.canonicalAstHash()).isEqualTo(result.canonicalAstHash());
        assertThat(plan.metadataBinding()).isEqualTo(result.metadataBinding());
        assertThat(plan.metadataSnapshotId()).isEqualTo(QueryPlanFixtures.SNAPSHOT_ID);
        assertThat(plan.metadataCatalogVersion()).isEqualTo(QueryPlanFixtures.SNAPSHOT_VERSION);
        assertThat(plan.metadataContentHash()).isEqualTo(SegmentValidationFixtures.snapshot().contentHash());
        assertThat(plan.planHash()).isNotNull();

        assertThat(CODEC.hash(plan)).isEqualTo(plan.planHash());
        assertThat(CODEC.writeString(plan))
                .isEqualTo(new String(CODEC.write(plan), StandardCharsets.UTF_8));
        assertThat(plan.canonicalAstHash())
                .isEqualTo(CANONICAL.hash(result.canonicalSegment()));

        TrustedPlanContext context = plan.trustedContext();
        assertThat(context.tenantId()).isEqualTo("tenant-001");
        assertThat(context.programId()).isEqualTo("program-001");
        assertThat(context.membershipId()).isEqualTo("membership-1");
        assertThat(context.userId()).isEqualTo("user-001");
        assertThat(context.dataScopeId()).isEqualTo("scope-008");
        assertThat(context.asOf()).isEqualTo(QueryPlanFixtures.AS_OF);
        assertThat(context.dataWatermark()).isEqualTo(QueryPlanFixtures.WATERMARK);
        assertThat(context.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(context.reportingCurrency()).isEqualTo("CNY");
        assertThat(plan.dataScopePredicate().kind())
                .isEqualTo(DataScopePredicate.Requirement.SUBJECT_IN_DATASCOPE);
        assertThat(plan.dataScopePredicate().subject()).isEqualTo(SubjectKind.MEMBER);
        assertThat(plan.dataScopePredicate().dataScopeId()).isEqualTo("scope-008");
    }

    @Test
    void aDownstreamCallerCanReadEveryRequiredFieldAndCollectionsAreImmutable() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());

        assertThat(plan.predicateRoot()).isNotNull();
        assertThat(plan.dependencies()).isNotEmpty();
        assertThat(plan.windowBindings()).isNotEmpty();
        assertThat(plan.currencyBindings()).isNotEmpty();
        assertThat(plan.dataScopePredicate()).isNotNull();
        assertThat(plan.trustedContext()).isNotNull();

        assertThatThrownBy(() -> plan.dependencies().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.windowBindings().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolvedDependenciesAreCompleteImmutableDuplicateFreeAndDeterministicallyOrdered() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());

        List<ResolvedDependency> dependencies = plan.dependencies();
        assertThat(dependencies.stream().map(ResolvedDependency::kind).toList())
                .containsExactly(MetadataKind.ATTRIBUTE, MetadataKind.METRIC, MetadataKind.RELATION);
        assertThat(dependencies.stream().filter(d -> d.kind() == MetadataKind.ATTRIBUTE).toList())
                .singleElement()
                .satisfies(d -> assertThat(d.id()).isEqualTo("member.tier"));
        assertThat(dependencies.stream().filter(d -> d.kind() == MetadataKind.METRIC).toList())
                .singleElement()
                .isInstanceOfSatisfying(ResolvedDependency.MetricDependency.class, metric -> {
                    assertThat(metric.id()).isEqualTo("order.net_paid_amount");
                    assertThat(metric.relationId()).isEqualTo("member.orders");
                    assertThat(metric.definition().aggregation()).isEqualTo(AggregateFunction.SUM);
                    assertThat(metric.definition().emptySetBehavior()).isEqualTo(EmptySetBehavior.ZERO);
                });
        assertThat(dependencies.stream().filter(d -> d.kind() == MetadataKind.RELATION).toList())
                .singleElement()
                .isInstanceOfSatisfying(ResolvedDependency.RelationDependency.class,
                        relation -> assertThat(relation.id()).isEqualTo("member.orders"));
    }

    @Test
    void metricResolutionUsesTheExactPinnedSnapshotFilterAndRefundSemantics() {
        var snapshot = QueryPlanFixtures.richSnapshot();
        var document = QueryPlanFixtures.document(snapshot, SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.metric("n-spend", "order.net_paid_amount",
                        SegmentValidationFixtures.rolling(90, com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                        com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1000.00", "CNY"))))));

        SemanticQueryPlan plan = QueryPlanFixtures.plan(
                document, snapshot, SegmentValidationFixtures.context());

        ResolvedDependency.MetricDependency metric = plan.dependencies().stream()
                .filter(ResolvedDependency.MetricDependency.class::isInstance)
                .map(ResolvedDependency.MetricDependency.class::cast)
                .findFirst()
                .orElseThrow();
        MetricDefinition pinned = com.company.campaign.metadata.testsupport.MetadataFixtures.netPaidAmountMetric();
        assertThat(metric.relationId()).isEqualTo(pinned.relationId());
        assertThat(metric.filter()).isEqualTo(pinned.filter());
        assertThat(metric.refundTreatment()).isEqualTo(pinned.refundTreatment());
        assertThat(metric.refundTreatment())
                .isEqualTo(com.company.campaign.metadata.domain.RefundTreatment.SUBTRACT);
        assertThat(metric.filter().entries()).hasSize(2);
        assertThat(metric.definition().aggregation()).isEqualTo(AggregateFunction.SUM);
        assertThat(metric.definition().currencySemantics())
                .isEqualTo(CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY);
    }

    @Test
    void compilingIdenticalExplicitInputsOneHundredTimesIsByteIdentical() {
        SegmentValidationResult result =
                SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        SemanticQueryPlan first = QueryPlanFixtures.planner().plan(
                result, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context());
        byte[] expected = CODEC.write(first);
        String expectedAst = new com.company.campaign.query.ast.QueryAstCodec().writeString(first.predicateRoot());

        for (int index = 0; index < 100; index++) {
            SemanticQueryPlan again = QueryPlanFixtures.planner().plan(
                    result, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context());
            assertThat(CODEC.write(again)).isEqualTo(expected);
            assertThat(again.planHash()).isEqualTo(first.planHash());
            assertThat(new com.company.campaign.query.ast.QueryAstCodec().writeString(again.predicateRoot()))
                    .isEqualTo(expectedAst);
        }
    }

    @Test
    void currencyBindingsArePerDefinitionAndConvertWhenAMonetaryMetricIsReferenced() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());

        assertThat(plan.currencyBindings()).extracting(CurrencyBinding::definitionId)
                .containsExactly("member.tier", "order.net_paid_amount");

        CurrencyBinding tier = plan.currencyBindings().get(0);
        assertThat(tier.kind()).isEqualTo(MetadataKind.ATTRIBUTE);
        assertThat(tier.action()).isEqualTo(CurrencyBinding.Action.NO_CONVERSION);
        assertThat(tier.targetCurrency()).isNull();
        assertThat(tier.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.NOT_APPLICABLE);

        CurrencyBinding metric = plan.currencyBindings().get(1);
        assertThat(metric.kind()).isEqualTo(MetadataKind.METRIC);
        assertThat(metric.action()).isEqualTo(CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY);
        assertThat(metric.targetCurrency()).isEqualTo("CNY");
        assertThat(metric.rateResolution()).isEqualTo(CurrencyBinding.RateResolution.DEFERRED);
    }
}