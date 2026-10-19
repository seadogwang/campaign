package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.SegmentAgentSkillPreflight;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.builder.tool.SegmentToolDescriptor;
import com.company.campaign.builder.tool.SegmentToolIds;

import java.util.List;

/**
 * The exact Skill compatibility proof the refinement orchestrator demands before a run starts.
 *
 * <p>Refinement reuses the committed T11 Skill, so the first half of the proof is the unchanged create-flow
 * {@link SegmentAgentSkillPreflight}. The refinement flow then executes a strict subsequence of that Skill's
 * Tool path — it skips {@code intent.resolve} entirely, because the refinement adapter resolves no Intent and
 * {@code SegmentToolInvoker} only requires the host-chosen ranks to strictly increase after the declared first
 * Tool — so the second half proves that the subsequence exists, at the server-owned versions, in the declared
 * order, and that the declared bounds can cover it.
 *
 * <p>Nothing here weakens the create-flow proof, adds a Tool, or changes the committed Skill resource. A Skill
 * that is missing one of these Tools, orders them differently, pins another version or declares a bound too
 * small for the fixed refinement path is refused before the model-free structural stage and before every
 * protected Tool implementation.
 */
public final class SegmentRefinementSkillPreflight {

    /**
     * The exact ordered Tool identifiers the fixed refinement flow admits.
     *
     * <p>{@code metadata.discover} runs first because Metadata discovery authorization must precede every
     * further protected stage. {@code explanation.grounded} is not listed: its evidence is optional, so it is
     * invoked only when the host honestly holds it, and a Skill that permits it is still required by the
     * create-flow proof.
     */
    public static final List<String> REQUIRED_TOOL_IDS = List.of(
            SegmentToolIds.METADATA_DISCOVER,
            SegmentToolIds.DSL_VALIDATE,
            SegmentToolIds.PLAN_BUILD,
            SegmentToolIds.PREVIEW_SAFE);

    /** The number of state entries the fixed refinement happy path records, including the start state. */
    public static final int REQUIRED_HAPPY_PATH_STEPS = 7;

    private SegmentRefinementSkillPreflight() {
    }

    /**
     * Proves that one Skill and the catalog derived from it can execute the fixed refinement flow, or refuses
     * the pair with the bounded {@link SegmentRefinementAdapterErrorCodes#SKILL_INCOMPATIBLE} code.
     */
    public static void requireCompatible(SegmentSkillDefinition skill, SegmentToolCatalog catalog) {
        if (skill == null || catalog == null) {
            throw incompatible("A refinement run requires one validated Skill and its catalog");
        }
        try {
            SegmentAgentSkillPreflight.requireCompatible(skill, catalog);
        } catch (RuntimeException rejected) {
            throw incompatible("A refinement Skill must also satisfy the unchanged create-flow proof");
        }
        requireOrderedSubsequence(catalog);
        if (skill.limits().maxSteps() < REQUIRED_HAPPY_PATH_STEPS) {
            throw incompatible("A Skill maxSteps bound cannot cover the fixed refinement path");
        }
        if (skill.limits().maxToolCalls() < REQUIRED_TOOL_IDS.size()) {
            throw incompatible("A Skill maxToolCalls bound cannot cover the fixed refinement Tool path");
        }
    }

    private static void requireOrderedSubsequence(SegmentToolCatalog catalog) {
        int lastRank = -1;
        for (String toolId : REQUIRED_TOOL_IDS) {
            SegmentToolDescriptor descriptor = catalog.descriptor(toolId);
            if (descriptor == null) {
                throw incompatible("A refinement Skill must permit every Tool the fixed flow executes");
            }
            if (descriptor.rank() <= lastRank) {
                throw incompatible("A refinement Skill must order its Tools exactly as the fixed flow executes them");
            }
            lastRank = descriptor.rank();
        }
        SegmentToolDescriptor first = catalog.ordered().get(0);
        if (!SegmentToolIds.METADATA_DISCOVER.equals(first.toolId())) {
            throw incompatible("The refinement flow must begin with the declared Metadata discovery Tool");
        }
    }

    private static SegmentRefinementException incompatible(String message) {
        return SegmentRefinementException.of(
                SegmentRefinementAdapterErrorCodes.SKILL_INCOMPATIBLE, message);
    }
}