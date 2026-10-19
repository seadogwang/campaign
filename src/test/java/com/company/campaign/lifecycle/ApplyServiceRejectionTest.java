package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.createPlan;
import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.editPlan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T17 requirement 2: a wrong hash, an unknown or foreign Plan, a wrong user/tenant/program, an expired
 * Plan, a stale Campaign/Draft revision, changed frozen compatibility and a failed final validation all
 * produce zero mutation and exactly one bounded audit event.
 *
 * <p>Requirement 6: a request that lacks {@code campaign.edit} is refused, and the absent-Plan and
 * cross-boundary failures are indistinguishable so the boundary is non-enumerating.
 */
class ApplyServiceRejectionTest {

    private static final ContentHash OTHER_HASH =
            ContentHash.sha256("some-other-plan".getBytes(StandardCharsets.UTF_8));

    // ------------------------------------------------------------------ input and identity

    @Test
    void aWrongPlanHashIsRefusedWithZeroMutation() {
        CampaignPlan plan = createPlan();
        Rejection rejection = reject(plan, new ApplyCommand(plan.planId(), OTHER_HASH),
                LifecycleTestFixtures.context());

        assertThat(rejection.failure()).isInstanceOf(LifecycleException.class)
                .satisfies(failure -> assertThat(((LifecycleException) failure).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_PLAN_MISMATCH));
        rejection.assertNoMutation();
    }

    @Test
    void anUnknownPlanIdIsIndistinguishableFromAForeignOne() {
        CampaignPlan plan = createPlan();
        // The Plan exists in the store, but under a foreign tenant, while the caller presents its own tenant.
        CampaignPlan foreign = createPlan(LifecycleTestFixtures.otherTenantContext());
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan, foreign), store);

        ResourceNotFoundException unknown = catchThrowableOfType(
                ResourceNotFoundException.class,
                () -> service.apply(new ApplyCommand("plan-absent-0001", OTHER_HASH),
                        LifecycleTestFixtures.context()));
        ResourceNotFoundException crossBoundary = catchThrowableOfType(
                ResourceNotFoundException.class,
                () -> service.apply(new ApplyCommand(foreign.planId(), foreign.planHash()),
                        LifecycleTestFixtures.context()));

        // The two failures are identical in type, code, message and details.
        assertThat(unknown.code()).isEqualTo(crossBoundary.code());
        assertThat(unknown.getMessage()).isEqualTo(crossBoundary.getMessage());
        assertThat(unknown.details()).isEqualTo(crossBoundary.details());
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    @Test
    void aForeignTenantProgramOrUserCannotApplyAnotherCallersPlan() {
        CampaignPlan plan = createPlan();

        assertNotFound(plan, LifecycleTestFixtures.otherTenantContext());
        assertNotFound(plan, LifecycleTestFixtures.otherProgramContext());
        assertNotFound(plan, LifecycleTestFixtures.otherUserContext());
    }

    @Test
    void aCallerWithoutCampaignEditIsRefused() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        assertThatThrownBy(() -> service.apply(
                new ApplyCommand(plan.planId(), plan.planHash()), LifecycleTestFixtures.contextWithoutEdit()))
                .isInstanceOf(PermissionDeniedException.class);

        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    /**
     * R1 requirement 1: ownership is proved before the permission decision.
     *
     * <p>A caller inside the same tenant and program but with a different verified user — and without
     * {@code campaign.edit} — must see the identical non-disclosing not-found failure for an absent Plan and
     * for a Plan that really exists but belongs to someone else. If authorization ran first, the permission
     * denial would enumerate the foreign Plan's existence.
     */
    @Test
    void aSameTenantPlanOwnedByAnotherUserIsNotFoundEvenForACallerWithoutCampaignEdit() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);
        TrustedExecutionContext lowPermissionOtherUser = LifecycleTestFixtures.otherUserWithoutEditContext();

        assertThat(lowPermissionOtherUser.hasPermission(CampaignPermission.CAMPAIGN_EDIT))
                .as("the caller must genuinely lack campaign.edit for this test to mean anything")
                .isFalse();

        ResourceNotFoundException existingForeignPlan = catchThrowableOfType(
                ResourceNotFoundException.class,
                () -> service.apply(new ApplyCommand(plan.planId(), plan.planHash()),
                        lowPermissionOtherUser));
        ResourceNotFoundException absentPlan = catchThrowableOfType(
                ResourceNotFoundException.class,
                () -> service.apply(new ApplyCommand("plan-absent-0001", OTHER_HASH),
                        lowPermissionOtherUser));

        assertThat(existingForeignPlan.code()).isEqualTo(absentPlan.code());
        assertThat(existingForeignPlan.getMessage()).isEqualTo(absentPlan.getMessage());
        assertThat(existingForeignPlan.details()).isEqualTo(absentPlan.details());
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    /**
     * R1 requirement 2: a command presented without its trusted context cannot be audited, but must still
     * fail closed with a stable bounded code rather than a raw {@link NullPointerException}.
     */
    @Test
    void aMissingTrustedContextFailsClosedWithAStableCodeAndNoRawNullPointerException() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleAuditSink audit = new InMemoryLifecycleAuditSink();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(audit);
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        assertThatThrownBy(() -> service.apply(new ApplyCommand(plan.planId(), plan.planHash()), null))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID);
                    assertThat(failure.invariant()).isEqualTo("trustedContext");
                });

        // There was no trusted owner to audit against, so nothing was recorded and nothing was mutated.
        assertThat(audit.events()).isEmpty();
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    @Test
    void aStructurallyInvalidCommandIsRefused() {
        CampaignPlan plan = createPlan();
        Rejection rejection = reject(plan, null, LifecycleTestFixtures.context());

        assertThat(rejection.failure()).isInstanceOf(LifecycleException.class)
                .satisfies(failure -> assertThat(((LifecycleException) failure).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
        rejection.assertNoMutation();
    }

    /**
     * R1 requirement 2: malformed command components are bounded, never a raw {@code NullPointerException}
     * or {@code IllegalArgumentException}.
     */
    @Test
    void malformedCommandComponentsProduceBoundedFailures() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleAuditSink audit = new InMemoryLifecycleAuditSink();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(audit);
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        for (ApplyCommand malformed : List.of(
                new ApplyCommand(null, plan.planHash()),
                new ApplyCommand("", plan.planHash()),
                new ApplyCommand(plan.planId(), null))) {
            Throwable failure = catchThrowableOfType(RuntimeException.class,
                    () -> service.apply(malformed, LifecycleTestFixtures.context()));
            assertThat(failure)
                    .as("a malformed command must be a bounded lifecycle failure, not %s", failure)
                    .isInstanceOf(LifecycleException.class)
                    .satisfies(bounded -> assertThat(((LifecycleException) bounded).code())
                            .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
        }

        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
        assertThat(audit.events()).allSatisfy(event ->
                assertThat(event.disposition()).isEqualTo(LifecycleDisposition.INVALID));
    }

    // ------------------------------------------------------------------ freshness

    @Test
    void anExpiredPlanIsRefused() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan), store, LifecycleTestFixtures.compatible(),
                LifecycleTestFixtures.valid(), LifecycleTestFixtures.clockAt(Instant.parse("2026-10-03T00:20:00Z")));

        Throwable failure = rejectionOf(service, plan, LifecycleTestFixtures.context());
        assertThat(failure).isInstanceOf(LifecycleException.class)
                .satisfies(cause -> assertThat(((LifecycleException) cause).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_PLAN_EXPIRED));
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    @Test
    void aCompetingEditAgainstAStaleDraftRevisionIsRefused() {
        CampaignPlan createPlan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        var plans = LifecycleTestFixtures.planStore(createPlan);
        ApplyService service = LifecycleTestFixtures.service(plans, store);

        ApplyResult created = service.apply(command(createPlan), LifecycleTestFixtures.context());
        SavedCampaignDraft base = store.findDraft(created.campaignId()).orElseThrow();

        CampaignPlan winner = editPlan(base, "winner");
        CampaignPlan loser = LifecycleTestFixtures.competingEditPlan(base, "loser");
        plans.store(winner);
        plans.store(loser);

        ApplyResult applied = service.apply(command(winner), LifecycleTestFixtures.context());
        assertThat(applied.draftRevision()).isEqualTo(2L);

        // The second writer read revision 1, which has since advanced to 2; it must be refused as stale.
        Throwable failure = rejectionOf(service, loser, LifecycleTestFixtures.context());
        assertThat(failure).isInstanceOf(LifecycleException.class)
                .satisfies(cause -> assertThat(((LifecycleException) cause).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE));

        assertThat(store.draftCount()).isEqualTo(1);
        assertThat(store.findDraft(base.campaignId()).orElseThrow().draftRevision()).isEqualTo(2L);
        assertThat(store.receiptCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ compatibility and validation

    @Test
    void changedFrozenCompatibilityIsRefused() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan), store,
                query -> CompatibilityDecision.incompatible("CATALOG_MOVED"),
                LifecycleTestFixtures.valid(), LifecycleTestFixtures.CLOCK);

        Throwable failure = rejectionOf(service, plan, LifecycleTestFixtures.context());
        assertThat(failure).isInstanceOf(LifecycleException.class)
                .satisfies(cause -> assertThat(((LifecycleException) cause).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_STALE));
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    @Test
    void anUnavailableCompatibilityDecisionFailsClosed() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan), store,
                query -> {
                    throw new IllegalStateException("compatibility backend unavailable");
                },
                LifecycleTestFixtures.valid(), LifecycleTestFixtures.CLOCK);

        Throwable failure = rejectionOf(service, plan, LifecycleTestFixtures.context());
        assertThat(failure).isInstanceOf(LifecycleException.class)
                .satisfies(cause -> assertThat(((LifecycleException) cause).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_UNAVAILABLE));
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    @Test
    void aFailedFinalManifestValidationIsRefused() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan), store, LifecycleTestFixtures.compatible(),
                request -> List.of(LifecycleTestFixtures.blockingIssue()), LifecycleTestFixtures.CLOCK);

        Throwable failure = rejectionOf(service, plan, LifecycleTestFixtures.context());
        assertThat(failure).isInstanceOf(LifecycleException.class)
                .satisfies(cause -> assertThat(((LifecycleException) cause).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_VALIDATION_REJECTED));
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    @Test
    void anUntrustworthyValidationResultFailsClosed() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan), store, LifecycleTestFixtures.compatible(),
                request -> null, LifecycleTestFixtures.CLOCK);

        Throwable failure = rejectionOf(service, plan, LifecycleTestFixtures.context());
        assertThat(failure).isInstanceOf(LifecycleException.class)
                .satisfies(cause -> assertThat(((LifecycleException) cause).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_VALIDATION_UNAVAILABLE));
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    // ------------------------------------------------------------------ audit

    @Test
    void everyRejectionProducesExactlyOneBoundedAuditEvent() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleAuditSink audit = new InMemoryLifecycleAuditSink();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(audit);
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        assertThatThrownBy(() -> service.apply(new ApplyCommand(plan.planId(), OTHER_HASH),
                LifecycleTestFixtures.context())).isInstanceOf(LifecycleException.class);

        assertThat(audit.events()).hasSize(1);
        LifecycleAuditEvent event = audit.events().get(0);
        assertThat(event.action()).isEqualTo(LifecycleAction.APPLY);
        assertThat(event.disposition()).isEqualTo(LifecycleDisposition.REJECTED);
        assertThat(event.reasonCode()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PLAN_MISMATCH);
        assertThat(event.tenantId()).isEqualTo(LifecycleTestFixtures.context().tenantId());
        // No raw Manifest, ChangeSet, payload or trusted identifier beyond the caller's own context.
        assertThat(event.planHash()).isEqualTo(plan.planHash());
        assertThat(event.contentHash()).isNull();
    }

    @Test
    void aCrossBoundaryRejectionRecordsNoResourceOrPlanHash() {
        CampaignPlan foreign = createPlan(LifecycleTestFixtures.otherTenantContext());
        InMemoryLifecycleAuditSink audit = new InMemoryLifecycleAuditSink();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(audit);
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(foreign), store);

        assertThatThrownBy(() -> service.apply(
                new ApplyCommand(foreign.planId(), foreign.planHash()), LifecycleTestFixtures.context()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(audit.events()).hasSize(1);
        LifecycleAuditEvent event = audit.events().get(0);
        assertThat(event.disposition()).isEqualTo(LifecycleDisposition.NOT_FOUND);
        assertThat(event.resourceFingerprint()).isNull();
        assertThat(event.planHash()).isNull();
        assertThat(event.reasonCode()).isEqualTo(ApplyService.RESOURCE_NOT_FOUND_REASON);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertNotFound(CampaignPlan plan, TrustedExecutionContext context) {
        InMemoryLifecycleStore store = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);
        assertThatThrownBy(() -> service.apply(new ApplyCommand(plan.planId(), plan.planHash()), context))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    private static Throwable rejectionOf(ApplyService service, CampaignPlan plan,
            TrustedExecutionContext context) {
        return catchThrowableOfType(RuntimeException.class,
                () -> service.apply(command(plan), context));
    }

    private static Rejection reject(CampaignPlan plan, ApplyCommand command,
            TrustedExecutionContext context) {
        InMemoryLifecycleAuditSink audit = new InMemoryLifecycleAuditSink();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(audit);
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);
        Throwable failure = catchThrowableOfType(RuntimeException.class,
                () -> service.apply(command, context));
        return new Rejection(store, audit, failure);
    }

    private static ApplyCommand command(CampaignPlan plan) {
        return new ApplyCommand(plan.planId(), plan.planHash());
    }

    private record Rejection(InMemoryLifecycleStore store, InMemoryLifecycleAuditSink audit,
            Throwable failure) {

        void assertNoMutation() {
            assertThat(store.draftCount()).isZero();
            assertThat(store.receiptCount()).isZero();
            assertThat(audit.events()).hasSize(1);
            assertThat(audit.events().get(0).disposition().mutated()).isFalse();
        }
    }
}