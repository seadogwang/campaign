package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The immutable, bounded request one routing decision is asked for.
 *
 * <p>It carries only conversation/run identity, the host-derived per-Skill states of the T27 workspace, a
 * bounded list of prior-turn summaries and the latest user utterance. There is deliberately no field for a
 * mutable workspace, a Tool, a capability payload, a Proposal, a permission, a tenant fact or a lifecycle
 * action; the routing model cannot be handed anything it could mutate or echo back as authority.
 */
public record CampaignRoutingRequest(
        String conversationId,
        String runId,
        CampaignTurnPosition position,
        List<CampaignSkillState> skillStates,
        List<String> priorTurns,
        String utterance
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignRoutingRequest {
        if (conversationId == null || !IDENTIFIER.matcher(conversationId).matches()
                || runId == null || !IDENTIFIER.matcher(runId).matches()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "identity",
                    "A routing request requires bounded conversation and run identity.");
        }
        if (position == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "position", "A routing request requires a position.");
        }
        skillStates = skillStates == null ? List.of() : List.copyOf(new ArrayList<>(skillStates));
        if (skillStates.size() > CampaignAgentLimits.MAX_SELECTED_SKILLS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "skillStates",
                    "A routing request exceeds its Skill-state bound.");
        }
        priorTurns = priorTurns == null ? List.of() : List.copyOf(new ArrayList<>(priorTurns));
        if (priorTurns.size() > CampaignAgentLimits.MAX_PRIOR_TURNS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "priorTurns",
                    "A routing request exceeds its prior-turn bound.");
        }
        for (String summary : priorTurns) {
            if (summary == null || summary.length() > CampaignAgentLimits.MAX_PRIOR_TURN_LENGTH) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "priorTurn",
                        "A prior-turn summary must be bounded.");
            }
        }
        if (utterance == null || utterance.isBlank()
                || utterance.length() > CampaignAgentLimits.MAX_UTTERANCE_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "utterance",
                    "A routing request requires one bounded user utterance.");
        }
    }

    /** The canonical bytes of this request, used only to fingerprint a call; never published. */
    public byte[] canonicalBytes() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("conversationId", conversationId);
        material.put("runId", runId);
        material.put("position", position.name());
        material.put("utterance", utterance);
        material.put("priorTurns", priorTurns);
        List<Map<String, Object>> skills = new ArrayList<>();
        for (CampaignSkillState state : skillStates) {
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("skillId", state.skillId());
            skill.put("state", state.state().name());
            skill.put("code", state.code());
            skills.add(skill);
        }
        material.put("skillStates", skills);
        return CANONICAL_JSON.write(material);
    }

    /** The content hash of {@link #canonicalBytes()}. */
    public ContentHash requestHash() {
        return ContentHash.sha256(canonicalBytes());
    }

    /** A bounded diagnostic: the position and the counts, never the utterance. */
    @Override
    public String toString() {
        return "CampaignRoutingRequest[position=" + position
                + ", skills=" + skillStates.size()
                + ", priorTurns=" + priorTurns.size() + ']';
    }
}