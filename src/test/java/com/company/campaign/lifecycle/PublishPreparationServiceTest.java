package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.contracts.Issue;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17 requirements 5, 6 and the Prepare Publish half of 7: a preparation is generated only from a saved,
 * owned Draft at its exact revision and only with {@code campaign.publish}; missing, non-ACTIVE, stale or
 * mismatched Gate evidence rejects production preparation while exact ACTIVE evidence succeeds.
 */
class PublishPreparationServiceTest {

    @Test
    void preparingFreezesTheExactSavedDraftAndItsFrozenEvidence() {
        PublishScenario scenario = PublishScenario.active();

        PreparePublishResult result = scenario.prepare();

        assertThat(result.draftIdentity()).isEqualTo(scenario.draft.draftIdentity());
        assertThat(result.readinessState()).isEqualTo("ACTIVE");
        assertThat(result.requiredRiskCodes()).isEmpty();
        assertThat(result.previewCards()).hasSize(1);
        assertThat(result.previewCards().get(0).readinessState()).isEqualTo("ACTIVE");
        assertThat(result.expiresAt())
                .isEqualTo(result.createdAt().plus(PublishPreparation.DEFAULT_LIFETIME));

        assertThat(scenario.store.preparationCount()).isEqualTo(1);
        PublishPreparation stored = scenario.store
                .findPreparation(result.preparationId()).orElseThrow();
        assertThat(stored.draftRevision()).isEqualTo(scenario.draft.draftRevision());
        assertThat(stored.manifestHash()).isEqualTo(scenario.draft.manifestHash());
        assertThat(stored.draftHash()).isEqualTo(scenario.draft.draftHash());
        assertThat(stored.planHash()).isEqualTo(scenario.plan.planHash());
        assertThat(stored.catalogHash()).isEqualTo(scenario.plan.catalogHash());
        assertThat(stored.capabilityPins()).isEqualTo(scenario.plan.capabilityPins());
        assertThat(stored.gateInputsHash())
                .isEqualTo(com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures
                        .GATE_INPUTS_HASH);
        assertThat(stored.approvedDecisionHash())
                .isEqualTo(com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures
                        .APPROVED_DECISION_HASH);
        assertThat(stored.preparationHash()).isEqualTo(result.preparationHash());
    }

    @Test
    void eachPrepareAllocatesADistinctPreparationOverTheSameFrozenEvidence() {
        PublishScenario scenario = PublishScenario.active();

        PreparePublishResult first = scenario.prepare();
        PreparePublishResult second = scenario.prepareService().prepare(
                scenario.prepareCommand(), scenario.context);

        // Preparing twice is two distinct immutable records over the same frozen evidence: a preparation is
        // not a lock, it is an authority, and each one expires on its own.
        assertThat(second.preparationId()).isNotEqualTo(first.preparationId());
        assertThat(second.preparationHash()).isNotEqualTo(first.preparationHash());
        assertThat(scenario.store.preparationCount()).isEqualTo(2);
    }

    @Test
    void preparingRecordsExactlyOneSafeAuditEventPerCommand() {
        PublishScenario scenario = PublishScenario.active();
        int afterApply = scenario.audit.size();

        scenario.prepare();

        assertThat(scenario.audit.size()).isEqualTo(afterApply + 1);
        LifecycleAuditEvent event = scenario.audit.events().get(afterApply);
        assertThat(event.action()).isEqualTo(LifecycleAction.PREPARE_PUBLISH);
        assertThat(event.disposition()).isEqualTo(LifecycleDisposition.APPLIED);
        assertThat(event.reasonCode()).isEqualTo(PublishPreparationService.PREPARED_REASON);
        assertThat(event.resourceFingerprint()).isNotNull();
        assertThat(event.planHash()).isEqualTo(scenario.plan.planHash());
        assertThat(event.contentHash()).isEqualTo(scenario.draft.draftHash());
        assertThat(event.toString()).doesNotContain(scenario.campaignId());
    }

    // ------------------------------------------------------------------ authorization and ownership

    @Test
    void preparingRequiresTheIndependentCampaignPublishPermission() {
        PublishScenario scenario = PublishScenario.active();
        int before = scenario.audit.size();

        assertThatThrownBy(() -> scenario.prepareService()
                .prepare(scenario.prepareCommand(), PublishTestFixtures.contextWithoutPublish()))
                .isInstanceOf(PermissionDeniedException.class)
                .satisfies(failure -> assertThat(((PermissionDeniedException) failure).details())
                        .containsEntry("requiredPermission", "campaign.publish")
                        .containsEntry("resourceKind", "CAMPAIGN_PUBLISH"));

        assertThat(scenario.store.preparationCount()).isZero();
        assertThat(scenario.audit.size()).isEqualTo(before + 1);
        assertThat(scenario.audit.events().get(before).disposition())
                .isEqualTo(LifecycleDisposition.REJECTED);
        assertThat(scenario.audit.events().get(before).reasonCode())
                .isEqualTo(LifecycleSupport.PERMISSION_DENIED_REASON);
    }

    @Test
    void anUnknownCampaignIsNonEnumerablyNotFound() {
        PublishScenario scenario = PublishScenario.active();

        assertThatThrownBy(() -> scenario.prepareService().prepare(
                new PreparePublishCommand("campaign-999", scenario.draft.draftId(), 1L), scenario.context))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(scenario.store.preparationCount()).isZero();
    }

    @Test
    void aForeignTenantOrUserCannotPrepareTheDraft() {
        PublishScenario scenario = PublishScenario.active();

        for (TrustedExecutionContext foreign : java.util.List.of(
                PublishTestFixtures.otherTenantContext(), PublishTestFixtures.otherUserContext())) {
            assertThatThrownBy(() -> scenario.prepareService()
                    .prepare(scenario.prepareCommand(), foreign))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
        assertThat(scenario.store.preparationCount()).isZero();
    }

    @Test
    void aStaleDraftIdentityOrRevisionIsRefused() {
        PublishScenario scenario = PublishScenario.active();
        String campaignId = scenario.campaignId();

        assertThatThrownBy(() -> scenario.prepareService().prepare(
                new PreparePublishCommand(campaignId, "draft-999", 1L), scenario.context))
                .as("a different Draft identity is not this Campaign's editable Draft")
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE));

        assertThatThrownBy(() -> scenario.prepareService().prepare(
                new PreparePublishCommand(campaignId, scenario.draft.draftId(), 7L), scenario.context))
                .as("a Draft revision the Campaign has moved past is not preparable")
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE));

        assertThat(scenario.store.preparationCount()).isZero();
    }

    @Test
    void aStructurallyInvalidCommandIsRefusedBeforeAnyLookup() {
        PublishScenario scenario = PublishScenario.active();

        assertThatThrownBy(() -> scenario.prepareService().prepare(
                new PreparePublishCommand("", scenario.draft.draftId(), 1L), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
        assertThatThrownBy(() -> scenario.prepareService().prepare(null, scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID));
    }

    // ------------------------------------------------------------------ production activation

    @Test
    void aPlanCompiledUnderDevelopmentReadinessIsNeverPrepared() {
        PublishScenario experimental = PublishScenario.of(PublishTestFixtures.experimentalSegmentPlan());
        PublishScenario evaluationRequired =
                PublishScenario.of(PublishTestFixtures.evaluationRequiredSegmentPlan());

        for (PublishScenario scenario : java.util.List.of(experimental, evaluationRequired)) {
            assertThatThrownBy(scenario::prepare)
                    .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                        assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED);
                        assertThat(failure.invariant()).isEqualTo("activationGuard");
                    });
            assertThat(scenario.store.preparationCount()).isZero();
        }
    }

    @Test
    void aProjectionOfTheWrongStateIsRejected() {
        for (var projection : java.util.List.of(
                PublishTestFixtures.experimentalProjection(),
                PublishTestFixtures.evaluationRequiredProjection())) {
            PublishScenario scenario = PublishScenario.active().withReadiness(projection);
            assertThatThrownBy(scenario::prepare)
                    .isInstanceOfSatisfying(LifecycleException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED));
            assertThat(scenario.store.preparationCount()).isZero();
        }
    }

    @Test
    void staleGateInputsOrASupersededApprovedDecisionAreRejected() {
        for (var projection : java.util.List.of(
                PublishTestFixtures.staleInputsProjection(),
                PublishTestFixtures.supersededDecisionProjection())) {
            PublishScenario scenario = PublishScenario.active().withReadiness(projection);
            assertThatThrownBy(scenario::prepare)
                    .isInstanceOfSatisfying(LifecycleException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED));
            assertThat(scenario.store.preparationCount()).isZero();
        }
    }

    @Test
    void missingReadinessEvidenceIsRejectedAndUnanswerableReadinessFailsClosed() {
        PublishScenario missing =
                PublishScenario.active().withReadiness(PublishTestFixtures.missingReadiness());
        assertThatThrownBy(missing::prepare)
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED));

        PublishScenario unavailable =
                PublishScenario.active().withReadiness(PublishTestFixtures.failingReadiness());
        assertThatThrownBy(unavailable::prepare)
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_UNAVAILABLE);
                    assertThat(LifecycleSupport.dispositionOf(failure))
                            .isEqualTo(LifecycleDisposition.UNAVAILABLE);
                });

        assertThat(missing.store.preparationCount()).isZero();
        assertThat(unavailable.store.preparationCount()).isZero();
    }

    @Test
    void incompatibleFrozenInputsRejectPreparation() {
        PublishScenario scenario = PublishScenario.active();
        PublishPreparationService service = new PublishPreparationService(
                scenario.plans, PublishTestFixtures.authorization(),
                query -> CompatibilityDecision.incompatible("CATALOG_MOVED"),
                LifecycleTestFixtures.valid(),
                PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                scenario.store, new SequentialLifecycleIdGenerator(), LifecycleTestFixtures.CLOCK);

        assertThatThrownBy(() -> service.prepare(scenario.prepareCommand(), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_STALE));
        assertThat(scenario.store.preparationCount()).isZero();
    }

    @Test
    void anUnanswerableCompatibilityPortFailsClosed() {
        PublishScenario scenario = PublishScenario.active();
        PublishPreparationService service = new PublishPreparationService(
                scenario.plans, PublishTestFixtures.authorization(),
                query -> CompatibilityDecision.unavailable("COMPATIBILITY_UNKNOWN"),
                LifecycleTestFixtures.valid(),
                PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                scenario.store, new SequentialLifecycleIdGenerator(), LifecycleTestFixtures.CLOCK);

        assertThatThrownBy(() -> service.prepare(scenario.prepareCommand(), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_UNAVAILABLE));
    }

    // ------------------------------------------------------------------ R1: final-Manifest validation

    /**
     * R1 requirement 5: Prepare Publish re-runs the same bounded deterministic final-Manifest validation
     * Apply and Publish use, and every untrustworthy answer fails closed and stores nothing.
     */
    @Test
    void anUntrustworthyFinalManifestFailsClosedAndStoresNothing() {
        PublishScenario scenario = PublishScenario.active();

        PublishPreparationService blocking = PublishTestFixtures.prepareService(
                scenario.plans, scenario.store,
                PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                request -> java.util.List.of(LifecycleTestFixtures.blockingIssue()),
                LifecycleTestFixtures.CLOCK, new SequentialLifecycleIdGenerator());
        assertThatThrownBy(() -> blocking.prepare(scenario.prepareCommand(), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_VALIDATION_REJECTED);
                    assertThat(failure.invariant()).isEqualTo("finalManifest");
                });

        for (ManifestValidationPort untrustworthy : java.util.List.<ManifestValidationPort>of(
                request -> null,
                request -> {
                    throw new IllegalStateException("validation backend unavailable");
                },
                request -> {
                    java.util.List<Issue> overBound = new java.util.ArrayList<>();
                    for (int index = 0; index <= LifecycleSupport.MAX_VALIDATION_ISSUES; index++) {
                        overBound.add(Issue.error("FIXTURE_ISSUE", "bounded"));
                    }
                    return overBound;
                },
                request -> java.util.Arrays.asList(Issue.error("FIXTURE_ISSUE", "bounded"), null))) {
            PublishPreparationService service = PublishTestFixtures.prepareService(
                    scenario.plans, scenario.store,
                    PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                    untrustworthy, LifecycleTestFixtures.CLOCK, new SequentialLifecycleIdGenerator());
            assertThatThrownBy(() -> service.prepare(scenario.prepareCommand(), scenario.context))
                    .isInstanceOfSatisfying(LifecycleException.class, failure -> assertThat(failure.code())
                            .isEqualTo(LifecycleErrorCodes.LIFECYCLE_VALIDATION_UNAVAILABLE));
        }

        // A valid answer still prepares, proving the validation is additive rather than a blanket refusal.
        assertThat(scenario.prepare().readinessState()).isEqualTo("ACTIVE");
        assertThat(scenario.store.preparationCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ R1: ownership before authorization

    /**
     * R1 requirement 1: Draft ownership is proved before the {@code campaign.publish} decision, so a Draft
     * owned by another user inside the same tenant is not distinguishable from an absent Campaign even for a
     * caller who also lacks the permission.
     */
    @Test
    void aSameTenantDraftOwnedByAnotherUserIsNotFoundEvenWithoutPublishPermission() {
        PublishScenario scenario = PublishScenario.active();
        TrustedExecutionContext lowPermissionOtherUser =
                LifecycleTestFixtures.otherUserWithoutPublishContext();
        assertThat(lowPermissionOtherUser.hasPermission(CampaignPermission.CAMPAIGN_PUBLISH)).isFalse();

        for (String campaignId : java.util.List.of(scenario.campaignId(), "campaign-999")) {
            assertThatThrownBy(() -> scenario.prepareService().prepare(
                    new PreparePublishCommand(campaignId, scenario.draft.draftId(),
                            scenario.draft.draftRevision()),
                    lowPermissionOtherUser))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        assertThat(scenario.store.preparationCount()).isZero();
    }

    /** R1 requirement 2: a prepare command without its trusted context fails closed with a stable code. */
    @Test
    void aMissingTrustedContextFailsClosedWithAStableCode() {
        PublishScenario scenario = PublishScenario.active();

        assertThatThrownBy(() -> scenario.prepareService().prepare(scenario.prepareCommand(), null))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID);
                    assertThat(failure.invariant()).isEqualTo("trustedContext");
                });
        assertThat(scenario.store.preparationCount()).isZero();
    }

    @Test
    void aDraftWhoseSourcePlanIsGoneCannotBePrepared() {
        CampaignPlan plan = PublishTestFixtures.activeSegmentPlan();
        PublishScenario scenario = PublishScenario.of(plan);

        // The Plan store no longer holds the Draft's source Plan: frozen evidence cannot be re-proved.
        var plans = PublishTestFixtures.planStore();
        PublishPreparationService service = PublishTestFixtures.prepareService(
                plans, scenario.store,
                PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                LifecycleTestFixtures.CLOCK, new SequentialLifecycleIdGenerator());

        assertThatThrownBy(() -> service.prepare(scenario.prepareCommand(), scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_DRAFT_NOT_PUBLISHABLE));
        assertThat(scenario.store.preparationCount()).isZero();
    }
}