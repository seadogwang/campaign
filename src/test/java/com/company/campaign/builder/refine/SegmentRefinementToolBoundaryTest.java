package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentToolAdmission;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.builder.tool.SegmentToolErrorCodes;
import com.company.campaign.builder.tool.SegmentToolHandler;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.builder.tool.SegmentToolInvoker;
import com.company.campaign.builder.tool.SegmentToolRunContext;
import com.company.campaign.builder.tool.StandardSegmentToolHandlers;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.ToolEnvelope;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 3 of the task: the admitted Tool handler is the real execution boundary, so a Tool that is absent,
 * stale, out of order, over limit or post-terminal is refused <em>before</em> its Validator, Planner or Preview
 * implementation is entered, and an incompatible Skill is refused before any of them runs at all.
 */
class SegmentRefinementToolBoundaryTest {

    private static final String CALL_PREFIX = "call-";

    private record Harness(
            SegmentToolInvoker invoker,
            RefinementTestFixtures.ToolRunContext run,
            RefinementTestFixtures.CountingPreviewPort port,
            AgentTestFixtures.CountingAuthorization authorization) {

        SegmentToolAdmission call(String toolId, int sequence) {
            return invoker.invoke(new ToolEnvelope(
                    ContractVersions.CURRENT, CALL_PREFIX + sequence, toolId, false, ToolInput.empty()));
        }
    }

    private static Harness harness(SegmentToolCatalog catalog, Map<String, SegmentToolHandler> handlers) {
        AgentTestFixtures.CountingAuthorization authorization =
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll());
        RefinementTestFixtures.CountingPreviewPort port = new RefinementTestFixtures.CountingPreviewPort(
                AgentTestFixtures.previewPort(
                        RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()));
        RefinementTestFixtures.ToolRunContext run = new RefinementTestFixtures.ToolRunContext(
                authorization, port, RefinementTestFixtures.baseDocument());
        return new Harness(
                new SegmentToolInvoker(catalog, handlers, run), run, port, authorization);
    }

    private static Harness harness() {
        return harness(RefinementTestFixtures.catalog(), StandardSegmentToolHandlers.all());
    }

    /** The committed Skill pins, re-ranked after removing one Tool, so the catalog stays well formed. */
    private static SegmentSkillDefinition skillWithout(String toolId) {
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>();
        int rank = 0;
        for (SegmentSkillDefinition.ToolPin pin : AgentTestFixtures.committedToolPins()) {
            if (!pin.toolId().equals(toolId)) {
                pins.add(AgentTestFixtures.pin(pin.toolId(), rank++));
            }
        }
        return AgentTestFixtures.skillWithTools(pins);
    }

    // ------------------------------------------------------------------ admissible path

    @Test
    void theRefinementToolPathIsAdmissibleInTheDeclaredOrder() {
        Harness harness = harness();

        assertThat(harness.call(SegmentToolIds.METADATA_DISCOVER, 1).resolved()).isTrue();
        harness.run.acceptValidation(new com.company.campaign.segment.validation.SegmentValidator(
                        SegmentValidationFixtures.allowAll())
                .validate(RefinementTestFixtures.baseDocument(), RefinementTestFixtures.snapshot(),
                        RefinementTestFixtures.context(), RefinementTestFixtures.validationPolicy()));

        assertThat(harness.call(SegmentToolIds.DSL_VALIDATE, 2).resolved()).isTrue();
        SegmentToolAdmission plan = harness.call(SegmentToolIds.PLAN_BUILD, 3);
        assertThat(plan.resolved()).isTrue();
        harness.run.acceptPlan(plan.result() == null ? null : null);

        assertThat(harness.invoker.callCount()).isEqualTo(3);
        assertThat(harness.invoker.trace()).extracting(
                        com.company.campaign.builder.tool.SegmentToolCallRecord::toolId)
                .containsExactly(
                        SegmentToolIds.METADATA_DISCOVER, SegmentToolIds.DSL_VALIDATE, SegmentToolIds.PLAN_BUILD);
    }

    // ------------------------------------------------------------------ absent and unimplemented Tools

    @Test
    void aToolTheSkillDoesNotAllowIsRefusedBeforeAnyImplementationRuns() {
        Harness harness = harness(SegmentToolCatalog.fromSkill(skillWithout(SegmentToolIds.PREVIEW_SAFE)),
                StandardSegmentToolHandlers.all());

        SegmentToolAdmission first = harness.call(SegmentToolIds.PREVIEW_SAFE, 1);
        assertThat(first.resolved()).isFalse();
        assertThat(first.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_NOT_ALLOWED);
        assertThat(harness.port.calls()).isZero();
        assertThat(harness.invoker.callCount()).isZero();
    }

    @Test
    void anAllowedToolWithoutAnImplementationIsRefusedBeforeThePreviewPortIsEntered() {
        Harness harness = harness(
                RefinementTestFixtures.catalog(),
                RefinementTestFixtures.handlersWithout(SegmentToolIds.PREVIEW_SAFE));

        assertThat(harness.call(SegmentToolIds.METADATA_DISCOVER, 1).resolved()).isTrue();
        SegmentToolAdmission preview = harness.call(SegmentToolIds.PREVIEW_SAFE, 2);

        assertThat(preview.resolved()).isFalse();
        assertThat(preview.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_NOT_IMPLEMENTED);
        assertThat(harness.port.calls()).isZero();
    }

    @Test
    void anUnknownToolIsRefusedBeforeAnythingRuns() {
        Harness harness = harness();
        SegmentToolAdmission unknown = harness.call("preview.unsafe", 1);

        assertThat(unknown.resolved()).isFalse();
        assertThat(unknown.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_UNKNOWN);
        assertThat(harness.port.calls()).isZero();
    }

    @Test
    void aHostOnlyCapabilityIsRefusedAsForbiddenRatherThanAsUnknown() {
        Harness harness = harness();
        SegmentToolAdmission mutation = harness.call("publish.audience", 1);

        assertThat(mutation.resolved()).isFalse();
        assertThat(mutation.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_FORBIDDEN_CAPABILITY);
        assertThat(harness.port.calls()).isZero();
    }

    // ------------------------------------------------------------------ stale, out of order, over limit

    @Test
    void aStaleToolSchemaVersionIsRefusedBeforeAnythingRuns() {
        Harness harness = harness();
        SegmentToolAdmission stale = harness.invoker.invoke(new ToolEnvelope(
                new SchemaVersion(1, 2), "call-1", SegmentToolIds.PREVIEW_SAFE, false, ToolInput.empty()));

        assertThat(stale.resolved()).isFalse();
        assertThat(stale.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_SCHEMA_STALE);
        assertThat(harness.port.calls()).isZero();
        assertThat(harness.invoker.callCount()).isZero();
    }

    @Test
    void aLaterProtectedStageCannotRunBeforeTheDeclaredFirstTool() {
        Harness harness = harness();

        SegmentToolAdmission preview = harness.call(SegmentToolIds.PREVIEW_SAFE, 1);
        assertThat(preview.resolved()).isFalse();
        assertThat(preview.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_OUT_OF_ORDER);
        assertThat(harness.port.calls()).isZero();

        SegmentToolAdmission planning = harness.call(SegmentToolIds.PLAN_BUILD, 2);
        assertThat(planning.resolved()).isFalse();
        assertThat(planning.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_OUT_OF_ORDER);

        SegmentToolAdmission discovery = harness.call(SegmentToolIds.METADATA_DISCOVER, 3);
        assertThat(discovery.resolved()).isTrue();
    }

    @Test
    void aReversedOrRepeatedPhaseIsRefused() {
        Harness harness = harness();
        assertThat(harness.call(SegmentToolIds.METADATA_DISCOVER, 1).resolved()).isTrue();
        assertThat(harness.call(SegmentToolIds.DSL_VALIDATE, 2).resolved()).isTrue();

        SegmentToolAdmission repeated = harness.call(SegmentToolIds.DSL_VALIDATE, 3);
        assertThat(repeated.resolved()).isFalse();
        assertThat(repeated.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_DUPLICATE_CALL);

        SegmentToolAdmission backwards = harness.call(SegmentToolIds.METADATA_DISCOVER, 4);
        assertThat(backwards.resolved()).isFalse();
        assertThat(backwards.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_DUPLICATE_CALL);
        assertThat(harness.port.calls()).isZero();
    }

    @Test
    void anOverBudgetRunIsRefusedBeforeTheProtectedImplementationRuns() {
        Harness harness = harness(
                RefinementTestFixtures.catalogWithToolCallBudget(2), StandardSegmentToolHandlers.all());

        assertThat(harness.call(SegmentToolIds.METADATA_DISCOVER, 1).resolved()).isTrue();
        assertThat(harness.call(SegmentToolIds.DSL_VALIDATE, 2).resolved()).isTrue();

        SegmentToolAdmission overBudget = harness.call(SegmentToolIds.PLAN_BUILD, 3);
        assertThat(overBudget.resolved()).isFalse();
        assertThat(overBudget.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_CALL_LIMIT_EXCEEDED);

        SegmentToolAdmission preview = harness.call(SegmentToolIds.PREVIEW_SAFE, 4);
        assertThat(preview.resolved()).isFalse();
        assertThat(preview.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_CALL_LIMIT_EXCEEDED);
        assertThat(harness.port.calls()).isZero();
        assertThat(harness.invoker.callCount()).isEqualTo(2);
    }

    @Test
    void anOverLimitPayloadIsRefusedBeforeTheProtectedImplementationRuns() {
        Harness harness = harness();
        assertThat(harness.call(SegmentToolIds.METADATA_DISCOVER, 1).resolved()).isTrue();

        SegmentToolAdmission oversized = harness.invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-2", SegmentToolIds.PREVIEW_SAFE, false,
                ToolInput.of(Map.of("padding", "x".repeat(8192)))));

        assertThat(oversized.resolved()).isFalse();
        assertThat(oversized.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_PAYLOAD_LIMIT_EXCEEDED);
        assertThat(harness.port.calls()).isZero();
    }

    @Test
    void aPostTerminalRunRefusesEveryLaterTool() {
        Harness harness = harness();
        assertThat(harness.call(SegmentToolIds.METADATA_DISCOVER, 1).resolved()).isTrue();
        harness.invoker.markTerminal();

        SegmentToolAdmission discovery = harness.call(SegmentToolIds.METADATA_DISCOVER, 2);
        assertThat(discovery.resolved()).isFalse();
        assertThat(discovery.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_POST_TERMINAL);

        SegmentToolAdmission preview = harness.call(SegmentToolIds.PREVIEW_SAFE, 3);
        assertThat(preview.resolved()).isFalse();
        assertThat(preview.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_POST_TERMINAL);
        assertThat(harness.port.calls()).isZero();
        assertThat(harness.invoker.callCount()).isEqualTo(1);
    }

    @Test
    void everyRefusalLeavesOneAuditSafeTraceEntryWithItsPayloadFingerprint() {
        Harness harness = harness();
        harness.call(SegmentToolIds.PREVIEW_SAFE, 1);
        harness.call("publish.audience", 2);
        harness.call(SegmentToolIds.METADATA_DISCOVER, 3);

        assertThat(harness.invoker.trace()).hasSize(3);
        assertThat(harness.invoker.trace()).allSatisfy(record -> {
            assertThat(record.inputFingerprint()).isNotNull();
            assertThat(record.toolId()).isNotBlank();
        });
        assertThat(harness.invoker.trace().get(0).code()).isEqualTo(SegmentToolErrorCodes.TOOL_OUT_OF_ORDER);
        assertThat(harness.invoker.trace().get(1).code())
                .isEqualTo(SegmentToolErrorCodes.TOOL_FORBIDDEN_CAPABILITY);
        assertThat(harness.invoker.trace().get(2).code()).isNull();
    }

    // ------------------------------------------------------------------ Skill compatibility

    @Test
    void aSkillWithoutAPreviewToolIsRefusedBeforeAnyProtectedImplementationRuns() {
        SegmentSkillDefinition incompatible = skillWithout(SegmentToolIds.PREVIEW_SAFE);
        RefinementTestFixtures.CountingPreviewPort port = new RefinementTestFixtures.CountingPreviewPort(
                AgentTestFixtures.previewPort(
                        RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()));
        AgentTestFixtures.CountingAuthorization authorization =
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll());

        assertThatThrownBy(() -> SegmentRefinementEnvironment.of(
                incompatible,
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.of(
                        RefinementTestFixtures.context()),
                runContext -> RefinementTestFixtures.snapshot(),
                new com.company.campaign.metadata.service.MetadataQueryService(
                        authorization, AgentTestFixtures.repository(RefinementTestFixtures.snapshot())),
                authorization,
                RefinementTestFixtures.validationPolicy(),
                port,
                null,
                null))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(SegmentRefinementAdapterErrorCodes.SKILL_INCOMPATIBLE);
        assertThat(port.calls()).isZero();
        assertThat(authorization.calls()).isZero();
    }

    @Test
    void aReorderedOrReducedSkillIsRefusedByTheRefinementPreflight() {
        // Reversing two required Tools keeps the create-flow allowlist valid but breaks the refinement order.
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>();
        List<SegmentSkillDefinition.ToolPin> committed = AgentTestFixtures.committedToolPins();
        Map<String, Integer> rank = new java.util.LinkedHashMap<>();
        int index = 0;
        for (SegmentSkillDefinition.ToolPin pin : committed) {
            rank.put(pin.toolId(), index++);
        }
        int validateRank = rank.get(SegmentToolIds.DSL_VALIDATE);
        int planRank = rank.get(SegmentToolIds.PLAN_BUILD);
        for (SegmentSkillDefinition.ToolPin pin : committed) {
            int assigned = pin.toolId().equals(SegmentToolIds.DSL_VALIDATE)
                    ? planRank
                    : pin.toolId().equals(SegmentToolIds.PLAN_BUILD) ? validateRank : rank.get(pin.toolId());
            pins.add(AgentTestFixtures.pin(pin.toolId(), assigned));
        }
        List<SegmentSkillDefinition.ToolPin> reordered = new ArrayList<>(pins);
        reordered.sort((left, right) -> Integer.compare(left.rank(), right.rank()));
        List<SegmentSkillDefinition.ToolPin> finalPins = new ArrayList<>();
        for (int position = 0; position < reordered.size(); position++) {
            finalPins.add(AgentTestFixtures.pin(reordered.get(position).toolId(), position));
        }
        SegmentSkillDefinition swapped = AgentTestFixtures.skillWithTools(finalPins);

        assertThat(swapped.allowedToolIds()).contains(
                SegmentToolIds.DSL_VALIDATE, SegmentToolIds.PLAN_BUILD);
        assertThatThrownBy(() -> SegmentRefinementEnvironment.of(
                swapped,
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.of(
                        RefinementTestFixtures.context()),
                runContext -> RefinementTestFixtures.snapshot(),
                new com.company.campaign.metadata.service.MetadataQueryService(
                        SegmentValidationFixtures.allowAll(),
                        AgentTestFixtures.repository(RefinementTestFixtures.snapshot())),
                SegmentValidationFixtures.allowAll(),
                RefinementTestFixtures.validationPolicy(),
                AgentTestFixtures.previewPort(
                        RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()),
                null,
                null))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(SegmentRefinementAdapterErrorCodes.SKILL_INCOMPATIBLE);
    }

    @Test
    void aReducedToolCallBudgetIsRefusedByTheRefinementPreflight() {
        SegmentSkillDefinition reduced = AgentTestFixtures.skillWithLimits(16, 2);

        assertThatThrownBy(() -> SegmentRefinementEnvironment.of(
                reduced,
                com.company.campaign.preview.testsupport.RecordingTrustedContextProvider.of(
                        RefinementTestFixtures.context()),
                runContext -> RefinementTestFixtures.snapshot(),
                new com.company.campaign.metadata.service.MetadataQueryService(
                        SegmentValidationFixtures.allowAll(),
                        AgentTestFixtures.repository(RefinementTestFixtures.snapshot())),
                SegmentValidationFixtures.allowAll(),
                RefinementTestFixtures.validationPolicy(),
                AgentTestFixtures.previewPort(
                        RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()),
                null,
                null))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(SegmentRefinementAdapterErrorCodes.SKILL_INCOMPATIBLE);
    }

    @Test
    void theCommittedSkillReusesTheUnchangedCreateFlowPreflight() {
        SegmentRefinementEnvironment environment = RefinementTestFixtures.environment();

        assertThat(environment.skill().allowedToolIds())
                .containsExactlyElementsOf(com.company.campaign.builder.agent.SegmentAgentSkillPreflight.REQUIRED_TOOL_IDS);
        assertThat(SegmentRefinementSkillPreflight.REQUIRED_TOOL_IDS).allSatisfy(
                toolId -> assertThat(environment.catalog().allows(toolId)).isTrue());
        assertThat(environment.catalog().ordered().get(0).toolId()).isEqualTo(SegmentToolIds.METADATA_DISCOVER);
    }

    @Test
    void aHandlerThatThrowsProducesOneBoundedFailureWithNoLaterStage() {
        Map<String, SegmentToolHandler> handlers = new java.util.LinkedHashMap<>(
                StandardSegmentToolHandlers.all());
        handlers.put(SegmentToolIds.DSL_VALIDATE, (run, input) -> {
            throw new IllegalStateException("implementation failure with secret-like text");
        });
        Harness harness = harness(RefinementTestFixtures.catalog(), Map.copyOf(handlers));

        assertThat(harness.call(SegmentToolIds.METADATA_DISCOVER, 1).resolved()).isTrue();
        SegmentToolAdmission failing = harness.call(SegmentToolIds.DSL_VALIDATE, 2);

        assertThat(failing.resolved()).isFalse();
        assertThat(failing.failure().code()).isEqualTo(SegmentToolHandler.FAILURE_CODE);
        assertThat(harness.port.calls()).isZero();
        // The failing stage is the last thing that ran: no later phase was attempted at all.
        assertThat(harness.invoker.callCount()).isEqualTo(2);
        assertThat(harness.invoker.trace()).extracting(
                        com.company.campaign.builder.tool.SegmentToolCallRecord::toolId)
                .containsExactly(SegmentToolIds.METADATA_DISCOVER, SegmentToolIds.DSL_VALIDATE);
    }
}