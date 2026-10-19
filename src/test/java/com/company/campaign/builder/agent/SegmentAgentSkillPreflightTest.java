package com.company.campaign.builder.agent;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillContractVersions;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillErrorCodes;
import com.company.campaign.builder.skill.SegmentSkillException;
import com.company.campaign.builder.skill.SegmentSkillLoader;
import com.company.campaign.builder.skill.SegmentSkillTerminalStates;
import com.company.campaign.builder.tool.SegmentToolVersions;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 2 of the corrective task: the fixed v1.3 Agent flow re-proves the exact Agent/Skill
 * compatibility for every injected definition, so a reduced limit, a missing or reordered Tool, a missing
 * terminal state or disposition, and an unconstructible pin mismatch all fail before the provider, the model
 * and every protected stage.
 */
class SegmentAgentSkillPreflightTest {

    private static SegmentAgentEnvironment environment(SegmentSkillDefinition skill) {
        return AgentTestFixtures.environment(
                skill,
                AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()),
                AgentTestFixtures.snapshot(),
                AgentTestFixtures.context(),
                SegmentValidationFixtures.allowAll(),
                AgentTestFixtures.previewPort(
                        AgentTestFixtures.snapshot(), AgentTestFixtures.context(), SegmentValidationFixtures.allowAll()),
                null,
                null);
    }

    private static void assertIncompatible(SegmentSkillDefinition skill) {
        assertThatThrownBy(() -> environment(skill))
                .isInstanceOf(SegmentAgentException.class)
                .extracting(failure -> ((SegmentAgentException) failure).code())
                .isEqualTo(SegmentAgentErrorCodes.SKILL_INCOMPATIBLE);
    }

    @Test
    void theCommittedValidatedResourceSatisfiesTheExactAdapterFlow() {
        SegmentSkillDefinition skill = new SegmentSkillLoader().loadDefault();

        assertThat(skill.allowedToolIds()).containsExactlyElementsOf(SegmentAgentSkillPreflight.REQUIRED_TOOL_IDS);
        assertThat(skill.limits().maxSteps())
                .isGreaterThanOrEqualTo(SegmentAgentSkillPreflight.REQUIRED_HAPPY_PATH_STEPS);
        assertThat(skill.limits().maxToolCalls())
                .isGreaterThanOrEqualTo(SegmentAgentSkillPreflight.REQUIRED_TOOL_IDS.size());
        assertThat(skill.outputDispositions()).containsAll(SegmentAgentSkillPreflight.REQUIRED_DISPOSITIONS);
        assertThat(skill.terminalStates()).containsAll(SegmentAgentSkillPreflight.REQUIRED_TERMINAL_STATES);
        SegmentAgentSkillPreflight.requireCompatible(
                skill, com.company.campaign.builder.tool.SegmentToolCatalog.fromSkill(skill));
        assertThat(environment(skill)).isNotNull();
    }

    @Test
    void theRequiredFlowPinsTheExactRunningComponentVersions() {
        SegmentSkillDefinition.ContractPins pins = new SegmentSkillLoader().loadDefault().contractPins();

        assertThat(pins.agentContractVersion())
                .isEqualTo(SegmentSkillContractVersions.AGENT_CONTRACT_VERSION);
        assertThat(pins.intentVersion()).isEqualTo(SegmentSkillContractVersions.INTENT_VERSION);
        assertThat(pins.dslVersion()).isEqualTo(SegmentSkillContractVersions.DSL_VERSION);
        assertThat(pins.canonicalizerVersion()).isEqualTo(SegmentSkillContractVersions.CANONICALIZER_VERSION);
        assertThat(pins.resolverVersion()).isEqualTo(SegmentSkillContractVersions.RESOLVER_VERSION);
        assertThat(pins.validatorVersion()).isEqualTo(SegmentSkillContractVersions.VALIDATOR_VERSION);
        assertThat(pins.plannerVersion()).isEqualTo(SegmentSkillContractVersions.PLANNER_VERSION);
        assertThat(pins.previewContractVersion()).isEqualTo(SegmentSkillContractVersions.PREVIEW_CONTRACT_VERSION);
        assertThat(pins.toolSchemaVersion()).isEqualTo(SegmentSkillContractVersions.TOOL_SCHEMA_VERSION);
    }

    @Test
    void aSkillThatDropsARequiredToolIsRefused() {
        for (String toolId : SegmentAgentSkillPreflight.REQUIRED_TOOL_IDS) {
            List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>();
            int rank = 0;
            for (SegmentSkillDefinition.ToolPin pin : AgentTestFixtures.committedToolPins()) {
                if (!pin.toolId().equals(toolId)) {
                    pins.add(AgentTestFixtures.pin(pin.toolId(), rank++));
                }
            }
            assertIncompatible(AgentTestFixtures.skillWithTools(pins));
        }
    }

    @Test
    void aSkillThatPermitsAnExtraToolIsRefused() {
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>(AgentTestFixtures.committedToolPins());
        pins.add(AgentTestFixtures.pin(com.company.campaign.builder.tool.SegmentToolIds.METADATA_DETAIL, pins.size()));
        assertIncompatible(AgentTestFixtures.skillWithTools(pins));
    }

    @Test
    void aSkillThatReordersTwoRequiredToolsIsRefused() {
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>(AgentTestFixtures.committedToolPins());
        SegmentSkillDefinition.ToolPin first = pins.get(1);
        SegmentSkillDefinition.ToolPin second = pins.get(2);
        pins.set(1, AgentTestFixtures.pin(second.toolId(), 1));
        pins.set(2, AgentTestFixtures.pin(first.toolId(), 2));
        assertIncompatible(AgentTestFixtures.skillWithTools(pins));
    }

    @Test
    void aSkillWhoseStepBoundCannotCoverTheFixedPathIsRefused() {
        assertIncompatible(AgentTestFixtures.skillWithLimits(
                SegmentAgentSkillPreflight.REQUIRED_HAPPY_PATH_STEPS - 1, 6));
    }

    @Test
    void aSkillWhoseToolCallBoundCannotCoverTheFixedPathIsRefused() {
        assertIncompatible(AgentTestFixtures.skillWithLimits(16, SegmentAgentSkillPreflight.REQUIRED_TOOL_IDS.size() - 1));
    }

    @Test
    void aSkillThatOmitsAReachableTerminalStateIsRefused() {
        List<String> terminals = new ArrayList<>(AgentTestFixtures.skill().terminalStates());
        terminals.remove(SegmentSkillTerminalStates.UNSUPPORTED);
        assertIncompatible(AgentTestFixtures.skillWithTerminalStates(terminals));
    }

    @Test
    void aSkillThatOmitsAnEmittableDispositionIsRefused() {
        List<Disposition> dispositions = new ArrayList<>(AgentTestFixtures.skill().outputDispositions());
        dispositions.remove(Disposition.DENIED);
        assertIncompatible(AgentTestFixtures.skillWithOutputDispositions(dispositions));
    }

    @Test
    void aSkillWithAReadyForPreviewOrNeedsInputTerminalMissingCannotEvenBeConstructed() {
        assertThatThrownBy(() -> AgentTestFixtures.skillWithTerminalStates(
                        List.of(SegmentSkillTerminalStates.UNSUPPORTED, SegmentSkillTerminalStates.DENIED,
                                SegmentSkillTerminalStates.INVALID)))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED);
        assertThatThrownBy(() -> AgentTestFixtures.skillWithOutputDispositions(
                        List.of(Disposition.RESOLVED, Disposition.UNSUPPORTED, Disposition.DENIED,
                                Disposition.INVALID)))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(SegmentSkillErrorCodes.DISPOSITION_UNSUPPORTED);
    }

    @Test
    void aMismatchedToolVersionOrContractPinIsRefusedBeforeItCanBeInjected() {
        assertThatThrownBy(() -> AgentTestFixtures.skillWithToolVersion(
                        com.company.campaign.builder.tool.SegmentToolIds.INTENT_RESOLVE,
                        SegmentToolVersions.versionOf(com.company.campaign.builder.tool.SegmentToolIds.INTENT_RESOLVE)
                                + "-stale"))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(SegmentSkillErrorCodes.TOOL_VERSION_UNKNOWN);

        SegmentSkillDefinition base = AgentTestFixtures.skill();
        assertThatThrownBy(() -> new SegmentSkillDefinition.ContractPins(
                        SegmentSkillContractVersions.AGENT_CONTRACT_VERSION,
                        "9.9",
                        SegmentSkillContractVersions.DSL_VERSION,
                        SegmentSkillContractVersions.CANONICALIZER_VERSION,
                        SegmentSkillContractVersions.RESOLVER_VERSION,
                        SegmentSkillContractVersions.VALIDATOR_VERSION,
                        SegmentSkillContractVersions.PLANNER_VERSION,
                        SegmentSkillContractVersions.PREVIEW_CONTRACT_VERSION,
                        SegmentSkillContractVersions.TOOL_SCHEMA_VERSION))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(SegmentSkillErrorCodes.CONTRACT_VERSION_UNKNOWN);
        assertThat(base.contractPins().intentVersion()).isEqualTo(SegmentSkillContractVersions.INTENT_VERSION);
    }

    @Test
    void aDirectlyPublishedDefinitionCannotWeakenTheLoaderContract() {
        // The definition below is constructed with publish(...) rather than loaded, and it is still held to the
        // exact same adapter proof at environment construction.
        SegmentSkillDefinition reduced = AgentTestFixtures.skillWithLimits(16, 5);
        assertThat(reduced.allowedToolIds()).containsExactlyElementsOf(
                SegmentAgentSkillPreflight.REQUIRED_TOOL_IDS);
        assertIncompatible(reduced);

        SegmentAgentEnvironment accepted = environment(AgentTestFixtures.skill());
        AgentEnvelope<SegmentAgentRequest> envelope = AgentTestFixtures.envelope(new SegmentAgentRequest("Gold会员"));
        assertThat(new SegmentAgentAdapter(accepted).invoke(envelope).disposition())
                .isEqualTo(Disposition.RESOLVED);
        assertThat(envelope.schemaVersion()).isEqualTo(ContractVersions.CURRENT);
    }
}