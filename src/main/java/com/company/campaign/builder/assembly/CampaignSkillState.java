package com.company.campaign.builder.assembly;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded inspection state of one Skill in an ephemeral Campaign assembly.
 *
 * <p>It carries the Skill identifier, the closed {@link CampaignComponentState}, an optional bounded machine
 * code and the missing-dependency codes the workspace reports for the current snapshot. It never carries raw
 * model output, provider output, a template body, a reward secret, a credential or restricted data.
 */
public record CampaignSkillState(
        String skillId,
        CampaignComponentState state,
        String code,
        List<String> missingDependencyCodes
) {

    public CampaignSkillState {
        if (skillId == null || state == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID, "A Skill state requires its identifier and state.");
        }
        if (code != null && !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID, "A Skill state code must be a stable machine code.");
        }
        missingDependencyCodes = missingDependencyCodes == null
                ? List.of()
                : List.copyOf(new ArrayList<>(missingDependencyCodes));
    }

    /** A bounded diagnostic: the identifier, the state and the code, never a value. */
    @Override
    public String toString() {
        return "CampaignSkillState[skillId=" + skillId
                + ", state=" + state
                + ", code=" + (code == null ? "-" : code) + ']';
    }
}