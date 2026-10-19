package com.company.campaign.query.plan;

import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.testsupport.ReferenceQueryFixtures;
import com.company.campaign.segment.reference.AudienceVersionAvailability;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.InMemoryAudienceRetentionStore;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.InMemoryReferenceDataScopePort;
import com.company.campaign.segment.reference.ReferenceExecutionContext;
import com.company.campaign.segment.reference.ReferencePolicy;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18: the execution guard re-proves every frozen reference fact before anything executes.
 *
 * <p>An earlier decision is not permanent authority. Each case below starts from a plan that was genuinely
 * authorized and frozen, moves exactly one trusted fact, and requires the guard to refuse — so a reference can
 * never be executed from evidence that the host would no longer stand behind.
 */
class ReferenceExecutionGuardTest {

    private static final AudienceVersionRef VERSION = ReferenceQueryFixtures.version();

    private static SemanticQueryPlan plan(
            InMemoryAudienceVersionCatalog catalog, ReferenceExecutionContext referenceContext) {
        return ReferenceQueryFixtures.plan(
                ReferenceQueryFixtures.validated(ReferenceQueryFixtures.referenceDocument(VERSION), catalog),
                catalog,
                referenceContext);
    }

    private static QueryException refusal(SemanticQueryPlan plan, ReferenceExecutionContext referenceContext) {
        return (QueryException) org.assertj.core.api.Assertions.catchThrowable(() ->
                new ReferenceExecutionGuard(SegmentReferenceFixtures.allowAll())
                        .requireExecutable(plan, SegmentValidationFixtures.context(), referenceContext));
    }

    // ---------------------------------------------------------------- accepts a genuine plan

    @Test
    void aGenuinePlanPassesEveryReCheck() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);

        new ReferenceExecutionGuard(SegmentReferenceFixtures.allowAll())
                .requireExecutable(plan(catalog, referenceContext), SegmentValidationFixtures.context(),
                        referenceContext);
    }

    @Test
    void aReferenceFreePlanNeverConsultsAPortOrAContext() {
        SemanticQueryPlan referenceFree = ReferenceQueryFixtures.plan(
                SegmentValidationFixtures.validDocument(), ReferenceQueryFixtures.catalogOf(VERSION));

        // Null contexts and unreachable ports must all be irrelevant: nothing is consulted at all.
        new ReferenceExecutionGuard(SegmentReferenceFixtures.allowAll())
                .requireExecutable(referenceFree, null, null);
    }

    // ---------------------------------------------------------------- drift refusals

    @Test
    void aDeniedSegmentPermissionRefusesExecution() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);
        SemanticQueryPlan plan = plan(catalog, referenceContext);

        QueryException refused = (QueryException) org.assertj.core.api.Assertions.catchThrowable(() ->
                new ReferenceExecutionGuard(SegmentReferenceFixtures.denySegmentsOnly())
                        .requireExecutable(plan, SegmentValidationFixtures.context(), referenceContext));

        assertThat(refused.code()).isEqualTo(QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED);
        assertThat(refused.details()).containsEntry("invariant", "segmentAuthorization");
    }

    @Test
    void aThrowingAuthorizationBoundaryRefusesExecution() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);
        SemanticQueryPlan plan = plan(catalog, referenceContext);

        QueryException refused = (QueryException) org.assertj.core.api.Assertions.catchThrowable(() ->
                new ReferenceExecutionGuard(SegmentReferenceFixtures.throwing())
                        .requireExecutable(plan, SegmentValidationFixtures.context(), referenceContext));

        assertThat(refused.code()).isEqualTo(QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED);
        assertThat(refused.details()).containsEntry("invariant", "segmentAuthorization");
    }

    @Test
    void aWithdrawnOrDeletedVersionRefusesExecution() {
        for (AudienceVersionAvailability withdrawn
                : new AudienceVersionAvailability[] {
                        AudienceVersionAvailability.UNAVAILABLE, AudienceVersionAvailability.DELETED}) {
            InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
            ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);
            SemanticQueryPlan plan = plan(catalog, referenceContext);

            catalog.updateAvailability(VERSION, withdrawn);
            QueryException refused = refusal(plan, referenceContext);

            assertThat(refused.code()).isEqualTo(QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED);
            assertThat(refused.details()).containsEntry("invariant", "versionAvailability");
        }
    }

    @Test
    void aVersionWhoseBytesOrCatalogFactsMovedRefusesExecution() {
        InMemoryAudienceVersionCatalog frozen = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext frozenContext = ReferenceQueryFixtures.executionContext(frozen);
        SemanticQueryPlan plan = plan(frozen, frozenContext);

        InMemoryAudienceVersionCatalog moved = ReferenceQueryFixtures.catalogWith(
                SegmentReferenceFixtures.snapshot(
                        VERSION, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        com.company.campaign.metadata.domain.SubjectKind.MEMBER,
                        SegmentReferenceFixtures.AUDIENCE_SCOPE, java.util.List.of(), 9,
                        AudienceVersionAvailability.AVAILABLE));

        QueryException refused = refusal(plan, ReferenceQueryFixtures.executionContext(moved));

        assertThat(refused.code()).isEqualTo(QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED);
        assertThat(refused.details()).containsEntry("invariant", "versionIdentity");
    }

    @Test
    void aVersionTheHostNoLongerSeesRefusesExecution() {
        InMemoryAudienceVersionCatalog frozen = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext frozenContext = ReferenceQueryFixtures.executionContext(frozen);
        SemanticQueryPlan plan = plan(frozen, frozenContext);

        InMemoryAudienceVersionCatalog blind = ReferenceQueryFixtures.catalogWith();
        QueryException refused = refusal(plan, ReferenceQueryFixtures.executionContext(blind));

        assertThat(refused.details()).containsEntry("invariant", "versionIdentity");
    }

    @Test
    void aReleasedRetentionHoldRefusesExecutionButAnAddedOneDoesNot() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        InMemoryAudienceRetentionStore retention = InMemoryAudienceRetentionStore.retaining();
        AudienceVersionRef holder = ReferenceQueryFixtures.version("audience-holder", "version-1");
        retention.acquire(new com.company.campaign.segment.reference.AudienceRetentionHold(
                "hold-0001", VERSION, holder.audienceId(), holder.versionId(), Instant.parse("2026-09-15T00:00:00Z")));
        ReferenceExecutionContext referenceContext =
                ReferenceQueryFixtures.executionContext(catalog, retention);
        SemanticQueryPlan plan = plan(catalog, referenceContext);
        assertThat(plan.audienceDependencies().get(0).retentionHoldIds()).containsExactly("hold-0001");

        // An additional hold is harmless: the evidence the plan depended on is still present.
        retention.acquire(new com.company.campaign.segment.reference.AudienceRetentionHold(
                "hold-0002", VERSION, holder.audienceId(), holder.versionId(), Instant.parse("2026-09-16T00:00:00Z")));
        new ReferenceExecutionGuard(SegmentReferenceFixtures.allowAll())
                .requireExecutable(plan, SegmentValidationFixtures.context(), referenceContext);

        // Releasing the hold the plan depended on is drift and must refuse.
        retention.release("hold-0001");
        QueryException refused = refusal(plan, referenceContext);

        assertThat(refused.code()).isEqualTo(QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED);
        assertThat(refused.details()).containsEntry("invariant", "referenceRetention");
    }

    @Test
    void anEffectiveScopeThatKeptItsNameButChangedItsMembersRefusesExecution() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);
        SemanticQueryPlan plan = plan(catalog, referenceContext);

        // Same scope identifier, strictly different members: the identity alone is not a proof.
        InMemoryReferenceDataScopePort moved = new InMemoryReferenceDataScopePort()
                .register(SegmentReferenceFixtures.CALLER_SCOPE, Set.of("m1", "m2", "m3", "m4"))
                .register(SegmentReferenceFixtures.AUDIENCE_SCOPE, Set.of("m1", "m2"));
        ReferenceExecutionContext movedContext = new ReferenceExecutionContext(
                catalog,
                SegmentReferenceFixtures.sameProgramOnly(),
                moved,
                catalog,
                InMemoryAudienceRetentionStore.retaining(),
                ReferencePolicy.standard());

        QueryException refused = refusal(plan, movedContext);

        assertThat(refused.code()).isEqualTo(QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED);
        assertThat(refused.details()).containsEntry("invariant", "effectiveDataScope");
    }

    @Test
    void anUnprovableCallerScopeRefusesExecution() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);
        SemanticQueryPlan plan = plan(catalog, referenceContext);
        ReferenceExecutionContext blindScope = new ReferenceExecutionContext(
                catalog,
                SegmentReferenceFixtures.sameProgramOnly(),
                new InMemoryReferenceDataScopePort(),
                catalog,
                InMemoryAudienceRetentionStore.retaining(),
                ReferencePolicy.standard());

        assertThat(refusal(plan, blindScope).details()).containsEntry("invariant", "effectiveDataScope");
    }

    @Test
    void aRefusalNeverDisclosesTheReferencedVersionTenantProgramOrScope() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);
        SemanticQueryPlan plan = plan(catalog, referenceContext);
        catalog.updateAvailability(VERSION, AudienceVersionAvailability.DELETED);

        QueryException refused = refusal(plan, referenceContext);

        assertThat(refused.getMessage())
                .doesNotContain(VERSION.audienceId(), VERSION.versionId(), VERSION.versionHash().value(),
                        SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        ReferenceQueryFixtures.EFFECTIVE_SCOPE_ID, SegmentValidationFixtures.context().userId());
        assertThat(refused.details().values())
                .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(
                        VERSION.audienceId(), VERSION.versionId(), SegmentReferenceFixtures.TENANT));
    }

    @Test
    void aMissingContextOrPortIsTheBoundedInputCodeRatherThanASilentPass() {
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        ReferenceExecutionContext referenceContext = ReferenceQueryFixtures.executionContext(catalog);
        SemanticQueryPlan plan = plan(catalog, referenceContext);

        ContractAssertions.assertFailsWithCode(
                () -> new ReferenceExecutionGuard(SegmentReferenceFixtures.allowAll())
                        .requireExecutable(plan, null, referenceContext),
                QueryErrorCodes.QUERY_INPUT_MISSING);
        ContractAssertions.assertFailsWithCode(
                () -> new ReferenceExecutionGuard(SegmentReferenceFixtures.allowAll())
                        .requireExecutable(plan, SegmentValidationFixtures.context(), null),
                QueryErrorCodes.QUERY_INPUT_MISSING);
        ContractAssertions.assertFailsWithCode(
                () -> new ReferenceExecutionGuard(null),
                QueryErrorCodes.QUERY_INPUT_MISSING);
    }
}