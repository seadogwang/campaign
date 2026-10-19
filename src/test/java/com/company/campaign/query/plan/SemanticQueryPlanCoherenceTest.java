package com.company.campaign.query.plan;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.ast.QueryAstCodec;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 3: the package-private plan construction boundary rejects internally contradictory material even
 * when the declared plan hash is a valid self-consistent hash of that material.
 *
 * <p>Every case below rebuilds a valid hash from a deliberately contradictory {@code Material}, so the only
 * reason a plan can fail is the coherence re-proof — not the hash check.
 */
class SemanticQueryPlanCoherenceTest {

    private static final ComparisonOperator EQ = ComparisonOperator.EQUAL;

    private static void assertInconsistent(Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_PLAN_INCONSISTENT);
    }

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
            SemanticQueryPlan genuine,
            QueryAst predicate,
            ContentHash canonicalHash,
            List<ResolvedDependency> dependencies,
            List<ResolvedWindowBinding> windows,
            List<CurrencyBinding> currency,
            DataScopePredicate dataScope,
            TrustedPlanContext context) {
        return new SemanticQueryPlan.Material(
                genuine.planFormatVersion(),
                genuine.plannerVersion(),
                genuine.compilerVersion(),
                genuine.queryAstVersion(),
                genuine.dslSchemaVersion(),
                genuine.canonicalizerVersion(),
                genuine.subject(),
                predicate == null ? genuine.predicateRoot() : predicate,
                canonicalHash == null ? genuine.canonicalAstHash() : canonicalHash,
                genuine.metadataBinding(),
                dependencies == null ? genuine.dependencies() : dependencies,
                genuine.audienceDependencies(),
                windows == null ? genuine.windowBindings() : windows,
                currency == null ? genuine.currencyBindings() : currency,
                dataScope == null ? genuine.dataScopePredicate() : dataScope,
                context == null ? genuine.trustedContext() : context);
    }

    private static List<ResolvedDependency> sorted(List<ResolvedDependency> dependencies) {
        List<ResolvedDependency> copy = new ArrayList<>(dependencies);
        copy.sort(ResolvedDependency.ORDER);
        return copy;
    }

    private static SemanticQueryPlan genuine() {
        return QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
    }

    private static ResolvedWindowBinding.MetricWindow metricWindow(SemanticQueryPlan plan) {
        return (ResolvedWindowBinding.MetricWindow) plan.windowBindings().stream()
                .filter(ResolvedWindowBinding.MetricWindow.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private static CurrencyBinding currencyBinding(SemanticQueryPlan plan, MetadataKind kind, String definitionId) {
        return plan.currencyBindings().stream()
                .filter(binding -> binding.kind() == kind && binding.definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow();
    }

    // ---------------------------------------------------------------- scope and canonical root

    @Test
    void aDataScopePredicateForAnotherTrustedScopeIsRejected() {
        SemanticQueryPlan plan = genuine();
        DataScopePredicate foreign = new DataScopePredicate(
                DataScopePredicate.Requirement.SUBJECT_IN_DATASCOPE, SubjectKind.MEMBER, "scope-999");
        assertInconsistent(() -> construct(material(plan, null, null, null, null, null, foreign, null)));
    }

    @Test
    void anAlteredAstWithTheGenuineCanonicalHashIsRejected() {
        SemanticQueryPlan plan = genuine();
        QueryAst altered = QueryPlanFixtures.plan(SegmentValidationFixtures.tierEquals("n-tier", "SILVER"))
                .predicateRoot();
        assertInconsistent(() -> construct(material(plan, altered, null, null, null, null, null, null)));
    }

    @Test
    void aForgedCanonicalHashWithTheGenuineAstIsRejected() {
        SemanticQueryPlan plan = genuine();
        ContentHash forged = ContentHash.sha256("forged".getBytes(StandardCharsets.UTF_8));
        assertInconsistent(() -> construct(material(plan, null, forged, null, null, null, null, null)));
    }

    // ---------------------------------------------------------------- dependencies

    @Test
    void aMissingDirectDependencyIsRejected() {
        SemanticQueryPlan plan = genuine();
        List<ResolvedDependency> withoutMetric = plan.dependencies().stream()
                .filter(dependency -> dependency.kind() != MetadataKind.METRIC)
                .toList();
        assertInconsistent(() -> construct(material(plan, null, null, withoutMetric, null, null, null, null)));
    }

    @Test
    void anExtraUnrelatedDependencyIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedDependency.AttributeDependency extra = (ResolvedDependency.AttributeDependency)
                QueryPlanFixtures.plan(SegmentValidationFixtures.attribute("n-name", "member.name", EQ,
                                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("alice"))))
                        .dependencies().stream()
                        .filter(dependency -> dependency instanceof ResolvedDependency.AttributeDependency)
                        .findFirst()
                        .orElseThrow();
        List<ResolvedDependency> withExtra = new ArrayList<>(plan.dependencies());
        withExtra.add(extra);
        assertInconsistent(() -> construct(material(plan, null, null, sorted(withExtra), null, null, null, null)));
    }

    @Test
    void aWrongKindDependencyForAMetricReferenceIsRejected() {
        SemanticQueryPlan plan = genuine();
        CanonicalDefinition metricAsAttribute = new CanonicalDefinition(
                MetadataKind.ATTRIBUTE, "order.net_paid_amount", SubjectKind.MEMBER, SemanticDataType.MONEY,
                NullSemantics.UNKNOWN_VALUE, CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY, null);
        List<ResolvedDependency> wrongKind = plan.dependencies().stream()
                .map(dependency -> dependency.kind() == MetadataKind.METRIC
                        ? (ResolvedDependency) new ResolvedDependency.AttributeDependency(
                                metricAsAttribute, com.company.campaign.metadata.testsupport.MetadataFixtures.memberVersion())
                        : dependency)
                .toList();
        assertInconsistent(() -> construct(material(plan, null, null, sorted(wrongKind), null, null, null, null)));
    }

    @Test
    void aMissingMetricsTransitiveRelationIsRejected() {
        SemanticQueryPlan plan = genuine();
        List<ResolvedDependency> withoutRelation = plan.dependencies().stream()
                .filter(dependency -> dependency.kind() != MetadataKind.RELATION)
                .toList();
        assertInconsistent(() -> construct(material(plan, null, null, withoutRelation, null, null, null, null)));
    }

    // ---------------------------------------------------------------- windows

    @Test
    void aMissingWindowBindingIsRejected() {
        SemanticQueryPlan plan = genuine();
        assertInconsistent(() -> construct(material(plan, null, null, null, List.of(), null, null, null)));
    }

    @Test
    void anExtraWindowBindingAtAnUnknownPathIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        List<ResolvedWindowBinding> extra = new ArrayList<>(plan.windowBindings());
        extra.add(new ResolvedWindowBinding.MetricWindow("r.9", window.canonicalWindow(),
                window.resolvedWindow(), window.includeStart(), window.includeEnd(), window.boundarySemantics(),
                window.eventTimeSemantics(), window.eventTimeField()));
        assertInconsistent(() -> construct(material(plan, null, null, null, extra, null, null, null)));
    }

    @Test
    void aWindowBindingAtTheWrongPathIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        List<ResolvedWindowBinding> moved = List.of(new ResolvedWindowBinding.MetricWindow("r.not",
                window.canonicalWindow(), window.resolvedWindow(), window.includeStart(), window.includeEnd(),
                window.boundarySemantics(), window.eventTimeSemantics(), window.eventTimeField()));
        assertInconsistent(() -> construct(material(plan, null, null, null, moved, null, null, null)));
    }

    @Test
    void aWindowBindingOfTheWrongKindIsRejected() {
        SemanticQueryPlan plan = genuine();
        ResolvedWindowBinding.MetricWindow window = metricWindow(plan);
        List<ResolvedWindowBinding> wrongKind = List.of(new ResolvedWindowBinding.RelationWindow(
                window.path(), window.canonicalWindow(), window.resolvedWindow(), true, false,
                EventTimeSemantics.EVENT_TIME, "order.paid_at"));
        assertInconsistent(() -> construct(material(plan, null, null, null, wrongKind, null, null, null)));
    }

    // ---------------------------------------------------------------- currency bindings

    @Test
    void aMissingCurrencyBindingIsRejected() {
        SemanticQueryPlan plan = genuine();
        List<CurrencyBinding> withoutTier = plan.currencyBindings().stream()
                .filter(binding -> !binding.definitionId().equals("member.tier"))
                .toList();
        assertInconsistent(() -> construct(material(plan, null, null, null, null, withoutTier, null, null)));
    }

    @Test
    void anExtraUnrelatedCurrencyBindingIsRejected() {
        SemanticQueryPlan plan = genuine();
        List<CurrencyBinding> withExtra = new ArrayList<>(plan.currencyBindings());
        withExtra.add(new CurrencyBinding(MetadataKind.ATTRIBUTE, "member.name",
                CurrencyBinding.Action.NO_CONVERSION, null, CurrencyBinding.RateResolution.NOT_APPLICABLE));
        withExtra.sort(CurrencyBinding.ORDER);
        assertInconsistent(() -> construct(material(plan, null, null, null, null, withExtra, null, null)));
    }

    @Test
    void aCurrencyBindingWhoseActionDisagreesWithItsDefinitionIsRejected() {
        SemanticQueryPlan plan = genuine();
        List<CurrencyBinding> wrongAction = plan.currencyBindings().stream()
                .map(binding -> binding.definitionId().equals("member.tier")
                        ? new CurrencyBinding(MetadataKind.ATTRIBUTE, "member.tier",
                                CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY,
                                plan.trustedContext().reportingCurrency(),
                                CurrencyBinding.RateResolution.DEFERRED)
                        : binding)
                .toList();
        assertInconsistent(() -> construct(material(plan, null, null, null, null, wrongAction, null, null)));
    }

    @Test
    void aConversionBindingWhoseTargetIsNotTheTrustedCurrencyIsRejected() {
        SemanticQueryPlan plan = genuine();
        List<CurrencyBinding> wrongTarget = plan.currencyBindings().stream()
                .map(binding -> binding.action() == CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY
                        ? new CurrencyBinding(binding.kind(), binding.definitionId(),
                                CurrencyBinding.Action.CONVERT_TO_REPORTING_CURRENCY, "USD",
                                CurrencyBinding.RateResolution.DEFERRED)
                        : binding)
                .toList();
        assertInconsistent(() -> construct(material(plan, null, null, null, null, wrongTarget, null, null)));
    }

    // ---------------------------------------------------------------- positive controls

    @Test
    void theGenuinePlanStillConstructsAndVersionsAreUnchanged() {
        SemanticQueryPlan plan = genuine();
        assertThat(plan.planHash()).isEqualTo(SemanticQueryPlanCodec.hashMaterial(plan.material()));
        assertThat(new SemanticQueryPlanCodec().hash(plan)).isEqualTo(plan.planHash());
    }

    @Test
    void thePlannerAndTheConstructorShareOneStructuralTraversal() {
        SemanticQueryPlan plan = genuine();
        PlanStructure.Sites sites = PlanStructure.collect(plan.predicateRoot(), plan.subject());
        List<String> expectedPaths = Stream.concat(
                        sites.metrics().stream().map(PlanStructure.MetricSite::path),
                        sites.exists().stream().map(PlanStructure.ExistsSite::path))
                .sorted()
                .toList();
        assertThat(plan.windowBindings()).extracting(ResolvedWindowBinding::path)
                .containsExactlyElementsOf(expectedPaths);
    }

    @Test
    void theAstProjectsBackToTheExactCanonicalBytesAndHash() {
        SemanticQueryPlan plan = genuine();
        CanonicalSegmentCodec canonical = new CanonicalSegmentCodec();
        var projected = PlanStructure.canonicalProjection(plan.predicateRoot());

        assertThat(new QueryAstCodec().write(plan.predicateRoot())).isEqualTo(canonical.writeNode(projected));
        assertThat(canonical.hash(new CanonicalSegment(plan.canonicalizerVersion(), plan.dslSchemaVersion(),
                plan.subject(), plan.metadataBinding(), projected))).isEqualTo(plan.canonicalAstHash());
    }

    @Test
    void changingACurrencyBindingActionOrTargetChangesTheHashMaterial() {
        SemanticQueryPlan plan = genuine();
        CurrencyBinding tier = currencyBinding(plan, MetadataKind.ATTRIBUTE, "member.tier");
        CurrencyBinding changedAction = new CurrencyBinding(MetadataKind.ATTRIBUTE, "member.tier",
                CurrencyBinding.Action.SINGLE_CURRENCY_ONLY, null, CurrencyBinding.RateResolution.NOT_APPLICABLE);
        List<CurrencyBinding> modified = plan.currencyBindings().stream()
                .map(binding -> binding == tier ? changedAction : binding)
                .toList();

        ContentHash genuineHash = SemanticQueryPlanCodec.hashMaterial(plan.material());
        ContentHash modifiedHash = SemanticQueryPlanCodec.hashMaterial(
                material(plan, null, null, null, null, modified, null, null));
        assertThat(modifiedHash).isNotEqualTo(genuineHash);
    }
}