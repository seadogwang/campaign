package com.company.campaign.builder.campaignagent;

/**
 * The bounded outcome of one turn.
 *
 * <p>{@code accepted} is true exactly when the turn advanced the session revision and returned the new
 * projection; {@code code} is then null. A refused turn carries one stable machine code and the unchanged
 * projection, so a caller can render the reason without losing the transcript or the accepted state. The
 * result carries no raw error, no model output and no restricted value.
 */
public record CampaignAgentTurnResult(
        boolean accepted,
        String code,
        CampaignConversationProjection projection
) {

    public CampaignAgentTurnResult {
        if (projection == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "turnResult",
                    "A turn result requires its conversation projection.");
        }
        if (accepted == (code != null)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "turnResult",
                    "Only a refused turn may carry a bounded code.");
        }
    }

    /** One accepted turn with its new projection. */
    static CampaignAgentTurnResult accepted(CampaignConversationProjection projection) {
        return new CampaignAgentTurnResult(true, null, projection);
    }

    /** One refused turn with the unchanged projection. */
    static CampaignAgentTurnResult refused(String code, CampaignConversationProjection projection) {
        return new CampaignAgentTurnResult(false, code, projection);
    }

    /** A bounded diagnostic: the disposition, the code and the revision, never a value. */
    @Override
    public String toString() {
        return "CampaignAgentTurnResult[accepted=" + accepted
                + ", code=" + (code == null ? "-" : code)
                + ", revision=" + projection.revision() + ']';
    }
}