package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.EventSelectorProjectionFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillErrorCodes;
import com.company.campaign.builder.skill.SegmentSkillException;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.builder.tool.SegmentToolErrorCodes;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewBackendException;
import com.company.campaign.preview.testsupport.StubPreviewBackend;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 1 of the corrective task: the admitted Tool handler is the real execution boundary, so a Skill
 * that removes, reorders or mis-versions a protected Tool prevents the protected implementation from running
 * at all; a denied or failed Metadata discovery prevents the model port from receiving any projection; and a
 * failing or incoherent protected implementation produces exactly one bounded failure and no later stage.
 */
class SegmentAgentToolBoundaryTest {

    private static final List<String> REQUIRED_FLOW = List.of(
            SegmentToolIds.METADATA_DISCOVER,
            SegmentToolIds.INTENT_RESOLVE,
            SegmentToolIds.DSL_VALIDATE,
            SegmentToolIds.PLAN_BUILD,
            SegmentToolIds.PREVIEW_SAFE,
            SegmentToolIds.EXPLANATION_GROUNDED);

    private static SegmentSkillDefinition withoutTool(String toolId) {
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>();
        int rank = 0;
        for (SegmentSkillDefinition.ToolPin pin : AgentTestFixtures.committedToolPins()) {
            if (!pin.toolId().equals(toolId)) {
                pins.add(AgentTestFixtures.pin(pin.toolId(), rank++));
            }
        }
        return AgentTestFixtures.skillWithTools(pins);
    }

    private static SegmentSkillDefinition withSwappedTools(String first, String second) {
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>(AgentTestFixtures.committedToolPins());
        int firstIndex = -1;
        int secondIndex = -1;
        for (int index = 0; index < pins.size(); index++) {
            if (pins.get(index).toolId().equals(first)) {
                firstIndex = index;
            }
            if (pins.get(index).toolId().equals(second)) {
                secondIndex = index;
            }
        }
        String firstId = pins.get(firstIndex).toolId();
        String secondId = pins.get(secondIndex).toolId();
        List<SegmentSkillDefinition.ToolPin> swapped = new ArrayList<>(pins);
        swapped.set(firstIndex, AgentTestFixtures.pin(secondId, firstIndex));
        swapped.set(secondIndex, AgentTestFixtures.pin(firstId, secondIndex));
        return AgentTestFixtures.skillWithTools(swapped);
    }

    private static StubPreviewBackend countingBackend() {
        return new StubPreviewBackend((plan, runContext) -> StubPreviewBackend.coherent(
                plan, runContext, AgentTestFixtures.MEMBERS, "preview-stub-backend",
                StubPreviewBackend.DEFAULT_VERSION));
    }

    private static void assertIncompatible(SegmentSkillDefinition skill, StubPreviewBackend backend,
            SliceTestFixtures.StubModelPort modelPort, AgentTestFixtures.CountingAuthorization authorization) {
        assertThatThrownBy(() -> AgentTestFixtures.environment(
                        skill,
                        modelPort,
                        AgentTestFixtures.snapshot(),
                        AgentTestFixtures.context(),
                        authorization,
                        AgentTestFixtures.previewPortWithBackend(
                                AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                                SegmentValidationFixtures.allowAll(), backend),
                        null,
                        null))
                .isInstanceOf(SegmentAgentException.class)
                .extracting(failure -> ((SegmentAgentException) failure).code())
                .isEqualTo(SegmentAgentErrorCodes.SKILL_INCOMPATIBLE);
        assertThat(backend.callCount()).isZero();
        assertThat(modelPort.invocations()).isZero();
        assertThat(authorization.calls()).isZero();
    }

    // ------------------------------------------------------------------ Preview ownership

    @Test
    void removingPreviewSafeFromTheSkillPreventsThePreviewBackendFromBeingCalled() {
        StubPreviewBackend backend = countingBackend();
        assertIncompatible(
                withoutTool(SegmentToolIds.PREVIEW_SAFE),
                backend,
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll()));
    }

    @Test
    void reorderingPreviewSafeInTheSkillIsRefusedBeforeAnyExecution() {
        StubPreviewBackend backend = countingBackend();
        assertIncompatible(
                withSwappedTools(SegmentToolIds.PREVIEW_SAFE, SegmentToolIds.EXPLANATION_GROUNDED),
                backend,
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll()));
    }

    @Test
    void aWrongVersionPreviewSafePinCannotEvenBeConstructed() {
        StubPreviewBackend backend = countingBackend();
        SliceTestFixtures.StubModelPort modelPort = AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson());

        assertThatThrownBy(() -> AgentTestFixtures.skillWithToolVersion(SegmentToolIds.PREVIEW_SAFE, "9.9"))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(SegmentSkillErrorCodes.TOOL_VERSION_UNKNOWN);
        assertThat(backend.callCount()).isZero();
        assertThat(modelPort.invocations()).isZero();
    }

    // ------------------------------------------------------------------ planning ownership

    @Test
    void removingPlanBuildFromTheSkillPreventsThePlannerFromRunning() {
        StubPreviewBackend backend = countingBackend();
        assertIncompatible(
                withoutTool(SegmentToolIds.PLAN_BUILD),
                backend,
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll()));
    }

    @Test
    void reorderingPlanBuildInTheSkillIsRefusedBeforeAnyExecution() {
        StubPreviewBackend backend = countingBackend();
        assertIncompatible(
                withSwappedTools(SegmentToolIds.PLAN_BUILD, SegmentToolIds.DSL_VALIDATE),
                backend,
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll()));
    }

    // ------------------------------------------------------------------ discovery authorization ordering

    @Test
    void deniedMetadataDiscoveryStopsBeforeTheModelPortAndSendsNoProjection() {
        SliceTestFixtures.StubModelPort modelPort = AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson());
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                modelPort,
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                SegmentValidationFixtures.denyAll(),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("metadataDiscoveryFailure"));
        assertThat(modelPort.invocations()).isZero();
        assertThat(modelPort.lastRequest()).isNull();
        assertThat(result.stateTrace()).extracting(AgentTransition::toState).containsExactly(
                SegmentAgentState.UNDERSTANDING, SegmentAgentState.DISCOVERING, SegmentAgentState.DENIED);
        assertThat(result.toolTrace()).singleElement().satisfies(call -> {
            assertThat(call.toolId()).isEqualTo(SegmentToolIds.METADATA_DISCOVER);
            assertThat(call.disposition()).isEqualTo(Disposition.DENIED);
            assertThat(call.inputFingerprint()).isNotNull();
        });
        assertThat(result.previewCard()).isNull();
        assertThat(result.dslEditableHash()).isNull();
    }

    @Test
    void aFailedMetadataDiscoveryStopsBeforeTheModelPort() {
        SliceTestFixtures.StubModelPort modelPort = AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson());
        MetadataSnapshotRepository throwing = new MetadataSnapshotRepository() {
            @Override
            public void publish(MetadataSnapshot snapshot) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<MetadataSnapshot> findById(String snapshotId) {
                throw new IllegalStateException("repository unavailable with secret-like text");
            }

            @Override
            public Optional<MetadataSnapshot> findByPin(
                    String snapshotId, String snapshotVersion, com.company.campaign.shared.canonical.ContentHash hash) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<MetadataSnapshot> findByContentHash(
                    com.company.campaign.shared.canonical.ContentHash hash) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<MetadataSnapshot> retained() {
                throw new UnsupportedOperationException();
            }
        };
        SegmentAgentEnvironment environment = SegmentAgentEnvironment.of(
                AgentTestFixtures.skill(),
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.of(
                        AgentTestFixtures.context()),
                runContext -> AgentTestFixtures.snapshot(),
                new MetadataQueryService(SegmentValidationFixtures.allowAll(), throwing),
                SegmentValidationFixtures.allowAll(),
                modelPort,
                SliceTestFixtures.policy(),
                SliceTestFixtures.registry(),
                SliceTestFixtures.validationPolicy(),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("metadataDiscoveryFailure"));
        assertThat(modelPort.invocations()).isZero();
        assertThat(modelPort.lastRequest()).isNull();
        assertThat(result.toString()).doesNotContain("secret-like");
    }

    @Test
    void theModelProjectionCarriesOnlyTheDefinitionsDiscoveryAuthorized() {
        // The first three Metadata decisions permit member.code, member.tier and order.count only, so the
        // bounded safe model request must project exactly those three and never the denied metric or relation.
        SliceTestFixtures.StubModelPort modelPort = AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson());
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                modelPort,
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                com.company.campaign.preview.testsupport.PreviewAuthorizationStub.allowFirstMetadata(3),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(modelPort.invocations()).isEqualTo(1);
        com.company.campaign.builder.segment.SegmentModelRequest request = modelPort.lastRequest();
        assertThat(request).isNotNull();
        assertThat(request.permittedDefinitions())
                .extracting(com.company.campaign.builder.segment.SegmentModelRequest.MetadataProjection::stableId)
                .containsExactlyInAnyOrder("member.code", "member.tier", "order.count")
                .doesNotContain("order.net_paid_amount", "member.orders");
        // The denied definition was never disclosed, and the run then stopped at the genuinely denied T07
        // re-authorization rather than continuing on an unauthorized projection.
        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.previewCard()).isNull();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.PREVIEW_SAFE, SegmentToolIds.EXPLANATION_GROUNDED);
    }

    @Test
    void theModelPortReceivesNoEventSelectorWhoseDependenciesDiscoveryDidNotAdmit() {
        // Discovery denies exactly the Attribute that is a scope-filter dependency of the first selector, while
        // the relation and every other dependency stays admitted. The whole selector must disappear; the second
        // selector, whose own dependencies are all admitted, must still reach the model.
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.twoSelectorSnapshot();
        com.company.campaign.builder.segment.SegmentModelRequest request = modelRequestFor(
                snapshot, denyingAttribute(EventSelectorProjectionFixtures.ORDER_CHANNEL));

        assertThat(request.eventSelectors())
                .extracting(com.company.campaign.builder.segment.SegmentModelRequest.EventSelectorProjection::stableId)
                .containsExactly(EventSelectorProjectionFixtures.LATEST_ORDER_SELECTOR)
                .doesNotContain(EventSelectorProjectionFixtures.FIRST_ORDER_SELECTOR);
        assertThat(request.eventSelectors().toString())
                .doesNotContain(EventSelectorProjectionFixtures.FIRST_ORDER_SELECTOR, "First Order", "首单");
    }

    @Test
    void theModelPortReceivesEveryEventSelectorWhoseDependenciesDiscoveryAdmitted() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.twoSelectorSnapshot();
        com.company.campaign.builder.segment.SegmentModelRequest request = modelRequestFor(
                snapshot, SegmentValidationFixtures.allowAll());

        assertThat(request.eventSelectors())
                .extracting(com.company.campaign.builder.segment.SegmentModelRequest.EventSelectorProjection::stableId)
                .containsExactlyInAnyOrder(EventSelectorProjectionFixtures.FIRST_ORDER_SELECTOR,
                        EventSelectorProjectionFixtures.LATEST_ORDER_SELECTOR);
    }

    @Test
    void theModelPortReceivesNoSelectorWhoseIdentityAttributeDiscoveryDenied() {
        // The identity Attribute is not an ordering Attribute of this selector, so only the complete dependency
        // closure can detect the denial.
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.closureSelectorSnapshot();
        com.company.campaign.builder.segment.SegmentModelRequest request = modelRequestFor(
                snapshot, denyingAttribute(EventSelectorProjectionFixtures.ORDER_IDENTITY_KEY));

        assertThat(request.eventSelectors()).isEmpty();
    }

    @Test
    void theModelPortReceivesNoSelectorWhoseFixedFilterKeyDiscoveryDenied() {
        // The fixed-filter key order.status is referenced by no other dependency class of this selector.
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.closureSelectorSnapshot();
        com.company.campaign.builder.segment.SegmentModelRequest request = modelRequestFor(
                snapshot, denyingAttribute(EventSelectorProjectionFixtures.ORDER_STATUS));

        assertThat(request.eventSelectors()).isEmpty();
    }

    @Test
    void theModelPortReceivesTheSelectorWhoseCompleteDependencyClosureDiscoveryAdmitted() {
        MetadataSnapshot snapshot = EventSelectorProjectionFixtures.closureSelectorSnapshot();
        com.company.campaign.builder.segment.SegmentModelRequest request = modelRequestFor(
                snapshot, SegmentValidationFixtures.allowAll());

        assertThat(request.eventSelectors())
                .extracting(com.company.campaign.builder.segment.SegmentModelRequest.EventSelectorProjection::stableId)
                .containsExactly(EventSelectorProjectionFixtures.CLOSURE_SELECTOR);
    }

    private static com.company.campaign.builder.segment.SegmentModelRequest modelRequestFor(
            MetadataSnapshot snapshot,
            com.company.campaign.security.authorization.AuthorizationService authorizationService) {
        SliceTestFixtures.StubModelPort modelPort = AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson());
        SegmentAgentEnvironment environment = SegmentAgentEnvironment.of(
                AgentTestFixtures.skill(),
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.of(
                        AgentTestFixtures.context()),
                runContext -> snapshot,
                new MetadataQueryService(authorizationService, AgentTestFixtures.repository(snapshot)),
                authorizationService,
                modelPort,
                SliceTestFixtures.policy(),
                SliceTestFixtures.registry(),
                SliceTestFixtures.validationPolicy(),
                AgentTestFixtures.previewPort(
                        snapshot, AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result).isNotNull();
        assertThat(modelPort.invocations()).isEqualTo(1);
        assertThat(modelPort.lastRequest()).isNotNull();
        return modelPort.lastRequest();
    }

    /** Denies exactly one Attribute definition resource and delegates every other decision. */
    private static com.company.campaign.security.authorization.AuthorizationService denyingAttribute(
            String deniedDefinitionId) {
        com.company.campaign.security.authorization.AuthorizationService delegate =
                SegmentValidationFixtures.allowAll();
        return request -> {
            com.company.campaign.security.authorization.OwnedResourceRef resource =
                    request == null ? null : request.resource();
            if (resource != null
                    && resource.kind() == com.company.campaign.security.authorization.ResourceKind.ATTRIBUTE
                    && deniedDefinitionId.equals(resource.resourceId())) {
                return com.company.campaign.security.authorization.AuthorizationDecision.deny(
                        com.company.campaign.security.authorization.AuthorizationDisposition.PERMISSION_DENIED,
                        com.company.campaign.security.authorization.AuthorizationReason.MISSING_PERMISSION,
                        resource.kind(),
                        com.company.campaign.security.context.CampaignPermission.CAMPAIGN_READ);
            }
            return delegate.authorize(request);
        };
    }

    // ------------------------------------------------------------------ bounded handler failures

    @Test
    void aThrowingPreviewImplementationProducesOneBoundedFailureAndNoLaterStage() {
        StubPreviewBackend failing = new StubPreviewBackend((plan, runContext) -> {
            throw new PreviewBackendException(PreviewBackendException.Kind.UNAVAILABLE);
        });
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson())).withPreviewPort(
                AgentTestFixtures.previewPortWithBackend(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                        SegmentValidationFixtures.allowAll(), failing));

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(AgentTestFixtures.skill().failureCode("previewFailure"));
        assertThat(result.toolTrace()).filteredOn(call -> !call.disposition().isResolved()).hasSize(1);
        assertThat(result.toolTrace()).filteredOn(call -> SegmentToolIds.PREVIEW_SAFE.equals(call.toolId()))
                .singleElement()
                .satisfies(call -> assertThat(call.code())
                        .isEqualTo(SegmentToolErrorCodes.TOOL_EXECUTION_FAILED));
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.EXPLANATION_GROUNDED);
        assertThat(failing.callCount()).isEqualTo(1);
        assertThat(result.previewCard()).isNull();
        assertThat(result.explanation()).isNull();
    }

    @Test
    void aPreviewPortReturningASampleIsOneBoundedFailureAndNoLaterStage() {
        com.company.campaign.preview.PreviewResponse withSample = new com.company.campaign.preview.PreviewResponse(
                "OK",
                "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                3,
                null,
                List.of("sha256:" + "4".repeat(64)),
                new com.company.campaign.preview.PreviewExecutionContext(
                        AgentTestFixtures.snapshot().snapshotId(), "2026-01-01T00:00:00Z", "UTC", "wm-1"));
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.previewPortReturning(withSample),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.toolTrace()).filteredOn(call -> !call.disposition().isResolved()).hasSize(1);
        assertThat(result.toolTrace()).filteredOn(call -> SegmentToolIds.PREVIEW_SAFE.equals(call.toolId()))
                .singleElement()
                .satisfies(call -> assertThat(call.code())
                        .isEqualTo(SegmentToolErrorCodes.TOOL_SAMPLE_INCOHERENT));
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.EXPLANATION_GROUNDED);
        assertThat(result.previewCard()).isNull();
    }

    // ------------------------------------------------------------------ ownership and determinism

    @Test
    void everyProtectedStageRunsOnlyInsideItsAdmittedToolCall() {
        AgentTestFixtures.CountingAuthorization authorization =
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll());
        AgentTestFixtures.RecordingPreviewPort preview = new AgentTestFixtures.RecordingPreviewPort(
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()));
        SliceTestFixtures.StubModelPort modelPort = AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson());
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.skill(),
                modelPort,
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                authorization,
                preview,
                null,
                null);

        SegmentAgentResult first = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));
        SegmentAgentResult second = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(first.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(first.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactlyElementsOf(REQUIRED_FLOW);
        assertThat(first.toolTrace()).extracting(SegmentToolCallRecord::rank)
                .containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(first.toolTrace()).allSatisfy(
                call -> assertThat(call.disposition()).isEqualTo(Disposition.RESOLVED));
        // The genuine T09C port ran exactly once per run, the model port exactly once per run, and the
        // authorization boundary observed more than discovery alone, which is the validator and planner
        // running inside their admitted Tool calls.
        assertThat(preview.presentations()).hasSize(2);
        assertThat(modelPort.invocations()).isEqualTo(2);
        assertThat(authorization.calls()).isGreaterThan(2 * REQUIRED_FLOW.size());
        assertThat(second.stateTrace()).isEqualTo(first.stateTrace());
        assertThat(second.toolTrace()).isEqualTo(first.toolTrace());
        assertThat(second.canonicalBytes()).isEqualTo(first.canonicalBytes());
    }

    @Test
    void anIdenticalRunReplaysByteIdenticallyThroughTheToolBoundary() {
        byte[] expected = AgentTestFixtures.run(
                AgentTestFixtures.environment(AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson())),
                new SegmentAgentRequest("Gold会员")).canonicalBytes();

        for (int iteration = 0; iteration < 100; iteration++) {
            SegmentAgentResult replay = AgentTestFixtures.run(
                    AgentTestFixtures.environment(AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson())),
                    new SegmentAgentRequest("Gold会员"));
            assertThat(replay.canonicalBytes()).isEqualTo(expected);
        }
    }

    @Test
    void thePreviewBackendIsOnlyReachableThroughTheAdmittedPreviewCall() {
        StubPreviewBackend backend = countingBackend();
        SegmentAgentEnvironment environment = AgentTestFixtures.environment(
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.previewPortWithBackend(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(),
                        SegmentValidationFixtures.allowAll(), backend),
                null,
                null);

        SegmentAgentResult result = AgentTestFixtures.run(environment, new SegmentAgentRequest("Gold会员"));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactlyElementsOf(REQUIRED_FLOW);
        assertThat(backend.callCount()).isEqualTo(1);
    }
}