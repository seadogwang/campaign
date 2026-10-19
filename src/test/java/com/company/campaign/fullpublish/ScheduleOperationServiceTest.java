package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.PreparePublishResult;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Focused T21 durable scheduling tests: due work, idempotency, pause/resume, supersession and CAS. */
class ScheduleOperationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:05:00Z");
    private static final Instant LATER = Instant.parse("2026-10-03T00:10:00Z");

    private static LifecycleException failureOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, LifecycleException.class);
    }

    private static FullPublishScenario dailyPublished() {
        FullPublishScenario scenario = FullPublishScenario
                .daily("2026-09-29", "2026-10-01", "06:00")
                .withClock(LifecycleTestFixtures.clockAt(LATER));
        scenario.publish();
        return scenario;
    }

    // ------------------------------------------------------------------ due work

    @Test
    void anImmediateScheduleCommitsExactlyOneDueOccurrence() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();

        ScheduleDueResult result = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, NOW), scenario.context);

        assertThat(result.dueExecutionKeys()).hasSize(1);
        assertThat(result.skippedExecutionKeys()).isEmpty();
        assertThat(result.stateRevision()).isEqualTo(1L);

        List<ScheduledOccurrenceRecord> committed =
                scenario.store.findCommittedOccurrences(scenario.campaignId());
        assertThat(committed).hasSize(1);
        assertThat(committed.get(0).decision()).isEqualTo(OccurrenceDecision.DUE);
        assertThat(committed.get(0).fullDefinitionId())
                .isEqualTo(scenario.activeSchedule().fullDefinitionId());
        assertThat(committed.get(0).campaignId()).isEqualTo(scenario.campaignId());
    }

    @Test
    void planningTwiceCommitsNoDuplicateOccurrenceWork() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();

        ScheduleDueResult first = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, NOW), scenario.context);
        ScheduleDueResult second = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, NOW), scenario.context);

        assertThat(first.dueExecutionKeys()).hasSize(1);
        assertThat(second.dueExecutionKeys()).isEmpty();
        assertThat(second.alreadyCommittedExecutionKeys()).containsExactlyElementsOf(first.dueExecutionKeys());
        assertThat(scenario.store.findCommittedOccurrences(scenario.campaignId())).hasSize(1);
    }

    @Test
    void aDailyScheduleCatchesUpToTheMostRecentDateAndSkipsTheOlderMissedOnes() {
        FullPublishScenario scenario = dailyPublished();

        ScheduleDueResult result = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, LATER), scenario.context);

        assertThat(result.dueExecutionKeys()).hasSize(1);
        assertThat(result.skippedExecutionKeys()).hasSize(2);
        List<ScheduledOccurrenceRecord> committed =
                scenario.store.findCommittedOccurrences(scenario.campaignId());
        assertThat(committed).hasSize(3);
        assertThat(committed.stream().filter(record -> record.decision().due()).toList())
                .singleElement()
                .satisfies(record -> assertThat(record.localDate()).isEqualTo("2026-10-01"));
        assertThat(committed.stream()
                .filter(record -> !record.decision().due())
                .allMatch(record -> record.localDate().compareTo("2026-10-01") < 0))
                .isTrue();
    }

    @Test
    void aFutureEvaluationInstantIsRefused() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();

        assertThat(failureOf(() -> scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, NOW.plusSeconds(3600)),
                scenario.context)).code())
                .isEqualTo(FullPublishErrorCodes.FULL_COMMAND_INVALID);
        assertThat(scenario.store.findCommittedOccurrences(scenario.campaignId())).isEmpty();
    }

    // ------------------------------------------------------------------ pause and resume

    @Test
    void pausingBlocksNewDueWorkAndIsItselfIdempotent() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();

        ScheduleStateResult paused = scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 1L), scenario.context);
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.stateRevision()).isEqualTo(2L);

        ScheduleStateResult again = scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 2L), scenario.context);
        assertThat(again.stateRevision()).isEqualTo(2L);

        assertThat(failureOf(() -> scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 2L, NOW), scenario.context)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_SCHEDULE_PAUSED);
        assertThat(scenario.store.findCommittedOccurrences(scenario.campaignId())).isEmpty();
    }

    @Test
    void resumingAppliesTheMostRecentDueCatchUpRatherThanReplayingEveryMissedDate() {
        FullPublishScenario scenario = dailyPublished();

        scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 1L), scenario.context);
        ScheduleStateResult resumed = scenario.operationService().resume(
                new ScheduleStateCommand(scenario.campaignId(), 2L), scenario.context);
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        assertThat(resumed.stateRevision()).isEqualTo(3L);

        ScheduleDueResult result = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 3L, LATER), scenario.context);

        assertThat(result.dueExecutionKeys()).hasSize(1);
        assertThat(result.skippedExecutionKeys()).hasSize(2);
    }

    @Test
    void aStaleStateRevisionIsRefusedWithAControlledConflict() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();

        assertThat(failureOf(() -> scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 7L), scenario.context)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT);
        assertThat(failureOf(() -> scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 7L, NOW), scenario.context)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT);
    }

    // ------------------------------------------------------------------ supersession

    @Test
    void aNewerPublicationSupersedesTheActiveScheduleAndPreservesOlderCommittedWork() {
        FullPublishScenario scenario = FullPublishScenario.daily("2026-09-29", "2026-10-01", "06:00")
                .withClock(LifecycleTestFixtures.clockAt(LATER));
        FullPublishResult first = scenario.publish();
        scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, LATER), scenario.context);
        List<ScheduledOccurrenceRecord> firstCommitted =
                scenario.store.findCommittedOccurrences(scenario.campaignId());
        assertThat(firstCommitted).hasSize(3);

        PreparePublishResult base = scenario.base.prepareService()
                .prepare(scenario.base.prepareCommand(), scenario.context);
        PrepareFullPublishResult second = scenario.prepareService().prepare(
                new PrepareFullPublishCommand(base.preparationId(), base.preparationHash()),
                scenario.context);
        FullPublishResult published = scenario.confirm(
                second, "idem-2", second.requiredRiskCodes());

        CampaignScheduleState state = scenario.activeSchedule();
        assertThat(state.fullDefinitionId()).isEqualTo(published.fullDefinitionId());
        assertThat(state.stateRevision()).isEqualTo(2L);

        // The older committed work is untouched and still bound to the older frozen version.
        List<ScheduledOccurrenceRecord> after = scenario.store.findCommittedOccurrences(scenario.campaignId());
        assertThat(after).containsExactlyInAnyOrderElementsOf(firstCommitted);
        assertThat(after).allSatisfy(record ->
                assertThat(record.fullDefinitionId()).isEqualTo(first.fullDefinitionId()));

        // Planning now plans against the new version only.
        ScheduleDueResult result = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 2L, LATER), scenario.context);
        assertThat(result.dueExecutionKeys()).hasSize(1);
        assertThat(scenario.store.findCommittedOccurrences(scenario.campaignId()).stream()
                .filter(record -> record.fullDefinitionId().equals(published.fullDefinitionId()))
                .toList()).hasSize(3);
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void schedulingRequiresCampaignOperateAndTheExactOwner() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();

        var withoutOperate = SegmentValidationFixtures.context(
                EnumSet.complementOf(EnumSet.of(CampaignPermission.CAMPAIGN_OPERATE)));

        assertThatThrownBy(() -> scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 1L), withoutOperate))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, NOW), withoutOperate))
                .isInstanceOf(PermissionDeniedException.class);

        assertThatThrownBy(() -> scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 1L),
                com.company.campaign.lifecycle.testsupport.PublishTestFixtures.otherTenantContext()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> scenario.operationService().pause(
                new ScheduleStateCommand("campaign-unknown-1", 1L), scenario.context))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(failureOf(() -> scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 1L), null)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID);
    }
}