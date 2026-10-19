package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillState;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Safe, bounded presentation of the Campaign requirements collected so far.
 *
 * <p>Alongside the accumulated facts and their deterministic remaining gaps it carries the host-derived
 * {@link CampaignComponentTodo} view: the same facts grouped by the component each belongs to, closed against
 * the genuine workspace Skill states. The TODO is derived here and never stored, so it can only ever report a
 * state the accepted brief and the real workspace hold.
 */
public record CampaignRequirementProgress(
        CampaignType type,
        String audience,
        OfferDecision offerDecision,
        String offerDetail,
        CommunicationDecision communicationDecision,
        List<CampaignMessageInputs.Channel> channels,
        String message,
        BranchDecision branchDecision,
        String branchDetail,
        CampaignBranchRule branchRule,
        CampaignCadence cadence,
        CampaignExecutionSchedule schedule,
        String triggerEventCode,
        List<CampaignRequirementCode> missing,
        boolean complete,
        boolean unsupported,
        List<CampaignComponentTodo> componentTodo,
        CampaignBusinessObjective.SafeObjectiveStatus objectiveStatus) {

    public CampaignRequirementProgress {
        channels = channels == null ? List.of() : List.copyOf(channels);
        schedule = schedule == null ? CampaignExecutionSchedule.empty() : schedule;
        missing = missing == null ? List.of() : List.copyOf(missing);
        componentTodo = componentTodo == null ? List.of() : List.copyOf(new ArrayList<>(componentTodo));
        objectiveStatus = objectiveStatus == null ? CampaignBusinessObjective.SafeObjectiveStatus.absent() : objectiveStatus;
    }

    /** The progress of one brief with no workspace Skill evidence. */
    public static CampaignRequirementProgress from(CampaignRequirementBrief brief) {
        return from(brief, List.of());
    }

    /** The progress of one brief, with the component TODO closed against the workspace Skill states. */
    public static CampaignRequirementProgress from(
            CampaignRequirementBrief brief, List<CampaignSkillState> skills) {
        return from(brief, skills, null, null);
    }

    /**
     * The progress of one brief, with the component TODO closed against the workspace Skill states and the trusted
     * turn time.
     *
     * <p>The trusted turn time grounds the EXECUTION_SCHEDULE component status exactly as the finalization gate does,
     * so the presented progress and the finalization decision can never disagree.
     */
    public static CampaignRequirementProgress from(
            CampaignRequirementBrief brief,
            List<CampaignSkillState> skills,
            Instant trustedNow,
            ZoneId trustedZone) {
        CampaignRequirementBrief value = brief == null ? CampaignRequirementBrief.empty() : brief;
        CampaignBusinessObjective.SafeObjectiveStatus objectiveStatus =
                value.confirmedObjective() == null
                        ? CampaignBusinessObjective.SafeObjectiveStatus.absent()
                        : value.confirmedObjective().safeStatus();
        return new CampaignRequirementProgress(
                value.type(), value.audience(), value.offerDecision(), value.offerDetail(),
                value.communicationDecision(), value.channels(),
                value.message(), value.branchDecision(), value.branchDetail(), value.branchRule(), value.cadence(),
                value.schedule(), value.triggerEventCode(), value.missingRequirements(),
                value.completeForAssembly(), value.unsupported(),
                CampaignComponentTodo.from(value, skills, trustedNow, trustedZone),
                objectiveStatus);
    }
}
