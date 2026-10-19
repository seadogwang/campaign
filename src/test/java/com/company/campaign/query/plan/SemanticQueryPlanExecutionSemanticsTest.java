package com.company.campaign.query.plan;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.EntityVersionRef;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.WindowBoundarySemantics;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Findings 1-3: the plan carries every Metadata-owned storage fact, and the construction boundary re-proves
 * the exact Relation and resolved-window semantics even when a contradictory material is given a recomputed,
 * self-consistent plan hash.
 *
 * <p>Every adversarial case below satisfies the hash check by rebuilding the plan hash from the mutated
 * material, so a rejection can only come from the coherence re-proof.
 */
class SemanticQueryPlanExecutionSemanticsTest {

    private static final ComparisonOperator EQ = ComparisonOperator.EQUAL;
    private static final ComparisonOperator GT = ComparisonOperator.GREATER_THAN;

    /** A plan with an attribute, an EXISTS relation and a bounded metric over the same relation. */
    private static SemanticQueryPlan genuine() {
        return QueryPlanFixtures.plan(SegmentValidationFixtures.publish(
                SegmentValidationFixtures.all("n-root", List.of(
                        SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                        SegmentValidationFixtures.exists("n-e", "member.orders", SegmentValidationFixtures.lifetime(),
                                SegmentValidationFixtures.attribute("n-c", "order.channel", EQ,
                                        SegmentValidationFixtures.value(
                                                SegmentValidationFixtures.enumValue("ONLINE")))),
                        SegmentValidationFixtures.metric("n-spend", "order.net_paid_amount",
                                SegmentValidationFixtures.rolling(
                                        90, com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                                GT,
                                SegmentValidationFixtures.value(
                                        SegmentValidationFixtures.moneyValue("1000.00", "CNY")))))));
    }

    private static SemanticQueryPlan fixedWindowPlan() {
        return QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-m", "order.net_paid_amount",
                SegmentValidationFixtures.fixedWindow("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
                GT, SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));
    }

    private static SemanticQueryPlan lifetimeWindowPlan() {
        return QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-count", "order.count", SegmentValidationFixtures.lifetime(), GT,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))));
    }

    // ---------------------------------------------------------------- construction helpers

    private static SemanticQueryPlan construct(SemanticQueryPlan.Material material) {
        return new SemanticQueryPlan(
                material.planFormatVersion(),
                material.plannerVersion(),
                material.compilerVersion(),
                material.queryAstVersion(),
                material.dslSchemaVersion(),
                material.canonicalizerVersion(),
                material.subject(),
                material.predicateRoot(),
                material.canonicalAstHash(),
                material.metadataBinding(),
                material.dependencies(),
                material.audienceDependencies(),
                material.windowBindings(),
                material.currencyBindings(),
                material.dataScopePredicate(),
                material.trustedContext(),
                SemanticQueryPlanCodec.hashMaterial(material));
    }

    private static SemanticQueryPlan.Material material(
            SemanticQueryPlan plan,
            String queryAstVersion,
            List<ResolvedDependency> dependencies,
            List<ResolvedWindowBinding> windows) {
        SemanticQueryPlan.Material genuine = plan.material();
        return new SemanticQueryPlan.Material(
                genuine.planFormatVersion(),
                genuine.plannerVersion(),
                genuine.compilerVersion(),
                queryAstVersion == null ? genuine.queryAstVersion() : queryAstVersion,
                genuine.dslSchemaVersion(),
                genuine.canonicalizerVersion(),
                genuine.subject(),
                genuine.predicateRoot(),
                genuine.canonicalAstHash(),
                genuine.metadataBinding(),
                dependencies == null ? genuine.dependencies() : dependencies,
                genuine.audienceDependencies(),
                windows == null ? genuine.windowBindings() : windows,
                genuine.currencyBindings(),
                genuine.dataScopePredicate(),
                genuine.trustedContext());
    }

    private static void assertInconsistent(Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_PLAN_INCONSISTENT);
    }

    private static void assertRejected(
            SemanticQueryPlan plan, List<ResolvedDependency> dependencies, List<ResolvedWindowBinding> windows) {
        assertInconsistent(() -> construct(material(plan, null, dependencies, windows)));
    }

    private static ResolvedDependency.RelationDependency relation(SemanticQueryPlan plan) {
        return plan.dependencies().stream()
                .filter(ResolvedDependency.RelationDependency.class::isInstance)
                .map(ResolvedDependency.RelationDependency.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static ResolvedDependency.MetricDependency metric(SemanticQueryPlan plan) {
        return plan.dependencies().stream()
                .filter(ResolvedDependency.MetricDependency.class::isInstance)
                .map(ResolvedDependency.MetricDependency.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static ResolvedWindowBinding.MetricWindow metricWindow(SemanticQueryPlan plan) {
        return plan.windowBindings().stream()
                .filter(ResolvedWindowBinding.MetricWindow.class::isInstance)
                .map(ResolvedWindowBinding.MetricWindow.class::cast)
                .findFirst()
                .orElseThrow();
    }

    /** Replaces the one RELATION dependency, preserving deterministic order. */
    private static List<ResolvedDependency> swapRelation(
            SemanticQueryPlan plan, ResolvedDependency.RelationDependency replacement) {
        List<ResolvedDependency> copy = new ArrayList<>(plan.dependencies().size());
        for (ResolvedDependency dependency : plan.dependencies()) {
            copy.add(dependency instanceof ResolvedDependency.RelationDependency ? replacement : dependency);
        }
        return copy;
    }

    /** Replaces the one METRIC dependency (and its embedded relation) with a supplied mutant. */
    private static List<ResolvedDependency> swapMetric(
            SemanticQueryPlan plan, ResolvedDependency.MetricDependency replacement) {
        List<ResolvedDependency> copy = new ArrayList<>(plan.dependencies().size());
        for (ResolvedDependency dependency : plan.dependencies()) {
            copy.add(dependency instanceof ResolvedDependency.MetricDependency ? replacement : dependency);
        }
        return copy;
    }

    /** Replaces the one Metric window binding, preserving order. */
    private static List<ResolvedWindowBinding> swapMetricWindow(
            SemanticQueryPlan plan, ResolvedWindowBinding.MetricWindow replacement) {
        List<ResolvedWindowBinding> copy = new ArrayList<>(plan.windowBindings().size());
        for (ResolvedWindowBinding binding : plan.windowBindings()) {
            copy.add(binding instanceof ResolvedWindowBinding.MetricWindow ? replacement : binding);
        }
        return copy;
    }

    private static CanonicalRelationReference withJoinField(
            CanonicalRelationReference reference, String sourceJoinField) {
        return new CanonicalRelationReference(
                reference.id(), reference.sourceEntity(), reference.targetEntity(), reference.cardinality(),
                reference.joinKind(), sourceJoinField, reference.targetJoinField(),
                reference.targetNullSemantics(), reference.emptyRelationSemantics(),
                reference.eventTimeSemantics(), reference.eventTimeField());
    }

    // ---------------------------------------------------------------- Finding 1: carried facts

    @Test
    void everyDependencyCarriesItsExactEntityVersionsAndEventTime() {
        SemanticQueryPlan plan = genuine();

        ResolvedDependency.AttributeDependency attribute = plan.dependencies().stream()
                .filter(ResolvedDependency.AttributeDependency.class::isInstance)
                .map(ResolvedDependency.AttributeDependency.class::cast)
                .filter(dependency -> dependency.id().equals("member.tier"))
                .findFirst()
                .orElseThrow();
        assertThat(attribute.entityVersion()).isEqualTo(MetadataFixtures.memberVersion());

        ResolvedDependency.MetricDependency metric = metric(plan);
        assertThat(metric.entityVersion()).isEqualTo(MetadataFixtures.memberVersion());
        assertThat(metric.eventTimeField()).isEqualTo("order.paid_at");
        assertThat(metric.eventTimeSemantics()).isEqualTo(EventTimeSemantics.EVENT_TIME);

        ResolvedDependency.RelationDependency relation = relation(plan);
        assertThat(relation.sourceVersion()).isEqualTo(MetadataFixtures.memberVersion());
        assertThat(relation.targetVersion()).isEqualTo(MetadataFixtures.orderVersion());
        // The metric embeds the same complete relation projection as the dependency list.
        assertThat(metric.relation()).isEqualTo(relation);
        // A bounded metric window carries the same event-time fact as the dependency.
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        assertThat(window.eventTimeField()).isEqualTo("order.paid_at");
        assertThat(window.eventTimeSemantics()).isEqualTo(EventTimeSemantics.EVENT_TIME);
    }

    @Test
    void aLifetimeOnlyMetricRepresentsItsAbsentEventTimeDeliberately() {
        SemanticQueryPlan plan = lifetimeWindowPlan();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        assertThat(window.eventTimeSemantics()).isEqualTo(EventTimeSemantics.NOT_APPLICABLE);
        assertThat(window.eventTimeField()).isNull();

        ResolvedDependency.MetricDependency metric = metric(plan);
        assertThat(metric.eventTimeSemantics()).isNull();
        assertThat(metric.eventTimeField()).isNull();

        // The deliberate representation is present in the canonical bytes, not omitted.
        String wire = new SemanticQueryPlanCodec().writeString(plan);
        assertThat(wire)
                .contains("\"eventTimeSemantics\":\"NOT_APPLICABLE\"")
                .contains("\"eventTimeField\":null");
    }

    // ---------------------------------------------------------------- Finding 1: hash coverage

    private static void assertFieldChangesBytesAndHash(
            SemanticQueryPlan plan, List<ResolvedDependency> dependencies, List<ResolvedWindowBinding> windows) {
        SemanticQueryPlan.Material genuine = plan.material();
        SemanticQueryPlan.Material mutated = material(plan, null, dependencies, windows);
        assertThat(SemanticQueryPlanCodec.hashMaterial(mutated))
                .as("plan hash must cover the mutated field")
                .isNotEqualTo(SemanticQueryPlanCodec.hashMaterial(genuine));
        assertThat(SemanticQueryPlanCodec.writeMaterial(mutated))
                .as("plan bytes must cover the mutated field")
                .isNotEqualTo(SemanticQueryPlanCodec.writeMaterial(genuine));
    }

    private static EntityVersionRef otherVersion(SubjectKind entity) {
        return new EntityVersionRef(entity, new SchemaVersion(2, 0));
    }

    private static ResolvedDependency.MetricDependency withMetric(
            ResolvedDependency.MetricDependency metric,
            EntityVersionRef entityVersion,
            ResolvedDependency.RelationDependency relation,
            String eventTimeField,
            EventTimeSemantics eventTimeSemantics) {
        return new ResolvedDependency.MetricDependency(
                metric.definition(), entityVersion, metric.relationId(), relation,
                eventTimeField, eventTimeSemantics, metric.filter(), metric.refundTreatment());
    }

    @Test
    void anAttributeEntityVersionChangeAltersTheBytesAndHash() {
        SemanticQueryPlan plan = genuine();
        List<ResolvedDependency> mutated = plan.dependencies().stream()
                .map(dependency -> dependency instanceof ResolvedDependency.AttributeDependency attribute
                        && attribute.id().equals("member.tier")
                                ? (ResolvedDependency) new ResolvedDependency.AttributeDependency(
                                        attribute.definition(), otherVersion(SubjectKind.MEMBER))
                                : dependency)
                .toList();
        assertFieldChangesBytesAndHash(plan, mutated, null);
    }

    @Test
    void aMetricEntityVersionChangeAltersTheBytesAndHash() {
        SemanticQueryPlan plan = genuine();
        ResolvedDependency.MetricDependency metric = metric(plan);
        List<ResolvedDependency> mutated = swapMetric(plan, withMetric(
                metric, otherVersion(SubjectKind.MEMBER), metric.relation(),
                metric.eventTimeField(), metric.eventTimeSemantics()));
        assertFieldChangesBytesAndHash(plan, mutated, null);
    }

    @Test
    void aMetricEventTimeFieldChangeAltersTheBytesAndHash() {
        SemanticQueryPlan plan = genuine();
        ResolvedDependency.MetricDependency metric = metric(plan);
        List<ResolvedDependency> mutated = swapMetric(plan, withMetric(
                metric, metric.entityVersion(), metric.relation(),
                "order.created_at", metric.eventTimeSemantics()));
        assertFieldChangesBytesAndHash(plan, mutated, null);
    }

    @Test
    void aMetricWindowEventTimeSemanticsChangeAltersTheBytesAndHash() {
        SemanticQueryPlan plan = genuine();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        List<ResolvedWindowBinding> mutated = swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(), window.resolvedWindow(), window.includeStart(),
                window.includeEnd(), window.boundarySemantics(),
                EventTimeSemantics.INGESTION_TIME, window.eventTimeField()));
        assertFieldChangesBytesAndHash(plan, null, mutated);
    }

    @Test
    void aRelationSourceOrTargetVersionChangeAltersTheBytesAndHash() {
        SemanticQueryPlan plan = genuine();
        ResolvedDependency.RelationDependency relation = relation(plan);

        List<ResolvedDependency> changedSource = swapRelation(plan, new ResolvedDependency.RelationDependency(
                relation.reference(), otherVersion(SubjectKind.MEMBER), relation.targetVersion()));
        assertFieldChangesBytesAndHash(plan, changedSource, null);

        List<ResolvedDependency> changedTarget = swapRelation(plan, new ResolvedDependency.RelationDependency(
                relation.reference(), relation.sourceVersion(), otherVersion(SubjectKind.ORDER)));
        assertFieldChangesBytesAndHash(plan, changedTarget, null);
    }

    // ---------------------------------------------------------------- Finding 2: adversarial construction

    @Test
    void aForgedRelationJoinFieldIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedDependency.RelationDependency relation = relation(plan);
        ResolvedDependency.RelationDependency forged = new ResolvedDependency.RelationDependency(
                withJoinField(relation.reference(), "member.other_key"),
                relation.sourceVersion(), relation.targetVersion());
        assertRejected(plan, swapRelation(plan, forged), null);
    }

    @Test
    void aForgedRelationSourceOrTargetVersionIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedDependency.RelationDependency relation = relation(plan);

        assertRejected(plan, swapRelation(plan, new ResolvedDependency.RelationDependency(
                relation.reference(), otherVersion(SubjectKind.MEMBER), relation.targetVersion())), null);
        assertRejected(plan, swapRelation(plan, new ResolvedDependency.RelationDependency(
                relation.reference(), relation.sourceVersion(), otherVersion(SubjectKind.ORDER))), null);
    }

    @Test
    void aForgedMetricEmbeddedRelationProjectionIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedDependency.MetricDependency metric = metric(plan);
        ResolvedDependency.RelationDependency forged = new ResolvedDependency.RelationDependency(
                withJoinField(metric.relation().reference(), "member.other_key"),
                metric.relation().sourceVersion(), metric.relation().targetVersion());
        assertRejected(plan, swapMetric(plan, withMetric(
                metric, metric.entityVersion(), forged, metric.eventTimeField(), metric.eventTimeSemantics())), null);
    }

    @Test
    void aForgedMetricEventTimeFieldOrSemanticsInAWindowBindingIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);

        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(), window.resolvedWindow(), window.includeStart(),
                window.includeEnd(), window.boundarySemantics(), window.eventTimeSemantics(), "order.created_at")));
        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(), window.resolvedWindow(), window.includeStart(),
                window.includeEnd(), window.boundarySemantics(),
                EventTimeSemantics.INGESTION_TIME, window.eventTimeField())));
    }

    @Test
    void aForgedRollingResolvedInstantIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        ResolvedWindow.Rolling rolling = (ResolvedWindow.Rolling) window.resolvedWindow();

        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(),
                new ResolvedWindow.Rolling(rolling.start().plusSeconds(60), rolling.end()),
                window.includeStart(), window.includeEnd(), window.boundarySemantics(),
                window.eventTimeSemantics(), window.eventTimeField())));
        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(),
                new ResolvedWindow.Rolling(rolling.start(), rolling.end().plusSeconds(60)),
                window.includeStart(), window.includeEnd(), window.boundarySemantics(),
                window.eventTimeSemantics(), window.eventTimeField())));
    }

    @Test
    void aForgedFixedResolvedInstantIsRejected() {
        SemanticQueryPlan plan = fixedWindowPlan();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        ResolvedWindow.Fixed fixed = (ResolvedWindow.Fixed) window.resolvedWindow();
        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(),
                new ResolvedWindow.Fixed(fixed.start().plusSeconds(1), fixed.end().plusSeconds(1)),
                window.includeStart(), window.includeEnd(), window.boundarySemantics(),
                window.eventTimeSemantics(), window.eventTimeField())));
    }

    @Test
    void aForgedLifetimeAsOfOrWatermarkIsRejected() {
        SemanticQueryPlan plan = lifetimeWindowPlan();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        ResolvedWindow.Lifetime lifetime = (ResolvedWindow.Lifetime) window.resolvedWindow();

        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(),
                new ResolvedWindow.Lifetime(lifetime.evaluationAsOf().plusSeconds(1), lifetime.dataWatermark()),
                false, false, window.boundarySemantics(),
                window.eventTimeSemantics(), window.eventTimeField())));
        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(),
                new ResolvedWindow.Lifetime(lifetime.evaluationAsOf(), lifetime.dataWatermark().minusSeconds(60)),
                false, false, window.boundarySemantics(),
                window.eventTimeSemantics(), window.eventTimeField())));
    }

    @Test
    void forgedWindowInclusivityIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        assertThat(window.boundarySemantics())
                .isEqualTo(WindowBoundarySemantics.INCLUSIVE_START_EXCLUSIVE_END);

        // Internally consistent (INCLUSIVE_BOTH with includeEnd) yet wrong for this metric's pinned semantics.
        assertRejected(plan, null, swapMetricWindow(plan, new ResolvedWindowBinding.MetricWindow(
                window.path(), window.canonicalWindow(), window.resolvedWindow(), true, true,
                WindowBoundarySemantics.INCLUSIVE_BOTH,
                window.eventTimeSemantics(), window.eventTimeField())));
    }

    // ---------------------------------------------------------------- Finding 3: ordering and versions

    @Test
    void reversingTwoValidWindowBindingsIsRejectedEvenWithARecomputedHash() {
        SemanticQueryPlan plan = genuine();
        assertThat(plan.windowBindings()).hasSize(2);

        List<ResolvedWindowBinding> reversed = new ArrayList<>(plan.windowBindings());
        Collections.reverse(reversed);
        SemanticQueryPlan.Material contradictory = material(plan, null, null, reversed);
        ContentHash recomputed = SemanticQueryPlanCodec.hashMaterial(contradictory);
        assertThat(recomputed).isNotEqualTo(plan.planHash());
        assertInconsistent(() -> construct(contradictory));
    }

    @Test
    void anUnsupportedQueryAstVersionIsRejected() {
        SemanticQueryPlan plan = genuine();
        assertInconsistent(() -> construct(material(plan, "segment-query-ast/9.9", null, null)));
    }

    @Test
    void theQueryAstVersionIsInTheReadBoundaryBytesAndHashMaterial() {
        SemanticQueryPlan plan = genuine();
        assertThat(plan.queryAstVersion()).isEqualTo(PlanVersions.QUERY_AST_VERSION);
        assertThat(new SemanticQueryPlanCodec().writeString(plan))
                .contains("\"queryAstVersion\":\"" + PlanVersions.QUERY_AST_VERSION + "\"");

        SemanticQueryPlan.Material mutated = material(plan, "segment-query-ast/2.0", null, null);
        assertThat(SemanticQueryPlanCodec.hashMaterial(mutated))
                .isNotEqualTo(SemanticQueryPlanCodec.hashMaterial(plan.material()));
        assertThat(SemanticQueryPlanCodec.writeMaterial(mutated))
                .isNotEqualTo(SemanticQueryPlanCodec.writeMaterial(plan.material()));
    }

    @Test
    void anUnrelatedRelationDependencyIsRejected() {
        SemanticQueryPlan plan = genuine();
        List<ResolvedDependency> extra = new ArrayList<>(plan.dependencies());
        extra.add(new ResolvedDependency.RelationDependency(
                new CanonicalRelationReference("order.lines", SubjectKind.ORDER, SubjectKind.ORDER,
                        com.company.campaign.metadata.domain.RelationCardinality.ONE_TO_MANY,
                        com.company.campaign.metadata.domain.RelationJoinKind.CORRELATED_BY_SOURCE_KEY,
                        "source.id", "target.parent_id",
                        com.company.campaign.metadata.domain.NullSemantics.EXCLUDED,
                        com.company.campaign.metadata.domain.EmptySetBehavior.NULL,
                        EventTimeSemantics.EVENT_TIME, "order.line_at"),
                MetadataFixtures.orderVersion(), MetadataFixtures.orderVersion()));
        extra.sort(ResolvedDependency.ORDER);
        assertRejected(plan, extra, null);
    }

    @Test
    void theGenuinePlanStillConstructsWithItsRecomputedHash() {
        SemanticQueryPlan plan = genuine();
        SemanticQueryPlan rebuilt = construct(plan.material());
        assertThat(rebuilt.planHash()).isEqualTo(plan.planHash());
        assertThat(rebuilt.queryAstVersion()).isEqualTo(plan.queryAstVersion());
    }
}