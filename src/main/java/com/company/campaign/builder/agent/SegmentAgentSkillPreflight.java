package com.company.campaign.builder.agent;

import com.company.campaign.builder.skill.SegmentCapabilityState;
import com.company.campaign.builder.skill.SegmentSkillContractVersions;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillTerminalStates;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.builder.tool.SegmentToolDescriptor;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.builder.tool.SegmentToolVersions;
import com.company.campaign.contracts.Disposition;

import java.util.List;

/**
 * The exact Agent/Skill compatibility proof this fixed v1.3 adapter demands before a run starts.
 *
 * <p>The Agent can only execute one flow, so a Skill that is missing a required Tool, orders the declared Tools
 * differently, pins another component version, declares a bound too small for the fixed path, or omits a
 * disposition or terminal state this adapter can emit is refused before the model, the Metadata projection and
 * every protected stage. The proof runs for the committed validated resource and, identically, for every
 * injected {@link SegmentSkillDefinition}, so a custom
 * {@link SegmentSkillDefinition#publish} instance cannot weaken the loader contract by being constructed
 * directly instead of loaded.
 */
public final class SegmentAgentSkillPreflight {

    /**
     * The exact ordered Tool identifiers the fixed T11 Agent flow admits.
     *
     * <p>{@code metadata.detail} is deliberately absent: this Skill version's flow never executes it, so it is
     * not part of the declared flow and a definition that still permits it is incompatible with this adapter.
     */
    public static final List<String> REQUIRED_TOOL_IDS = List.of(
            SegmentToolIds.METADATA_DISCOVER,
            SegmentToolIds.INTENT_RESOLVE,
            SegmentToolIds.DSL_VALIDATE,
            SegmentToolIds.PLAN_BUILD,
            SegmentToolIds.PREVIEW_SAFE,
            SegmentToolIds.EXPLANATION_GROUNDED);

    /** The number of state entries the fixed happy path records, including the start state. */
    public static final int REQUIRED_HAPPY_PATH_STEPS = 8;

    /** Every disposition this adapter can emit. */
    public static final List<Disposition> REQUIRED_DISPOSITIONS = List.of(
            Disposition.RESOLVED, Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED,
            Disposition.INVALID);

    /** Every terminal Agent state this adapter can reach. */
    public static final List<String> REQUIRED_TERMINAL_STATES = List.of(
            SegmentSkillTerminalStates.NEEDS_INPUT,
            SegmentSkillTerminalStates.UNSUPPORTED,
            SegmentSkillTerminalStates.DENIED,
            SegmentSkillTerminalStates.INVALID,
            SegmentSkillTerminalStates.READY_FOR_PREVIEW);

    private SegmentAgentSkillPreflight() {
    }

    /**
     * Proves the exact compatibility of one Skill and the catalog derived from it, or refuses the pair with the
     * bounded {@link SegmentAgentErrorCodes#SKILL_INCOMPATIBLE} code.
     */
    public static void requireCompatible(SegmentSkillDefinition skill, SegmentToolCatalog catalog) {
        if (skill == null || catalog == null) {
            throw incompatible("A run requires one validated Skill and its catalog");
        }
        if (skill.capabilityState() != SegmentCapabilityState.EXPERIMENTAL) {
            throw incompatible("This adapter can only run an EXPERIMENTAL capability");
        }
        if (!"DEVELOPMENT_ONLY".equals(skill.evidenceClass())) {
            throw incompatible("This adapter can only run DEVELOPMENT_ONLY evidence");
        }
        requireExactPins(skill);
        requireExactTools(skill, catalog);
        requireBounds(skill);
        requireCompleteVocabulary(skill);
    }

    private static void requireExactPins(SegmentSkillDefinition skill) {
        SegmentSkillDefinition.ContractPins pins = skill.contractPins();
        if (!SegmentSkillContractVersions.AGENT_CONTRACT_VERSION.equals(pins.agentContractVersion())
                || !SegmentSkillContractVersions.INTENT_VERSION.equals(pins.intentVersion())
                || !SegmentSkillContractVersions.DSL_VERSION.equals(pins.dslVersion())
                || !SegmentSkillContractVersions.CANONICALIZER_VERSION.equals(pins.canonicalizerVersion())
                || !SegmentSkillContractVersions.RESOLVER_VERSION.equals(pins.resolverVersion())
                || !SegmentSkillContractVersions.VALIDATOR_VERSION.equals(pins.validatorVersion())
                || !SegmentSkillContractVersions.PLANNER_VERSION.equals(pins.plannerVersion())
                || !SegmentSkillContractVersions.PREVIEW_CONTRACT_VERSION.equals(pins.previewContractVersion())
                || !SegmentSkillContractVersions.TOOL_SCHEMA_VERSION.equals(pins.toolSchemaVersion())) {
            throw incompatible("A Skill must pin the exact running component versions");
        }
    }

    private static void requireExactTools(SegmentSkillDefinition skill, SegmentToolCatalog catalog) {
        if (!catalog.allowedToolIds().equals(REQUIRED_TOOL_IDS)) {
            throw incompatible("A Skill must declare exactly the ordered Tools this adapter executes");
        }
        for (SegmentToolDescriptor descriptor : catalog.ordered()) {
            if (!SegmentToolVersions.matches(descriptor.toolId(), descriptor.toolVersion())) {
                throw incompatible("A Skill must pin each Tool at its server-owned version");
            }
        }
    }

    private static void requireBounds(SegmentSkillDefinition skill) {
        if (skill.limits().maxSteps() < REQUIRED_HAPPY_PATH_STEPS) {
            throw incompatible("A Skill maxSteps bound cannot cover the fixed Agent path");
        }
        if (skill.limits().maxToolCalls() < REQUIRED_TOOL_IDS.size()) {
            throw incompatible("A Skill maxToolCalls bound cannot cover the fixed Agent Tool path");
        }
    }

    private static void requireCompleteVocabulary(SegmentSkillDefinition skill) {
        if (!skill.outputDispositions().containsAll(REQUIRED_DISPOSITIONS)) {
            throw incompatible("A Skill must declare every disposition this adapter can emit");
        }
        if (!skill.terminalStates().containsAll(REQUIRED_TERMINAL_STATES)) {
            throw incompatible("A Skill must declare every terminal state this adapter can reach");
        }
    }

    private static SegmentAgentException incompatible(String message) {
        return SegmentAgentException.invalid(SegmentAgentErrorCodes.SKILL_INCOMPATIBLE, message);
    }
}