package com.company.campaign.builder.campaignagent;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * One bounded, host-presentable answer option of a typed clarification.
 *
 * <p>An option carries an opaque identifier and a bounded safe label. It never carries a Tool argument, a
 * capability payload, a member identifier or a saved-state fact. A host-owned catalog choice may use its exact
 * bounded reference code as the opaque option identity; choosing it is still only one more bounded user turn.
 */
public record CampaignAnswerOption(String optionId, String label) {

    private static final Pattern OPTION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$");

    public CampaignAnswerOption {
        if (optionId == null || !OPTION_ID.matcher(optionId).matches()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "optionId",
                    "An answer option requires a bounded opaque identifier.");
        }
        if (label == null || label.isEmpty() || label.length() > CampaignAgentLimits.MAX_OPTION_LABEL_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "optionLabel",
                    "An answer option requires a bounded label.");
        }
        CampaignRoutingVocabulary.requireSafeCopy(label, "optionLabel");
    }

    /** A bounded diagnostic: the identifier only, never the label the user sees. */
    @Override
    public String toString() {
        return "CampaignAnswerOption[optionId=" + optionId + ']';
    }
}
