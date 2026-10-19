package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.proposal.ProposalIdentity;

import java.util.List;

/**
 * The one host-owned dependency set of the top-level Campaign Agent.
 *
 * <p>Every dependency is a small typed port. There is no registry, no reflection, no dynamic lookup, no
 * unrestricted map, no generic callback, no network client, no model SDK, no repository and no database: the
 * routing model is reached only through {@link #routingModel()}, and every Tool, catalog and capability input is
 * host evidence supplied through an explicit typed accessor. The trusted time snapshot ({@link #trustedTime()}) is
 * the only source of the current turn time a schedule is grounded and validated against.
 *
 * <p>An accessor may return {@code null} to mean "this build has no accepted implementation wired for that
 * stage"; the corresponding Skill then reports a bounded {@code Unavailable} outcome instead of fabricating
 * behaviour. That is how a host can run the Message, Promotion, Journey, Schedule and Governance Skills without
 * pretending that a production model Provider or a Segment Agent exists.
 */
public interface CampaignAgentEnvironment {

    /** The one bounded routing model port; a null port fails the turn closed as unavailable. */
    CampaignRoutingModelPort routingModel();

    /**
     * The trusted time snapshot every accepted schedule is grounded and validated against, or null when a host has
     * not wired one.
     *
     * <p>It is the only source of the current turn time: a model-extracted execution instant or window is rejected
     * as not future against its instant, and a relative or year-omitted date is grounded in the document the
     * structured extractor builds from the same snapshot. No orchestration boundary reads a clock directly.
     */
    default CampaignTurnTime trustedTime() {
        return null;
    }

    /** The structured Campaign requirement extractor, or null when this host keeps the legacy Skill flow. */
    default CampaignRequirementExtractorPort requirementExtractor() {
        return null;
    }

    /** The trusted host Tool result for registered channels and trigger events; empty fails closed. */
    default CampaignBusinessResourceCatalog businessResourceCatalog() {
        return CampaignBusinessResourceCatalog.empty();
    }

    /** Whether the host permits one schema-only repair of a rejected routing output. */
    default CampaignSchemaRepairPolicy repairPolicy() {
        return CampaignSchemaRepairPolicy.NONE;
    }

    /** The closed Campaign Tool catalog every Skill invocation is pinned to. */
    default CampaignToolCatalog toolCatalog() {
        return CampaignToolCatalog.standard();
    }

    /** The host-owned per-run Tool evidence, or null when no Tool-backed Skill is wired. */
    default CampaignToolRunContext toolRunContext() {
        return null;
    }

    /** The one accepted Segment authoring integration, or null when the Segment Skill is not wired. */
    default SegmentAuthoringIntegration segmentIntegration() {
        return null;
    }

    /** The host-owned resolved-or-terminal Segment source of one utterance, or null when none is wired. */
    default CampaignSegmentSubmission segmentSource(String conversationId, String runId, String utterance) {
        return null;
    }

    /**
     * The host-owned Segment submission of one utterance, replayed under one exact continuation.
     *
     * <p>The continuation is the genuine resolution evidence of the clarification this session already asked,
     * or null for a fresh utterance. It is host/session state, never model output and never a client field, so
     * the routing model can neither create it nor answer it. The default delegates to the three-argument form,
     * so an environment that only knows how to resolve a fresh utterance keeps the accepted behavior exactly.
     */
    default CampaignSegmentSubmission segmentSource(
            String conversationId,
            String runId,
            String utterance,
            CampaignSegmentContinuation continuation) {
        return segmentSource(conversationId, runId, utterance);
    }

    /** The host-owned typed Message inputs of one utterance. */
    default CampaignMessageInputs messageInputs(
            String utterance, List<CampaignClarificationAnswer> answers) {
        return CampaignMessageInputs.unresolved();
    }

    /** The host-owned typed Promotion inputs of one utterance. */
    default CampaignPromotionInputs promotionInputs(
            String utterance, List<CampaignClarificationAnswer> answers) {
        return CampaignPromotionInputs.unresolved();
    }

    /** The host-owned typed Schedule inputs of one utterance. */
    default CampaignScheduleInputs scheduleInputs(
            String utterance, List<CampaignClarificationAnswer> answers) {
        return CampaignScheduleInputs.unresolved();
    }

    /** The host-owned typed Governance inputs of one utterance. */
    default CampaignGovernanceInputs governanceInputs(
            String utterance, List<CampaignClarificationAnswer> answers) {
        return CampaignGovernanceInputs.unresolved();
    }

    /**
     * The host-owned typed Journey inputs of one utterance.
     *
     * <p>It is the only accessor that carries a whole node/topology declaration. The trusted facts the Journey
     * Tools are decided against — the frozen pack index, the capability catalog, the authoring policy and the
     * trusted context — are reached through {@link CampaignToolRunContext} instead, so this accessor can never
     * manufacture them.
     */
    default CampaignJourneyInputs journeyInputs(
            String utterance, List<CampaignClarificationAnswer> answers) {
        return CampaignJourneyInputs.unresolved();
    }

    /**
     * The host-owned compile-evidence registry, or null when none is wired.
     *
     * <p>It is the bridge between the conversation flow and the plan compile service: the
     * conversation registers host-owned Segment compile evidence per proposal hash when a
     * Campaign is finalized, and the compile service looks it up to pin the compile to the
     * same frozen evidence the Segment resolution ran against.
     */
    default CampaignCompileEvidenceRegistry compileEvidenceRegistry() {
        return null;
    }

    /**
     * The frozen T19 Journey pack index a Journey compile is validated against, or null.
     *
     * <p>It is reached from the tool run context when one is wired. The default returns null
     * so a build without a tool run context cannot accidentally supply an index it does not own.
     */
    default T19PackIndex journeyPackIndex() {
        CampaignToolRunContext ctx = toolRunContext();
        return ctx != null ? ctx.journeyPackIndex() : null;
    }

    /**
     * The stable Proposal identity one Skill's component is staged under.
     *
     * <p>The default addresses a new component through a fixed, server-owned {@code clientRef}, so a CREATE
     * assembly never lets the caller or the model choose an identity. An EDIT assembly overrides this to name
     * the existing component's stable {@code logicalId}.
     */
    default ProposalIdentity targetFor(String skillId) {
        return switch (skillId) {
            case CampaignSkillIds.SEGMENT_RULE_BASED -> ProposalIdentity.ofClientRef("campaign-segment");
            case CampaignSkillIds.MESSAGE_TEMPLATE -> ProposalIdentity.ofClientRef("campaign-message");
            case CampaignSkillIds.PROMOTION_REFERENCE -> ProposalIdentity.ofClientRef("campaign-promotion");
            case CampaignSkillIds.SCHEDULE_BASIC -> ProposalIdentity.ofClientRef("campaign-schedule");
            case CampaignSkillIds.GOVERNANCE_LIMIT -> ProposalIdentity.ofClientRef("campaign-governance");
            default -> throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_UNAVAILABLE, "target",
                    "This build has no stageable component for that Skill.");
        };
    }
}
