package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleDisposition;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Focused T21 full-preparation tests: binding, ownership, fail-closed ports and the frozen risk set. */
class FullPublishPreparationServiceTest {

    private static LifecycleException failureOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, LifecycleException.class);
    }

    // ------------------------------------------------------------------ the accepted path

    @Test
    void aFullPreparationBindsTheBasePreparationJourneyScheduleAndRuntimePin() {
        FullPublishScenario scenario = FullPublishScenario.immediate();

        PrepareFullPublishResult preparation = scenario.prepare();

        assertThat(preparation.preparationId()).isNotBlank();
        assertThat(preparation.basePreparationHash()).isEqualTo(scenario.basePreparation.preparationHash());
        assertThat(preparation.scheduleForm()).isEqualTo("IMMEDIATE");
        assertThat(preparation.scheduleZoneId()).isEqualTo("Asia/Shanghai");
        assertThat(preparation.runtimeAvailabilityState()).isEqualTo("AVAILABLE");
        assertThat(preparation.requiredRiskCodes()).isEqualTo(FullPublishTestFixtures.FULL_RISKS);
        assertThat(preparation.createdAt()).isEqualTo(scenario.clock().instant());
        assertThat(preparation.expiresAt())
                .isEqualTo(scenario.clock().instant().plus(FullPublishVersions.DEFAULT_PREPARATION_LIFETIME));
        assertThat(scenario.store.fullPreparationCount()).isEqualTo(1);
    }

    @Test
    void theLatentPreparationFreezesExactlyTheCompiledJourneyAndSchedule() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        PrepareFullPublishResult preparation = scenario.prepare();

        FullPublishPreparation stored =
                scenario.store.findFullPreparation(preparation.preparationId()).orElseThrow();

        assertThat(stored.journey().sourceManifestHash()).isEqualTo(scenario.draft().manifestHash());
        assertThat(stored.journeyHash()).isEqualTo(stored.journey().contentHash());
        assertThat(stored.schedule().sourceManifestHash()).isEqualTo(scenario.draft().manifestHash());
        assertThat(stored.schedule().journeyHash()).isEqualTo(stored.journey().contentHash());
        assertThat(stored.scheduleHash()).isEqualTo(stored.schedule().scheduleHash());
        assertThat(stored.runtimeAvailability().available()).isTrue();
        assertThat(stored.tenantId()).isEqualTo(scenario.context.tenantId());
        assertThat(stored.campaignId()).isEqualTo(scenario.campaignId());
    }

    @Test
    void preparingRecordsExactlyOneFullPublicationAuditEvent() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.prepare();

        List<LifecycleAuditEvent> applied = scenario.base.audit.events().stream()
                .filter(event -> event.action() == LifecycleAction.FULL_PUBLISH)
                .filter(event -> event.disposition() == LifecycleDisposition.APPLIED)
                .toList();
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).reasonCode()).isEqualTo(FullPublishPreparationService.PREPARED_REASON);
    }

    @Test
    void preparingTwiceYieldsTwoIndependentPreparationsOverAnUnchangedBase() {
        FullPublishScenario scenario = FullPublishScenario.immediate();

        PrepareFullPublishResult first = scenario.prepare();
        PrepareFullPublishResult second = scenario.prepare();

        assertThat(second.preparationId()).isNotEqualTo(first.preparationId());
        assertThat(second.journeyHash()).isEqualTo(first.journeyHash());
        assertThat(second.scheduleHash()).isEqualTo(first.scheduleHash());
        assertThat(scenario.store.fullPreparationCount()).isEqualTo(2);
        assertThat(scenario.base.store.isPreparationConsumed(scenario.basePreparation.preparationId()))
                .as("preparing is not authority to publish")
                .isFalse();
    }

    // ------------------------------------------------------------------ non-disclosing rejections

    @Test
    void anAbsentCrossOwnerOrForeignPreparationSharesOneNotFoundFailure() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        FullPublishPreparationService service = scenario.prepareService();

        assertThatThrownBy(() -> service.prepare(
                new PrepareFullPublishCommand("missing-preparation-1", FullPublishTestFixtures.hash("x")),
                scenario.context))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> service.prepare(scenario.prepareCommand(), PublishTestFixtures.otherTenantContext()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.prepare(scenario.prepareCommand(), PublishTestFixtures.otherUserContext()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPresentedHashThatIsNotTheFrozenHashIsRefused() {
        FullPublishScenario scenario = FullPublishScenario.immediate();

        LifecycleException failure = failureOf(() -> scenario.prepareService().prepare(
                new PrepareFullPublishCommand(
                        scenario.basePreparation.preparationId(), FullPublishTestFixtures.hash("wrong")),
                scenario.context));

        assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH);
        assertThat(scenario.store.fullPreparationCount()).isZero();
    }

    @Test
    void aStructurallyInvalidCommandOrMissingContextIsRefused() {
        FullPublishScenario scenario = FullPublishScenario.immediate();

        assertThat(failureOf(() -> scenario.prepareService().prepare(null, scenario.context)).code())
                .isEqualTo(FullPublishErrorCodes.FULL_COMMAND_INVALID);
        assertThat(failureOf(() -> scenario.prepareService().prepare(
                new PrepareFullPublishCommand(" ", FullPublishTestFixtures.hash("x")), scenario.context)).code())
                .isEqualTo(FullPublishErrorCodes.FULL_COMMAND_INVALID);
        assertThat(failureOf(() -> scenario.prepareService()
                .prepare(scenario.prepareCommand(), null)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID);
    }

    @Test
    void preparingRequiresCampaignPublishAndTheFrozenDataScope() {
        FullPublishScenario scenario = FullPublishScenario.immediate();

        assertThatThrownBy(() -> scenario.prepareService()
                .prepare(scenario.prepareCommand(), PublishTestFixtures.contextWithoutPublish()))
                .isInstanceOf(PermissionDeniedException.class);

        LifecycleException failure = failureOf(() -> scenario.prepareService()
                .prepare(scenario.prepareCommand(), PublishTestFixtures.otherDataScopeContext()));
        assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE);
    }

    @Test
    void anExpiredOrConsumedBasePreparationIsRefused() {
        FullPublishScenario scenario = FullPublishScenario.immediate()
                .withClock(java.time.Clock.fixed(
                        Instant.parse("2026-10-03T00:05:00Z").plus(2, ChronoUnit.HOURS),
                        java.time.ZoneOffset.UTC));

        assertThat(failureOf(scenario::prepare).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_EXPIRED);

        FullPublishScenario published = FullPublishScenario.immediate();
        published.publish();
        assertThat(failureOf(published::prepare).code())
                .as("a full publication consumes the base preparation")
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_CONSUMED);
    }

    // ------------------------------------------------------------------ fail-closed host evidence

    @Test
    void aMissingOrFailingJourneySourceFailsClosed() {
        assertThat(failureOf(() -> FullPublishScenario.immediate()
                .withSource(FullPublishTestFixtures.missingSource()).prepare()).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_UNAVAILABLE);
        assertThat(failureOf(() -> FullPublishScenario.immediate()
                .withSource(FullPublishTestFixtures.failingSource()).prepare()).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_UNAVAILABLE);
    }

    @Test
    void aJourneyThatDoesNotCompileFailsClosed() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        // A topology with no edge leaves the End node unreachable, so the graph does not compile.
        JourneyTopology broken = JourneyTopology.of(JourneyTopology.TOPOLOGY_FORMAT_VERSION);
        scenario.withTopology(broken);

        assertThat(failureOf(scenario::prepare).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_UNAVAILABLE);
    }

    @Test
    void aJourneyDriftFromTheFrozenManifestIsRefused() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        // A topology with a different edge identity compiles to a different Journey hash.
        JourneyTopology other = JourneyTopology.of(
                JourneyTopology.TOPOLOGY_FORMAT_VERSION,
                new JourneyTopologyEdge(
                        "edge-start-end-other",
                        FullPublishTestFixtures.component(scenario.plan, FlowPackVersions.START).logicalId(),
                        FullPublishTestFixtures.component(scenario.plan, FlowPackVersions.END).logicalId(),
                        com.company.campaign.journey.graph.JourneyBranchKind.DEFAULT));
        PrepareFullPublishResult preparation = scenario.prepare();
        scenario.withTopology(other);

        assertThat(failureOf(() -> scenario.confirm(
                preparation, "idem-x", preparation.requiredRiskCodes())).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE);
    }

    @Test
    void aDisabledOrMovedSchedulePackFailsClosed() {
        assertThat(failureOf(() -> FullPublishScenario.immediate()
                .withSource(FullPublishTestFixtures.sourceWith(
                        FullPublishTestFixtures.immediatePlan(),
                        FullPublishTestFixtures.indexWithDisabledSchedule()))
                .prepare()).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_SCHEDULE_UNAVAILABLE);
        assertThat(failureOf(() -> FullPublishScenario.immediate()
                .withSource(FullPublishTestFixtures.sourceWith(
                        FullPublishTestFixtures.immediatePlan(),
                        FullPublishTestFixtures.indexWithMovedScheduleContract()))
                .prepare()).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_SCHEDULE_UNAVAILABLE);
    }

    @Test
    void anAbsentUnavailableOrMismatchedRuntimeAvailabilityFailsClosed() {
        assertThat(failureOf(() -> FullPublishScenario.immediate()
                .withRuntime(FullPublishTestFixtures.missingRuntime()).prepare()).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_RUNTIME_UNAVAILABLE);
        assertThat(failureOf(() -> FullPublishScenario.immediate()
                .withRuntime(FullPublishTestFixtures.unavailableRuntime()).prepare()).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_RUNTIME_UNAVAILABLE);
        assertThat(failureOf(() -> FullPublishScenario.immediate()
                .withRuntime(FullPublishTestFixtures.failingRuntime()).prepare()).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_RUNTIME_UNAVAILABLE);

        LifecycleException failure = failureOf(() -> {
            FullPublishScenario scenario = FullPublishScenario.immediate();
            PrepareFullPublishResult preparation = scenario.prepare();
            scenario.withRuntime(FullPublishTestFixtures.otherRuntime());
            scenario.confirm(preparation, "idem-runtime", preparation.requiredRiskCodes());
        });
        assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE);
    }

    @Test
    void aRejectedPreparationIsAuditedWithItsBoundedReasonCode() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        failureOf(() -> scenario.prepareService().prepare(
                new PrepareFullPublishCommand(
                        scenario.basePreparation.preparationId(), FullPublishTestFixtures.hash("wrong")),
                scenario.context));

        List<LifecycleAuditEvent> rejected = scenario.base.audit.events().stream()
                .filter(event -> event.action() == LifecycleAction.FULL_PUBLISH)
                .filter(event -> event.disposition() == LifecycleDisposition.REJECTED)
                .toList();
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).reasonCode())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH);
    }

    @Test
    void theTrustedContextIsNeverReadFromTheCommand() {
        List<String> componentNames = java.util.Arrays.stream(PrepareFullPublishCommand.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(componentNames).containsExactly("basePreparationId", "basePreparationHash");
    }

    @Test
    void aFullPreparationNeverAcceptsAForgedTrustedFact() {
        TrustedExecutionContext context = FullPublishScenario.immediate().context;
        assertThat(context).isNotNull();
        assertThat(PrepareFullPublishCommand.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getType().getName())
                        .isIn(String.class.getName(), com.company.campaign.shared.canonical.ContentHash.class.getName()));
    }
}