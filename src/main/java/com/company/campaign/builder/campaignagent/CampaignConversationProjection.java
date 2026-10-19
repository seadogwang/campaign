package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyWorkspace;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignComponentView;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded conversation projection one turn returns.
 *
 * <p>It carries the conversation/run identity, the session revision and phase, the route kind and disposition
 * of the turn, the accepted transcript (turn identifiers, roles and bounded text), the host-derived Skill
 * states, the bounded ordered component views of the T27 inspection, the pending typed clarification with its
 * question codes and options, bounded safe assistant copy, the audience result card, the T27 workspace
 * inspection hash and — only when the host produced one — the Proposal the existing Plan action may consume.
 *
 * <p>It never carries a model request or output byte, a prompt, a Tool artifact, a template body, a reward
 * secret, a member or contact identifier, Provider data, a permission or an exception message. The projection
 * is a presentation surface, not a mutation surface: nothing in it can Apply, Publish, pause, resume, retry or
 * reach a Provider.
 */
public record CampaignConversationProjection(
        String conversationId,
        String runId,
        long revision,
        CampaignAgentPhase phase,
        String disposition,
        CampaignRouteKind routeKind,
        List<CampaignConversationTurn> turns,
        List<CampaignSkillState> skills,
        List<CampaignComponentView> components,
        CampaignClarification clarification,
        String assistantCopy,
        CampaignAudienceSummary audience,
        ContentHash inspectionHash,
        CampaignProposalPatch proposal,
        ContentHash proposalHash,
        String followUpPrompt,
        CampaignJourneySummary journey,
        CampaignRequirementProgress requirements,
        List<CampaignComponentClarification> componentIssues,
        List<CampaignComponentManifest> componentManifests
) {

    /** The bounded disposition of an accepted turn. */
    public static final String ACCEPTED = "CAMPAIGN_AGENT_ACCEPTED";

    /** The bounded disposition of a turn that was refused without changing anything. */
    public static final String REFUSED = "CAMPAIGN_AGENT_REFUSED";

    /**
     * The accepted T28 shape: a projection that carries no Journey summary.
     *
     * <p>It is kept so every accepted call site keeps compiling. A projection built through it honestly reports
     * that no Journey bundle is staged, which is the exact truth when the Journey Skill contributed nothing.
     */
    public CampaignConversationProjection(
            String conversationId,
            String runId,
            long revision,
            CampaignAgentPhase phase,
            String disposition,
            CampaignRouteKind routeKind,
            List<CampaignConversationTurn> turns,
            List<CampaignSkillState> skills,
            List<CampaignComponentView> components,
            CampaignClarification clarification,
            String assistantCopy,
            CampaignAudienceSummary audience,
            ContentHash inspectionHash,
            CampaignProposalPatch proposal,
            ContentHash proposalHash,
            String followUpPrompt) {
        this(conversationId, runId, revision, phase, disposition, routeKind, turns, skills, components,
                clarification, assistantCopy, audience, inspectionHash, proposal, proposalHash, followUpPrompt,
                null, CampaignRequirementProgress.from(CampaignRequirementBrief.empty()), List.of(), List.of());
    }

    /** Source-compatible shape used before requirement progress became part of the projection. */
    public CampaignConversationProjection(
            String conversationId,
            String runId,
            long revision,
            CampaignAgentPhase phase,
            String disposition,
            CampaignRouteKind routeKind,
            List<CampaignConversationTurn> turns,
            List<CampaignSkillState> skills,
            List<CampaignComponentView> components,
            CampaignClarification clarification,
            String assistantCopy,
            CampaignAudienceSummary audience,
            ContentHash inspectionHash,
            CampaignProposalPatch proposal,
            ContentHash proposalHash,
            String followUpPrompt,
            CampaignJourneySummary journey) {
        this(conversationId, runId, revision, phase, disposition, routeKind, turns, skills, components,
                clarification, assistantCopy, audience, inspectionHash, proposal, proposalHash, followUpPrompt,
                journey, CampaignRequirementProgress.from(CampaignRequirementBrief.empty()), List.of(), List.of());
    }

    public CampaignConversationProjection {
        if (conversationId == null || runId == null || revision < 0 || phase == null || disposition == null
                || inspectionHash == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "projection",
                    "A conversation projection requires its bounded session facts.");
        }
        turns = turns == null ? List.of() : List.copyOf(new ArrayList<>(turns));
        skills = skills == null ? List.of() : List.copyOf(new ArrayList<>(skills));
        components = components == null ? List.of() : List.copyOf(new ArrayList<>(components));
        requirements = requirements == null
                ? CampaignRequirementProgress.from(CampaignRequirementBrief.empty())
                : requirements;
        componentIssues = componentIssues == null ? List.of() : List.copyOf(new ArrayList<>(componentIssues));
        componentManifests = componentManifests == null ? List.of() : List.copyOf(new ArrayList<>(componentManifests));
        if (components.size() > CampaignAssemblyWorkspace.MAX_COMPONENTS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "projectionComponents",
                    "A projection carries at most the accepted component count bound.");
        }
        for (int index = 0; index < components.size(); index++) {
            CampaignComponentView component = components.get(index);
            if (component == null) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "projectionComponents",
                        "A component projection entry requires its bounded identity facts.");
            }
            if (index > 0 && !precedes(components.get(index - 1), component)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "projectionComponents",
                        "Components are ordered by identity and never repeat.");
            }
        }
        if ((proposal == null) != (proposalHash == null)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "projectionProposal",
                    "A projection carries a Proposal and its hash together, or neither.");
        }
        boolean journeyStaged = components.stream()
                .anyMatch(component -> CampaignSkillIds.JOURNEY_FLOW.equals(component.skillId()));
        if (journeyStaged != (journey != null)) {
            // A Journey summary describes an accepted bundle, so the two facts cannot disagree: a summary with
            // no staged Journey would be fabricated evidence, and a staged Journey with no summary would leave
            // the canvas unable to report it truthfully.
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "projectionJourney",
                    "A projection carries a Journey summary exactly when a Journey bundle is staged.");
        }
    }

    /** True when this projection carries a host-produced Proposal for the explicit Plan action. */
    public boolean hasProposal() {
        return proposal != null;
    }

    /** The canonical component order: component identity, then operation kind. */
    private static boolean precedes(CampaignComponentView prior, CampaignComponentView next) {
        int byIdentity = prior.componentId().compareTo(next.componentId());
        return byIdentity != 0 ? byIdentity < 0 : prior.operationKind().compareTo(next.operationKind()) < 0;
    }

    /** A bounded diagnostic: the revision, the phase and the counts, never a value. */
    @Override
    public String toString() {
        return "CampaignConversationProjection[revision=" + revision
                + ", phase=" + phase
                + ", routeKind=" + routeKind
                + ", turns=" + turns.size()
                + ", skills=" + skills.size()
                + ", components=" + components.size() + ']';
    }
}
