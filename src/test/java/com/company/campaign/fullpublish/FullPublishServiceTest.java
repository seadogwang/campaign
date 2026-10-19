package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.lifecycle.ApplyCommand;
import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.PreparePublishResult;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Focused T21 full-publication tests: the one atomic commit, idempotency, binding and rejection. */
class FullPublishServiceTest {

    private static LifecycleException failureOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, LifecycleException.class);
    }

    private static PreparePublishResult basePreparationOf(FullPublishScenario scenario) {
        return scenario.base.prepareService().prepare(scenario.base.prepareCommand(), scenario.context);
    }

    private static PrepareFullPublishResult fullPreparationOf(
            FullPublishScenario scenario, PreparePublishResult base) {
        return scenario.prepareService().prepare(
                new PrepareFullPublishCommand(base.preparationId(), base.preparationHash()),
                scenario.context);
    }

    // ------------------------------------------------------------------ the atomic commit

    @Test
    void oneConfirmationFreezesTheBaseDefinitionTheFullDefinitionTheProjectionAndTheOutbox() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();

        FullPublishResult result = scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes());

        // The accepted T17 definition and receipt, committed by the accepted T17 store primitive.
        PublishedCampaignDefinition base = scenario.base.store.findLatestPublished(scenario.campaignId())
                .orElseThrow();
        assertThat(base.publishedRevision()).isEqualTo(1L);
        assertThat(base.tenantId()).isEqualTo(scenario.context.tenantId());
        assertThat(scenario.base.store.isPreparationConsumed(scenario.basePreparation.preparationId()))
                .as("the accepted T17 preparation is consumed by the same transaction")
                .isTrue();

        // The full definition and receipt.
        FullPublishedDefinition full = scenario.store.findFullDefinition(result.fullDefinitionId())
                .orElseThrow();
        assertThat(full.publicationRevision()).isEqualTo(1L);
        assertThat(full.baseIdentity()).isEqualTo(base.identity());
        assertThat(full.manifestHash()).isEqualTo(base.manifestHash());
        assertThat(full.journey().sourceManifestHash()).isEqualTo(base.manifestHash());
        assertThat(result.baseIdentity()).isEqualTo(base.identity());
        assertThat(scenario.store.fullDefinitionCount()).isEqualTo(1);
        assertThat(scenario.store.fullReceiptCount()).isEqualTo(1);

        // The active schedule projection and the durable scheduling-registration work item.
        CampaignScheduleState state = scenario.activeSchedule();
        assertThat(state.status()).isEqualTo(ScheduleStatus.ACTIVE);
        assertThat(state.stateRevision()).isEqualTo(1L);
        assertThat(state.fullDefinitionId()).isEqualTo(full.fullDefinitionId());
        assertThat(state.scheduleHash()).isEqualTo(full.scheduleHash());
        assertThat(scenario.store.outboxEvents()).hasSize(1);
        ScheduleRegistrationOutboxEvent event = scenario.store.outboxEvents().get(0);
        assertThat(event.fullDefinitionId()).isEqualTo(full.fullDefinitionId());
        assertThat(event.publicationRevision()).isEqualTo(1L);
        assertThat(event.topic()).isEqualTo(FullPublishVersions.OUTBOX_TOPIC);

        // No Execution, Participant, enrollment or provider side effect exists anywhere.
        assertThat(scenario.store.findCommittedOccurrences(scenario.campaignId())).isEmpty();
    }

    @Test
    void theFullPublicationTransactionRecordsBothBoundedAuditEvents() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();

        List<LifecycleAction> applied = scenario.base.audit.events().stream()
                .filter(event -> event.disposition().mutated())
                .map(LifecycleAuditEvent::action)
                .toList();
        assertThat(applied).contains(LifecycleAction.PREPARE_PUBLISH, LifecycleAction.PUBLISH,
                LifecycleAction.FULL_PUBLISH);
        assertThat(scenario.base.audit.events().stream()
                .filter(event -> event.action() == LifecycleAction.FULL_PUBLISH)
                .filter(event -> event.reasonCode().equals(FullPublishService.FULL_PUBLISHED_REASON)))
                .hasSize(1);
    }

    @Test
    void anExactRetryReturnsTheOriginalReceiptAndWritesNothing() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();
        FullPublishResult first = scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes());

        FullPublishResult retry = scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes());

        assertThat(retry.fullIdentity()).isEqualTo(first.fullIdentity());
        assertThat(retry.publishedAt()).isEqualTo(first.publishedAt());
        assertThat(scenario.store.fullDefinitionCount()).isEqualTo(1);
        assertThat(scenario.store.fullReceiptCount()).isEqualTo(1);
        assertThat(scenario.store.outboxEvents()).hasSize(1);
        assertThat(scenario.base.store.publishReceiptCount()).isEqualTo(1);
    }

    @Test
    void aReusedKeyWithAChangedRequestIsAConflict() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "idem-1", preparation.requiredRiskCodes());

        List<String> changed = List.of(preparation.requiredRiskCodes().get(0));

        LifecycleException failure = failureOf(() -> scenario.confirm(preparation, "idem-1", changed));

        assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PUBLISH_CONFLICT);
        assertThat(scenario.store.fullDefinitionCount()).isEqualTo(1);
    }

    @Test
    void aConsumedPreparationIsRefusedForAnotherKeyOrAnotherPreparation() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "idem-1", preparation.requiredRiskCodes());

        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-2", preparation.requiredRiskCodes())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_CONSUMED);
        assertThat(scenario.store.fullDefinitionCount()).isEqualTo(1);
    }

    @Test
    void publishingANewerVersionAtomicallySupersedesTheOlderActiveSchedule() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        FullPublishResult first = scenario.publish();
        CampaignScheduleState firstState = scenario.activeSchedule();

        PrepareFullPublishResult secondFull = fullPreparationOf(scenario, basePreparationOf(scenario));
        FullPublishResult second = scenario.confirm(
                secondFull, "idem-2", secondFull.requiredRiskCodes());

        assertThat(second.publicationRevision()).isEqualTo(2L);
        CampaignScheduleState secondState = scenario.activeSchedule();
        assertThat(secondState.stateRevision()).isEqualTo(firstState.stateRevision() + 1L);
        assertThat(secondState.fullDefinitionId()).isEqualTo(second.fullDefinitionId());
        assertThat(secondState.fullDefinitionId()).isNotEqualTo(first.fullDefinitionId());
        assertThat(scenario.base.store.findLatestPublished(scenario.campaignId()).orElseThrow()
                .publishedRevision()).isEqualTo(2L);
        assertThat(scenario.store.outboxEvents()).hasSize(2);
    }

    // ------------------------------------------------------------------ rejections

    @Test
    void anAbsentCrossOwnerOrForeignPreparationSharesOneNotFoundFailure() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();
        FullPublishService service = scenario.publishService();
        FullPublishCommand command = scenario.command(
                preparation, "idem-1", preparation.requiredRiskCodes());

        assertThatThrownBy(() -> service.confirm(command, PublishTestFixtures.otherTenantContext()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(command, PublishTestFixtures.otherUserContext()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(
                new FullPublishCommand(
                        "missing-preparation-1", FullPublishTestFixtures.hash("x"), "idem-1",
                        preparation.requiredRiskCodes()),
                scenario.context))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aHashPermissionOrDataScopeFailureIsRefusedBeforeAnyReplay() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();
        FullPublishService service = scenario.publishService();

        assertThat(failureOf(() -> service.confirm(new FullPublishCommand(
                preparation.preparationId(), FullPublishTestFixtures.hash("wrong"), "idem-1",
                preparation.requiredRiskCodes()), scenario.context)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_MISMATCH);

        assertThatThrownBy(() -> service.confirm(scenario.command(
                        preparation, "idem-1", preparation.requiredRiskCodes()),
                PublishTestFixtures.contextWithoutPublish()))
                .isInstanceOf(PermissionDeniedException.class);

        assertThat(failureOf(() -> service.confirm(
                scenario.command(preparation, "idem-1", preparation.requiredRiskCodes()),
                PublishTestFixtures.otherDataScopeContext())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE);
    }

    @Test
    void aStructurallyInvalidCommandOrMissingContextIsRefused() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();

        assertThat(failureOf(() -> scenario.publishService().confirm(null, scenario.context)).code())
                .isEqualTo(FullPublishErrorCodes.FULL_COMMAND_INVALID);
        assertThat(failureOf(() -> scenario.publishService()
                .confirm(scenario.command(preparation, "idem-1", preparation.requiredRiskCodes()), null)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID);
        assertThat(failureOf(() -> scenario.confirm(preparation, " ", preparation.requiredRiskCodes())).code())
                .isEqualTo(FullPublishErrorCodes.FULL_COMMAND_INVALID);
    }

    @Test
    void theConfirmationsMustBeExactlyTheFrozenRisksAndNeverRepeat() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();

        assertThat(failureOf(() -> scenario.confirm(preparation, "idem-1", List.of())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH);
        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-1", List.of("RUNTIME_UNAVAILABLE"))).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH);
        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-1", List.of("RUNTIME_UNAVAILABLE", "RUNTIME_UNAVAILABLE",
                        "SCHEDULE_WORK_DEFERRED"))).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH);
        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-1", List.of("RUNTIME_UNAVAILABLE", "not a machine code"))).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH);
        assertThat(scenario.store.fullDefinitionCount()).isZero();
    }

    @Test
    void anExpiredPreparationIsRefused() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();

        scenario.withClock(java.time.Clock.fixed(
                preparation.expiresAt().plusSeconds(1), java.time.ZoneOffset.UTC));

        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_EXPIRED);
        assertThat(scenario.store.fullDefinitionCount()).isZero();
    }

    @Test
    void aMovedDraftMakesThePreparationStale() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();

        CampaignPlan edit = FullPublishTestFixtures.editSchedulePlan(
                scenario.draft(), Map.of(
                        ScheduleBasicVersions.FIELD_FORM, "ONCE",
                        ScheduleBasicVersions.FIELD_ZONE_ID, FullPublishTestFixtures.ZONE,
                        ScheduleBasicVersions.FIELD_RUN_AT, "2026-11-01T09:00:00+08:00"));
        scenario.base.plans.store(edit);
        scenario.base.applyService().apply(new ApplyCommand(edit.planId(), edit.planHash()),
                scenario.context);

        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE);
        assertThat(scenario.store.fullDefinitionCount()).isZero();
        assertThat(scenario.base.store.isPreparationConsumed(scenario.basePreparation.preparationId()))
                .isFalse();
    }

    @Test
    void aSegmentGateThatNoLongerActivatesThePlanMakesTheConfirmationFailClosed() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();

        scenario.withReadiness(PublishTestFixtures.experimentalProjection());
        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-gate", preparation.requiredRiskCodes())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED);

        scenario.withReadiness(PublishTestFixtures.missingReadiness());
        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-gate-2", preparation.requiredRiskCodes())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_REJECTED);

        scenario.withReadiness(PublishTestFixtures.failingReadiness());
        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-gate-3", preparation.requiredRiskCodes())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_ACTIVATION_UNAVAILABLE);

        assertThat(scenario.store.fullDefinitionCount()).isZero();
    }

    @Test
    void aFullPublicationNeverExposesAHostOnlyMutationThroughAModelTool() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        assertThat(scenario.publish().fullDefinitionId()).isNotBlank();
        assertThat(FullPublishCommand.class.getRecordComponents()).hasSize(4);
    }
}