package com.company.campaign.query.plan;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.query.testsupport.ReferenceQueryFixtures;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.reference.AudienceProgramPolicy;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.ReferenceDataScopePort;
import com.company.campaign.segment.reference.ReferenceExecutionContext;
import com.company.campaign.segment.reference.ReferencePolicy;
import com.company.campaign.segment.reference.ReferenceValidationContext;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18: the T08 planner independently re-resolves, reauthorizes and freezes every exact Audience Version pin.
 *
 * <p>Two properties matter most here. A document with no reference must plan exactly as it did before
 * references existed — including when a host supplies ports that would fail if they were ever consulted. And
 * a document that does reference a version must be planned from trusted ports that are re-asked at plan time,
 * with any drift between the accepted validation and the plan rejected rather than absorbed.
 */
class AudienceReferencePlanningTest {

    private static final AudienceVersionRef VERSION = ReferenceQueryFixtures.version();

    private static Predicate referenceWithinAGroup() {
        return SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                ReferenceQueryFixtures.reference("n-ref", VERSION)));
    }

    // ---------------------------------------------------------------- exact pin success

    @Test
    void aReferencePlanFreezesItsExactVersionEffectiveScopeAndEvidence() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);

        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        assertThat(plan.audienceDependencies()).hasSize(1);
        AudienceVersionDependency dependency = plan.audienceDependencies().get(0);
        assertThat(dependency.reference()).isEqualTo(VERSION);
        assertThat(dependency.subject()).isEqualTo(SubjectKind.MEMBER);
        assertThat(dependency.ownerTenantId()).isEqualTo(SegmentReferenceFixtures.TENANT);
        assertThat(dependency.ownerProgramId()).isEqualTo(SegmentReferenceFixtures.PROGRAM);
        assertThat(dependency.effectiveDataScope().dataScopeId())
                .isEqualTo(ReferenceQueryFixtures.EFFECTIVE_SCOPE_ID);
        assertThat(dependency.directDependencies()).isEmpty();
        assertThat(dependency.depth()).isEqualTo(1);
        assertThat(dependency.retentionHoldIds()).isEmpty();
        assertThat(dependency.availability().isUsable()).isTrue();
        assertThat(plan.hasAudienceReferences()).isTrue();
    }

    @Test
    void thePlanRequiresTheHostComputedEffectiveIntersectionRatherThanOnlyTheCallerScope() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);

        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        DataScopePredicate predicate = plan.dataScopePredicate();

        assertThat(predicate.kind())
                .isEqualTo(DataScopePredicate.Requirement.SUBJECT_IN_EFFECTIVE_DATASCOPE);
        assertThat(predicate.isEffectiveIntersection()).isTrue();
        assertThat(predicate.subject()).isEqualTo(SubjectKind.MEMBER);
        // The caller's own scope is strictly wider than the referenced version allows, so the plan must not
        // claim it; the effective identity is the host-computed intersection.
        assertThat(predicate.dataScopeId()).isEqualTo(ReferenceQueryFixtures.EFFECTIVE_SCOPE_ID);
        assertThat(predicate.dataScopeId()).isNotEqualTo(ReferenceQueryFixtures.CALLER_SCOPE_ID);
        assertThat(predicate.dataScopeHash()).isNotNull();
        assertThat(predicate.dataScopeHash())
                .isEqualTo(plan.audienceDependencies().get(0).effectiveDataScope().dataScopeHash());
    }

    @Test
    void theReferenceStaysOneLeafInTheAstAndCarriesNoTrustedFact() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);

        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(referenceWithinAGroup(), catalog);

        assertThat(QueryPlanFixtures.countNodes(plan.predicateRoot(), QueryAst.AudienceReference.class))
                .isEqualTo(1);
        QueryAst.AudienceReference leaf = (QueryAst.AudienceReference) ((QueryAst.Group) plan.predicateRoot())
                .children().get(1);
        // Only the immutable pin: no tenant, program, scope, availability, dependency or complexity fact.
        assertThat(leaf.reference().audienceId()).isEqualTo(VERSION.audienceId());
        assertThat(leaf.reference().versionId()).isEqualTo(VERSION.versionId());
        assertThat(leaf.reference().versionHash()).isEqualTo(VERSION.versionHash());
    }

    @Test
    void duplicateReferenceSitesYieldOneFrozenDependencyAndBothLeaves() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        Predicate predicate = SegmentValidationFixtures.all("n-root", List.of(
                ReferenceQueryFixtures.reference("n-ref-1", VERSION),
                SegmentValidationFixtures.not("n-not", ReferenceQueryFixtures.reference("n-ref-2", VERSION))));

        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(predicate, catalog);

        assertThat(plan.audienceDependencies()).hasSize(1);
        assertThat(QueryPlanFixtures.countNodes(plan.predicateRoot(), QueryAst.AudienceReference.class))
                .isEqualTo(2);
    }

    @Test
    void aNestedReferenceFreezesItsOwnTransitiveDependencyGraph() {
        AudienceVersionRef nested = ReferenceQueryFixtures.version("audience-base", "version-1");
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogWith(
                SegmentReferenceFixtures.snapshot(VERSION, List.of(nested)),
                SegmentReferenceFixtures.snapshot(nested));

        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        assertThat(plan.audienceDependencies()).extracting(dependency -> dependency.reference())
                .containsExactlyInAnyOrder(VERSION, nested);
        AudienceVersionDependency outer = plan.audienceDependencies().stream()
                .filter(dependency -> dependency.reference().equals(VERSION))
                .findFirst()
                .orElseThrow();
        assertThat(outer.directDependencies()).containsExactly(nested);
        assertThat(outer.depth()).isEqualTo(1);
        assertThat(plan.audienceDependencies().stream()
                .filter(dependency -> dependency.reference().equals(nested))
                .findFirst()
                .orElseThrow()
                .depth()).isEqualTo(2);
    }

    @Test
    void aMultiParentReferenceGraphIsHashEquivalentBetweenValidationAndPlanning() {
        // One referenced version whose catalog dependencies are two parents that share one transitive version,
        // the second of which is published under a strictly narrower scope.
        AudienceVersionRef wideParent = ReferenceQueryFixtures.version("audience-parent-a", "version-1");
        AudienceVersionRef narrowParent = ReferenceQueryFixtures.version("audience-parent-z", "version-1");
        AudienceVersionRef shared = ReferenceQueryFixtures.version("audience-shared", "version-1");
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogWith(
                SegmentReferenceFixtures.snapshot(VERSION, List.of(wideParent, narrowParent)),
                SegmentReferenceFixtures.snapshot(wideParent, List.of(shared)),
                SegmentReferenceFixtures.snapshot(narrowParent, SegmentReferenceFixtures.TENANT,
                        SegmentReferenceFixtures.PROGRAM, SubjectKind.MEMBER,
                        SegmentReferenceFixtures.NARROW_SCOPE, List.of(shared), 2,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE),
                SegmentReferenceFixtures.snapshot(shared));

        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(
                result, catalog, ReferenceQueryFixtures.executionContext(catalog));

        // Planning re-resolves the graph independently and refuses it unless its hash equals the accepted
        // validation-time hash, so planning at all is the equivalence proof; the scopes must agree as well.
        assertThat(result.referenceGraph().versions().stream()
                .filter(resolved -> resolved.reference().equals(shared))
                .findFirst().orElseThrow().effectiveDataScope().dataScopeId())
                .isEqualTo(SegmentReferenceFixtures.NARROW_SCOPE);
        assertThat(plan.audienceDependencies()).hasSize(4);
        assertThat(plan.audienceDependencies().stream()
                .filter(dependency -> dependency.reference().equals(shared))
                .findFirst().orElseThrow().effectiveDataScope().dataScopeId())
                .isEqualTo(SegmentReferenceFixtures.NARROW_SCOPE);
        // The plan binds the host-computed meet of every path, not the caller's own wider scope.
        assertThat(plan.dataScopePredicate().dataScopeId()).isEqualTo(SegmentReferenceFixtures.NARROW_SCOPE);
        assertThat(plan.dataScopePredicate().dataScopeId())
                .isNotEqualTo(ReferenceQueryFixtures.CALLER_SCOPE_ID);
    }

    @Test
    void everyFrozenCatalogFactIsPlanHashCovered() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan base = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        InMemoryAudienceVersionCatalog other = ReferenceQueryFixtures.catalogWith(
                SegmentReferenceFixtures.snapshot(
                        VERSION, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, SegmentReferenceFixtures.AUDIENCE_SCOPE, List.of(), 9,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE));
        SemanticQueryPlan changed = ReferenceQueryFixtures.plan(
                ReferenceQueryFixtures.referenceDocument(VERSION), other);

        assertThat(changed.audienceDependencies().get(0).expandedComplexity()).isEqualTo(9);
        assertThat(changed.planHash()).isNotEqualTo(base.planHash());
        assertThat(changed.audienceDependencies()).isNotEqualTo(base.audienceDependencies());
    }

    @Test
    void planningIsDeterministicForAReferenceBearingDocument() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);

        SemanticQueryPlan first = ReferenceQueryFixtures.plan(result, catalog, referenceContext);
        SemanticQueryPlan second = ReferenceQueryFixtures.plan(result, catalog, referenceContext);

        assertThat(second).isEqualTo(first);
        assertThat(second.planHash()).isEqualTo(first.planHash());
        assertThat(new SemanticQueryPlanCodec().writeString(second))
                .isEqualTo(new SemanticQueryPlanCodec().writeString(first));
    }

    // ---------------------------------------------------------------- fails closed

    @Test
    void planningAReferenceWithoutTheTrustedPortsFailsClosed() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        ContractAssertions.assertFailsWithCode(
                () -> new SegmentQueryPlanner(SegmentReferenceFixtures.allowAll()).plan(
                        result, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context()),
                QueryErrorCodes.QUERY_REFERENCE_CONTEXT_REQUIRED);
    }

    // ---------------------------------------------------------------- no-reference compatibility

    @Test
    void aDocumentWithoutAReferencePlansIdenticallyWithAndWithoutReferencePorts() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        ReferenceExecutionContext refusedEverywhere = new ReferenceExecutionContext(
                reference -> {
                    throw new IllegalStateException("must never be consulted");
                },
                (caller, referenced) -> {
                    throw new IllegalStateException("must never be consulted");
                },
                new ReferenceDataScopePort() {
                    @Override
                    public Optional<com.company.campaign.segment.reference.EffectiveDataScope> callerScope(
                            String tenantId, String programId, String userId, String dataScopeId) {
                        throw new IllegalStateException("must never be consulted");
                    }

                    @Override
                    public Optional<com.company.campaign.segment.reference.EffectiveDataScope> intersect(
                            com.company.campaign.segment.reference.EffectiveDataScope caller,
                            com.company.campaign.segment.reference.EffectiveDataScope parent,
                            com.company.campaign.segment.reference.EffectiveDataScope referenced) {
                        throw new IllegalStateException("must never be consulted");
                    }
                },
                reference -> {
                    throw new IllegalStateException("must never be consulted");
                },
                new com.company.campaign.segment.reference.AudienceRetentionPort() {
                    @Override
                    public com.company.campaign.segment.reference.AudienceRetentionHold acquire(
                            com.company.campaign.segment.reference.AudienceRetentionHold hold) {
                        throw new IllegalStateException("must never be consulted");
                    }

                    @Override
                    public void release(String holdId) {
                        throw new IllegalStateException("must never be consulted");
                    }

                    @Override
                    public java.util.List<com.company.campaign.segment.reference.AudienceRetentionHold>
                            activeHolds(AudienceVersionRef reference) {
                        throw new IllegalStateException("must never be consulted");
                    }

                    @Override
                    public com.company.campaign.segment.reference.HardDeletionOutcome hardDelete(
                            AudienceVersionRef reference) {
                        throw new IllegalStateException("must never be consulted");
                    }

                    @Override
                    public com.company.campaign.segment.reference.RetentionReleasePolicy releasePolicy() {
                        throw new IllegalStateException("must never be consulted");
                    }
                },
                ReferencePolicy.standard());

        SemanticQueryPlan without = new SegmentQueryPlanner(SegmentValidationFixtures.allowAll()).plan(
                result, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context());
        SemanticQueryPlan with = new SegmentQueryPlanner(SegmentValidationFixtures.allowAll()).plan(
                result, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context(),
                refusedEverywhere);

        assertThat(with).isEqualTo(without);
        assertThat(with.audienceDependencies()).isEmpty();
        assertThat(with.hasAudienceReferences()).isFalse();
        assertThat(with.dataScopePredicate().isEffectiveIntersection()).isFalse();
        assertThat(with.dataScopePredicate().dataScopeId())
                .isEqualTo(SegmentValidationFixtures.context().dataScopeId());
        assertThat(with.dataScopePredicate().dataScopeHash()).isNull();
        assertThat(new SemanticQueryPlanCodec().writeString(with))
                .isEqualTo(new SemanticQueryPlanCodec().writeString(without));
    }

    // ---------------------------------------------------------------- drift rejects planning

    @Test
    void authorizationDriftBetweenValidationAndPlanningRejectsPlanning() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        ContractAssertions.assertFailsWithCode(
                () -> new SegmentQueryPlanner(SegmentReferenceFixtures.denySegmentsOnly()).plan(
                        result, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context(),
                        ReferenceQueryFixtures.executionContext(catalog)),
                QueryErrorCodes.QUERY_REFERENCE_DRIFT);
    }

    @Test
    void versionBytesOrCatalogFactDriftBetweenValidationAndPlanningRejectsPlanning() {
        InMemoryAudienceVersionCatalog accepted = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), accepted);

        // Same audience and version identity, different published bytes: the accepted graph cannot be re-made.
        InMemoryAudienceVersionCatalog moved = ReferenceQueryFixtures.catalogWith(
                SegmentReferenceFixtures.snapshot(
                        VERSION, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, SegmentReferenceFixtures.NARROW_SCOPE, List.of(), 1,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE));

        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.plan(
                        result, moved, ReferenceQueryFixtures.executionContext(moved)),
                QueryErrorCodes.QUERY_REFERENCE_DRIFT);
    }

    @Test
    void availabilityDriftBetweenValidationAndPlanningRejectsPlanning() {
        InMemoryAudienceVersionCatalog accepted = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), accepted);

        InMemoryAudienceVersionCatalog withdrawn = ReferenceQueryFixtures.catalogOf(VERSION);
        withdrawn.updateAvailability(VERSION,
                com.company.campaign.segment.reference.AudienceVersionAvailability.DELETED);

        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.plan(
                        result, withdrawn, ReferenceQueryFixtures.executionContext(withdrawn)),
                QueryErrorCodes.QUERY_REFERENCE_DRIFT);
    }

    @Test
    void programPolicyDriftBetweenValidationAndPlanningRejectsPlanning() {
        AudienceVersionRef sibling = ReferenceQueryFixtures.version("audience-sibling", "version-1");
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogWith(
                SegmentReferenceFixtures.snapshot(
                        sibling, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.SIBLING_PROGRAM,
                        SubjectKind.MEMBER, SegmentReferenceFixtures.AUDIENCE_SCOPE, List.of(), 1,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE));
        AudienceProgramPolicy permissive = SegmentReferenceFixtures.allowingSiblingProgram();
        SegmentValidationResult result = new com.company.campaign.segment.validation.SegmentValidator(
                SegmentReferenceFixtures.allowAll()).validate(
                        ReferenceQueryFixtures.referenceDocument(sibling),
                        SegmentValidationFixtures.snapshot(),
                        SegmentValidationFixtures.context(),
                        SegmentValidationFixtures.policy(),
                        ReferenceValidationContext.of(
                                catalog, permissive, SegmentReferenceFixtures.dataScopePort(),
                                ReferencePolicy.standard()));

        assertThat(result.valid()).isTrue();
        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.plan(
                        result, catalog, ReferenceQueryFixtures.executionContext(catalog)),
                QueryErrorCodes.QUERY_REFERENCE_DRIFT);
    }

    @Test
    void anUnprovableCallerDataScopeRejectsPlanning() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        ReferenceExecutionContext blindScope = new ReferenceExecutionContext(
                catalog,
                AudienceProgramPolicy.sameProgramOnly(),
                new com.company.campaign.segment.reference.InMemoryReferenceDataScopePort(),
                catalog,
                com.company.campaign.segment.reference.InMemoryAudienceRetentionStore.retaining(),
                ReferencePolicy.standard());

        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.plan(result, catalog, blindScope),
                QueryErrorCodes.QUERY_REFERENCE_DRIFT);
    }

    @Test
    void aThrowingRetentionPortRejectsPlanningRatherThanFreezingNoEvidence() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        ReferenceExecutionContext throwingRetention = new ReferenceExecutionContext(
                catalog,
                AudienceProgramPolicy.sameProgramOnly(),
                SegmentReferenceFixtures.dataScopePort(),
                catalog,
                ReferenceQueryFixtures.unreachableRetention(),
                ReferencePolicy.standard());

        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.plan(result, catalog, throwingRetention),
                QueryErrorCodes.QUERY_REFERENCE_DRIFT);
    }

    // ---------------------------------------------------------------- retention evidence

    @Test
    void aReferenceFreePlanCarriesNoReferenceMaterialOnTheWire() {
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(
                SegmentValidationFixtures.validDocument(), ReferenceQueryFixtures.catalogOf(VERSION));

        String wire = new SemanticQueryPlanCodec().writeString(plan);

        // The plan document always declares the section, but a reference-free plan declares it empty and its
        // DataScope requirement keeps the three-field caller-scope shape it had before references existed.
        assertThat(wire).contains("\"audienceDependencies\":[]");
        assertThat(wire).doesNotContain("AUDIENCE_REFERENCE");
        assertThat(wire).doesNotContain("dataScopeHash");
        assertThat(wire).contains("SUBJECT_IN_DATASCOPE");
        assertThat(new SemanticQueryPlanCodec().hash(plan)).isEqualTo(plan.planHash());
    }

    @Test
    void retentionEvidenceFrozenAtPlanTimeIsPlanHashCovered() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        com.company.campaign.segment.reference.InMemoryAudienceRetentionStore held =
                com.company.campaign.segment.reference.InMemoryAudienceRetentionStore.retaining();
        held.acquire(new com.company.campaign.segment.reference.AudienceRetentionHold(
                "hold-0007", VERSION, "audience-parent", "version-9",
                java.time.Instant.parse("2026-09-15T00:00:00Z")));

        SemanticQueryPlan unheld = ReferenceQueryFixtures.plan(
                result, catalog, ReferenceQueryFixtures.executionContext(catalog));
        SemanticQueryPlan withHold = ReferenceQueryFixtures.plan(
                result, catalog, ReferenceQueryFixtures.executionContext(catalog, held));

        assertThat(unheld.audienceDependencies().get(0).retentionHoldIds()).isEmpty();
        assertThat(withHold.audienceDependencies().get(0).retentionHoldIds()).containsExactly("hold-0007");
        assertThat(withHold.planHash()).isNotEqualTo(unheld.planHash());
    }

    @Test
    void retentionHoldsObservedAtPlanTimeAreFrozenAsEvidence() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        com.company.campaign.segment.reference.InMemoryAudienceRetentionStore retention =
                com.company.campaign.segment.reference.InMemoryAudienceRetentionStore.retaining();
        retention.acquire(new com.company.campaign.segment.reference.AudienceRetentionHold(
                "hold-0001", VERSION, "audience-parent", "version-9",
                java.time.Instant.parse("2026-09-15T00:00:00Z")));
        SegmentValidationResult result = ReferenceQueryFixtures.validated(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(
                result, catalog, ReferenceQueryFixtures.executionContext(catalog, retention));

        assertThat(plan.audienceDependencies().get(0).retentionHoldIds()).containsExactly("hold-0001");
    }

    // ---------------------------------------------------------------- plan coherence

    @Test
    void anEffectiveScopeRequirementWithoutAReferenceIsContradictoryPlanMaterial() {
        SemanticQueryPlan genuine = ReferenceQueryFixtures.plan(
                SegmentValidationFixtures.validDocument(), ReferenceQueryFixtures.catalogOf(VERSION));
        assertThat(genuine.audienceDependencies()).isEmpty();

        ContractAssertions.assertFailsWithCode(
                () -> new SemanticQueryPlan(
                        genuine.planFormatVersion(), genuine.plannerVersion(), genuine.compilerVersion(),
                        genuine.queryAstVersion(), genuine.dslSchemaVersion(), genuine.canonicalizerVersion(),
                        genuine.subject(), genuine.predicateRoot(), genuine.canonicalAstHash(),
                        genuine.metadataBinding(), genuine.dependencies(), List.of(),
                        genuine.windowBindings(), genuine.currencyBindings(),
                        new DataScopePredicate(
                                DataScopePredicate.Requirement.SUBJECT_IN_EFFECTIVE_DATASCOPE,
                                SubjectKind.MEMBER,
                                ReferenceQueryFixtures.EFFECTIVE_SCOPE_ID,
                                com.company.campaign.shared.canonical.ContentHash.sha256(
                                        "scope".getBytes(StandardCharsets.UTF_8))),
                        genuine.trustedContext(), genuine.planHash()),
                QueryErrorCodes.QUERY_PLAN_INCONSISTENT);
    }

    @Test
    void anAudienceDependencyWithNoReferenceSiteIsContradictoryPlanMaterial() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan referencePlan = ReferenceQueryFixtures.plan(
                ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        SemanticQueryPlan referenceFree = ReferenceQueryFixtures.plan(
                SegmentValidationFixtures.validDocument(), catalog);
        List<AudienceVersionDependency> frozen = referencePlan.audienceDependencies();

        ContractAssertions.assertFailsWithCode(
                () -> new SemanticQueryPlan(
                        referenceFree.planFormatVersion(), referenceFree.plannerVersion(),
                        referenceFree.compilerVersion(), referenceFree.queryAstVersion(),
                        referenceFree.dslSchemaVersion(), referenceFree.canonicalizerVersion(),
                        referenceFree.subject(), referenceFree.predicateRoot(),
                        referenceFree.canonicalAstHash(), referenceFree.metadataBinding(),
                        referenceFree.dependencies(), frozen, referenceFree.windowBindings(),
                        referenceFree.currencyBindings(),
                        new DataScopePredicate(
                                DataScopePredicate.Requirement.SUBJECT_IN_EFFECTIVE_DATASCOPE,
                                SubjectKind.MEMBER,
                                ReferenceQueryFixtures.EFFECTIVE_SCOPE_ID,
                                frozen.get(0).effectiveDataScope().dataScopeHash()),
                        referenceFree.trustedContext(),
                        com.company.campaign.query.plan.SemanticQueryPlanCodec.hashMaterial(
                                referenceFree.material())),
                QueryErrorCodes.QUERY_PLAN_INCONSISTENT);
    }

    @Test
    void aReferencePinOutsideThePlanContributorSetIsRejectedByTheContract() {
        // The pin grammar is the first boundary: a version identity carrying SQL-looking text never becomes
        // a reference at all, so no later layer has to defend against it.
        ContractAssertions.assertFailsWithCode(
                () -> ReferenceQueryFixtures.version("audience-vip", "version-1' OR 1=1 --"),
                com.company.campaign.segment.reference.ReferenceErrorCodes.INPUT_INVALID);
        assertThatThrownBy(
                        () -> com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate.of(
                                "n-ref", "audience-vip", "version-7", "not-a-hash"))
                .isInstanceOf(com.company.campaign.segment.dsl.contract.SegmentDslException.class);
    }

    @Test
    void aReferenceToAnUnpublishedVersionFailsValidationRatherThanPlanning() {
        InMemoryAudienceVersionCatalog empty = ReferenceQueryFixtures.catalogWith();

        SegmentValidationResult result = new com.company.campaign.segment.validation.SegmentValidator(
                SegmentReferenceFixtures.allowAll()).validate(
                        ReferenceQueryFixtures.referenceDocument(VERSION),
                        SegmentValidationFixtures.snapshot(),
                        SegmentValidationFixtures.context(),
                        SegmentValidationFixtures.policy(),
                        SegmentReferenceFixtures.referenceContext(empty));

        assertThat(result.valid()).isFalse();
        assertThat(result.referenceGraph()).isNull();
        assertThat(result.issues()).extracting(com.company.campaign.contracts.Issue::code)
                .containsExactly(
                        com.company.campaign.segment.reference.ReferenceErrorCodes.VERSION_NOT_FOUND);
    }
}