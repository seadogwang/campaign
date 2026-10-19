package com.company.campaign.builder.campaignagent;

import java.util.List;

/**
 * The immutable, coherent set of typed facts one turn supplies for a Campaign requirement brief.
 *
 * <p>A delta is deliberately partial: a user may describe any subset of the brief in any turn, in any order.
 * A {@code null} (or empty) field therefore means <em>not supplied in this turn</em> — it never means "erase an
 * accepted fact". A non-null field is a supplied fact that replaces the prior value when it is merged, so a
 * coherent explicit correction is exactly a delta that supplies the corrected value.
 *
 * <p>The value can only be constructed coherent: an Offer description without a defined Offer, a branch
 * description without required branching, a duplicate or non-ordered channel list and an over-bound text are
 * all refused here, so a half-supplied pair never reaches a merge. {@link OfferDecision#UNKNOWN} and
 * {@link BranchDecision#UNKNOWN} are normalized to "not supplied" rather than stored, so an unanswered fact is
 * indistinguishable from an omitted fact and can never overwrite an accepted decision.
 *
 * <p>This type carries no Campaign type: the type precedes the brief and is owned by the top-level routing
 * contract, so a turn's typed facts cannot silently classify a Campaign.
 *
 * <p>The {@link #objectiveEvidence()} field is an untrusted string proposal from the model extractor or routing
 * decoder. It is never a confirmed host fact. Only {@link CampaignConversationAgent}, after provenance validation
 * against the current user utterance, may construct {@link CampaignBusinessObjective} and place it in a
 * {@link CampaignRequirementBrief}. A delta without objective evidence preserves the accepted objective; invalid
 * evidence preserves the accepted objective and does not partially mutate other state.
 */
public record CampaignRequirementDelta(
        String audience,
        OfferDecision offerDecision,
        String offerDetail,
        CommunicationDecision communicationDecision,
        List<CampaignMessageInputs.Channel> channels,
        String message,
        BranchDecision branchDecision,
        String branchDetail,
        CampaignCadence cadence,
        CampaignBranchRule branchRule,
        ChannelOperation channelOperation,
        CampaignExecutionSchedule schedule,
        String objectiveEvidence
) {

    /** The largest accepted audience requirement text, in UTF-16 code units. */
    public static final int MAX_AUDIENCE_LENGTH = 2048;

    /** The largest accepted Offer description, in UTF-16 code units. */
    public static final int MAX_OFFER_DETAIL_LENGTH = 2048;

    /** The largest accepted user-authored message content/brief, in UTF-16 code units. */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    /** The largest accepted branch description, in UTF-16 code units. */
    public static final int MAX_BRANCH_DETAIL_LENGTH = 2048;

    public CampaignRequirementDelta {
        audience = CampaignRequirementTexts.boundedText(audience, MAX_AUDIENCE_LENGTH, "audience");
        offerDecision = offerDecision == OfferDecision.UNKNOWN ? null : offerDecision;
        offerDetail = CampaignRequirementTexts.boundedText(offerDetail, MAX_OFFER_DETAIL_LENGTH, "offerDetail");
        communicationDecision = communicationDecision == CommunicationDecision.UNKNOWN
                ? null
                : communicationDecision;
        channels = CampaignRequirementTexts.canonicalChannels(channels);
        message = CampaignRequirementTexts.boundedText(message, MAX_MESSAGE_LENGTH, "message");
        branchDecision = branchDecision == BranchDecision.UNKNOWN ? null : branchDecision;
        branchDetail = CampaignRequirementTexts.boundedText(branchDetail, MAX_BRANCH_DETAIL_LENGTH, "branchDetail");
        // Only an explicit no-communication decision may clear the whole accepted set without naming channels.
        if (channels.isEmpty()) {
            channelOperation = null;
        }
        schedule = schedule == null ? CampaignExecutionSchedule.empty() : schedule;
        if (offerDetail != null && offerDecision != OfferDecision.DEFINED) {
            throw new IllegalArgumentException(
                    "A Campaign requirement Offer description requires a defined Offer.");
        }
        if (communicationDecision == CommunicationDecision.NONE
                && (!channels.isEmpty() || message != null || channelOperation != null)) {
            throw new IllegalArgumentException(
                    "A no-communication correction cannot also supply a channel or message.");
        }
        // A delta may establish communication intent without yet supplying a channel or message;
        // the brief's missingRequirements() will ask for whichever is still needed.
        if (branchDetail != null && branchDecision != BranchDecision.REQUIRED) {
            throw new IllegalArgumentException(
                    "A Campaign requirement branch description requires required branching.");
        }
        if (branchRule != null && branchDecision != BranchDecision.REQUIRED) {
            throw new IllegalArgumentException(
                    "A Campaign branch rule requires required branching.");
        }
    }

    /** Source-compatible constructor for callers that have not supplied a structured branch rule. */
    public CampaignRequirementDelta(
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignCadence cadence) {
        this(audience, offerDecision, offerDetail, inferredCommunication(channels, message),
                channels, message, branchDecision, branchDetail, cadence,
                null, null, null, null);
    }

    /**
     * Source-compatible constructor for callers that supply a structured branch rule but no channel operation or
     * schedule evidence.
     */
    public CampaignRequirementDelta(
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignCadence cadence,
            CampaignBranchRule branchRule) {
        this(audience, offerDecision, offerDetail, inferredCommunication(channels, message),
                channels, message, branchDecision, branchDetail, cadence,
                branchRule, null, null, null);
    }

    /**
     * Source-compatible constructor for callers with channel operation and schedule evidence
     * but an inferred communication decision.
     */
    public CampaignRequirementDelta(
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignCadence cadence,
            CampaignBranchRule branchRule,
            ChannelOperation channelOperation,
            CampaignExecutionSchedule schedule) {
        this(audience, offerDecision, offerDetail, inferredCommunication(channels, message),
                channels, message, branchDecision, branchDetail, cadence, branchRule, channelOperation, schedule, null);
    }

    /**
     * Source-compatible constructor for callers that supply explicit communication decision, channel operation
     * and schedule evidence but predate the objective-evidence field.
     */
    public CampaignRequirementDelta(
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            CommunicationDecision communicationDecision,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignCadence cadence,
            CampaignBranchRule branchRule,
            ChannelOperation channelOperation,
            CampaignExecutionSchedule schedule) {
        this(audience, offerDecision, offerDetail, communicationDecision,
                channels, message, branchDecision, branchDetail, cadence,
                branchRule, channelOperation, schedule, null);
    }

    /** The explicit channel correction of this turn, or {@code null} when the named channels only add. */
    public CampaignChannelUpdate channelUpdate() {
        return channelOperation == null ? null : new CampaignChannelUpdate(channelOperation, channels);
    }

    /** The one delta that supplies no fact at all. */
    public static CampaignRequirementDelta empty() {
        return new CampaignRequirementDelta(
                null, null, null, null, List.of(), null, null, null, null, null, null, null, null);
    }

    /** This turn's non-audience facts, used when text is evidence for an active Segment clarification. */
    public CampaignRequirementDelta withoutAudience() {
        return new CampaignRequirementDelta(
                null, offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, channelOperation, schedule, objectiveEvidence);
    }

    /** This turn's facts with an unverified model channel claim removed. */
    public CampaignRequirementDelta withoutChannels() {
        CommunicationDecision decision = communicationDecision == CommunicationDecision.DEFINED && message == null
                ? null
                : communicationDecision;
        return new CampaignRequirementDelta(
                audience, offerDecision, offerDetail, decision, List.of(), message,
                branchDecision, branchDetail, cadence, branchRule, null, schedule, objectiveEvidence);
    }

    /** This turn with an exact channel selected from the host business-resource Tool. */
    public CampaignRequirementDelta withSelectedChannel(CampaignMessageInputs.Channel channel) {
        return new CampaignRequirementDelta(
                audience, offerDecision, offerDetail, CommunicationDecision.DEFINED, List.of(channel), message,
                branchDecision, branchDetail, cadence, branchRule, ChannelOperation.REPLACE, schedule,
                objectiveEvidence);
    }

    /** This turn's delta with the objective evidence proposal replaced (or cleared when null). */
    public CampaignRequirementDelta withObjectiveEvidence(String evidence) {
        return new CampaignRequirementDelta(
                audience, offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, channelOperation, schedule, evidence);
    }

    /** True when this turn supplied no fact, so merging it can change no accepted fact. */
    public boolean isEmpty() {
        return audience == null && offerDecision == null && offerDetail == null && communicationDecision == null
                && channels.isEmpty() && message == null
                && branchDecision == null && branchDetail == null && cadence == null && branchRule == null
                && channelOperation == null && schedule.isEmpty() && objectiveEvidence == null;
    }

    /**
     * True when this turn supplied a coherent value for the fact the given pending question asked for.
     *
     * <p>It is a pure function of the supplied facts and the stable code, never of any wording, so a host can
     * tell a genuine answer from an unrelated turn without interpreting user copy.
     */
    public boolean answers(CampaignRequirementCode pendingQuestion) {
        if (pendingQuestion == null) {
            return false;
        }
        return switch (pendingQuestion) {
            case AUDIENCE_REQUIRED -> audience != null;
            case OFFER_DECISION_REQUIRED -> offerDecision != null;
            case OFFER_DETAIL_REQUIRED -> offerDetail != null;
            case CHANNEL_REQUIRED -> communicationDecision == CommunicationDecision.NONE || !channels.isEmpty();
            case MESSAGE_REQUIRED -> communicationDecision == CommunicationDecision.NONE || message != null;
            case BRANCH_DECISION_REQUIRED -> branchDecision != null;
            case BRANCH_DETAIL_REQUIRED -> branchDetail != null;
            case BRANCH_RULE_REQUIRED -> branchRule != null;
            case CADENCE_REQUIRED -> cadence != null;
            // The mode-dependent schedule evidence is a pure function of the supplied schedule fields: a turn
            // answers a schedule question exactly when it supplied the field that question asked for.
            case EXECUTION_TIMEZONE_REQUIRED -> schedule.timeZone() != null;
            case EXECUTION_TIME_REQUIRED -> schedule.runAt() != null || schedule.localTime() != null;
            case SCHEDULE_START_DATE_REQUIRED -> schedule.startDate() != null;
            case SCHEDULE_END_DATE_REQUIRED -> schedule.endDate() != null;
            // The non-event type is derived from the schedule mode rather than asked, so no fact delta answers
            // the type question; its unsupported state is host-owned.
            case CAMPAIGN_TYPE_REQUIRED, CAMPAIGN_TYPE_UNSUPPORTED, EVENT_REQUIRED -> false;
        };
    }

    private static CommunicationDecision inferredCommunication(
            List<CampaignMessageInputs.Channel> channels, String message) {
        return (channels != null && !channels.isEmpty()) || message != null
                ? CommunicationDecision.DEFINED
                : null;
    }
}
