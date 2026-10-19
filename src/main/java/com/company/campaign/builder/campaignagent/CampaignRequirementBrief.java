package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The immutable accumulated requirement facts of one Campaign.
 *
 * <p>Requirement collection is divergent and conversational: facts arrive in any order, one turn at a time, so
 * the brief is a value that only ever grows through {@link #merge(CampaignRequirementDelta)}. It is deliberately
 * independent of the conversation session, the snapshot and HTTP — this slice owns the domain and nothing
 * reads it yet.
 *
 * <p>The value can only be constructed coherent: an Offer description without a defined Offer, a branch
 * description without required branching, a duplicate or non-ordered channel list and an over-bound text are
 * refused at construction. The Campaign type is nullable because it may not be known yet, and an {@code EVENT}
 * brief is a supported type whose registered trigger event is the Campaign's subject: its entry audience is
 * optional and required only when the user stated a genuine additional restriction, which is why
 * {@link #audienceApplicable()} exists as the one authority on whether an audience requirement belongs to the
 * Campaign at all.
 *
 * <p>{@link #missingRequirements()} and {@link #completeForAssembly()} are pure, deterministic functions of the
 * accumulated facts. The channel list uses the existing generic {@link CampaignMessageInputs.Channel}
 * vocabulary and is canonical, unique and ordered by that vocabulary's own declaration order.
 */
public record CampaignRequirementBrief(
        CampaignType type,
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
        CampaignExecutionSchedule schedule,
        String triggerEventCode,
        CampaignBusinessObjective confirmedObjective
) {

    public CampaignRequirementBrief {
        audience = CampaignRequirementTexts.boundedText(
                audience, CampaignRequirementDelta.MAX_AUDIENCE_LENGTH, "audience");
        offerDecision = offerDecision == null ? OfferDecision.UNKNOWN : offerDecision;
        offerDetail = CampaignRequirementTexts.boundedText(
                offerDetail, CampaignRequirementDelta.MAX_OFFER_DETAIL_LENGTH, "offerDetail");
        communicationDecision = communicationDecision == null ? CommunicationDecision.UNKNOWN : communicationDecision;
        channels = CampaignRequirementTexts.canonicalChannels(channels);
        message = CampaignRequirementTexts.boundedText(
                message, CampaignRequirementDelta.MAX_MESSAGE_LENGTH, "message");
        branchDecision = branchDecision == null ? BranchDecision.UNKNOWN : branchDecision;
        branchDetail = CampaignRequirementTexts.boundedText(
                branchDetail, CampaignRequirementDelta.MAX_BRANCH_DETAIL_LENGTH, "branchDetail");
        schedule = schedule == null ? CampaignExecutionSchedule.empty() : schedule;
        triggerEventCode = CampaignRequirementTexts.boundedText(triggerEventCode, 128, "triggerEventCode");
        if (offerDetail != null && offerDecision != OfferDecision.DEFINED) {
            throw new IllegalArgumentException(
                    "A Campaign requirement Offer description requires a defined Offer.");
        }
        if (communicationDecision == CommunicationDecision.NONE && (!channels.isEmpty() || message != null)) {
            throw new IllegalArgumentException(
                    "A Campaign without communication cannot carry a channel or message.");
        }
        if (branchDetail != null && branchDecision != BranchDecision.REQUIRED) {
            throw new IllegalArgumentException(
                    "A Campaign requirement branch description requires required branching.");
        }
        if (branchRule != null && branchDecision != BranchDecision.REQUIRED) {
            throw new IllegalArgumentException(
                    "A Campaign branch rule requires required branching.");
        }
        // A non-event Campaign may begin with no type; once its schedule mode is accepted, the non-event type is
        // derived from that mode rather than asked, so the one-time/repeated decision has one authority. An
        // explicit EVENT type is never derived over because its trigger comes from the registered event catalog.
        if (cadence != null && type != CampaignType.EVENT) {
            type = CampaignType.derivedFrom(cadence);
        }
    }

    /** Source-compatible constructor for persisted and legacy callers without a structured branch rule. */
    public CampaignRequirementBrief(
            CampaignType type,
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignCadence cadence) {
        this(type, audience, offerDecision, offerDetail, inferredCommunication(channels, message),
                channels, message, branchDecision, branchDetail, cadence, null, null, null, null);
    }

    /** Source-compatible constructor for callers that supply a structured branch rule but no schedule evidence. */
    public CampaignRequirementBrief(
            CampaignType type,
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignCadence cadence,
            CampaignBranchRule branchRule) {
        this(type, audience, offerDecision, offerDetail, inferredCommunication(channels, message),
                channels, message, branchDecision, branchDetail,
                cadence, branchRule, null, null, null);
    }

    /** Source-compatible constructor for callers that supplied schedule evidence before communication was explicit. */
    public CampaignRequirementBrief(
            CampaignType type,
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignCadence cadence,
            CampaignBranchRule branchRule,
            CampaignExecutionSchedule schedule) {
        this(type, audience, offerDecision, offerDetail, inferredCommunication(channels, message),
                channels, message, branchDecision, branchDetail, cadence, branchRule, schedule, null, null);
    }

    /**
     * Source-compatible constructor for callers predating registered trigger-event selection.
     * Delegates with null triggerEventCode and null confirmedObjective.
     */
    public CampaignRequirementBrief(
            CampaignType type,
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
            CampaignExecutionSchedule schedule) {
        this(type, audience, offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, schedule, null, null);
    }

    /**
     * Source-compatible constructor for callers that include triggerEventCode but predate the
     * confirmed-objective field. Delegates with null confirmedObjective.
     */
    public CampaignRequirementBrief(
            CampaignType type,
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
            CampaignExecutionSchedule schedule,
            String triggerEventCode) {
        this(type, audience, offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, schedule, triggerEventCode, null);
    }

    /** The one brief that has accumulated no fact and decided no type. */
    public static CampaignRequirementBrief empty() {
        return new CampaignRequirementBrief(
                null, null, OfferDecision.UNKNOWN, null, CommunicationDecision.UNKNOWN, List.of(), null,
                BranchDecision.UNKNOWN, null, null, null, null, null, null);
    }

    /** This brief with the top-level Campaign type fixed, and every accumulated fact unchanged. */
    public CampaignRequirementBrief withType(CampaignType type) {
        return new CampaignRequirementBrief(
                type, audience, offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, schedule, triggerEventCode, confirmedObjective);
    }

    /** This brief with the exact trigger event selected by the host catalog Tool. */
    public CampaignRequirementBrief withTriggerEventCode(String code) {
        return new CampaignRequirementBrief(
                type, audience, offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, schedule, code, confirmedObjective);
    }

    /** This brief with a host-validated replacement channel set. */
    public CampaignRequirementBrief withChannels(List<CampaignMessageInputs.Channel> replacement) {
        CommunicationDecision decision = replacement == null || replacement.isEmpty()
                ? communicationDecision
                : CommunicationDecision.DEFINED;
        return new CampaignRequirementBrief(
                type, audience, offerDecision, offerDetail, decision, replacement, message,
                branchDecision, branchDetail, cadence, branchRule, schedule, triggerEventCode, confirmedObjective);
    }

    /** This brief with a host-validated confirmed business objective. */
    public CampaignRequirementBrief withConfirmedObjective(CampaignBusinessObjective objective) {
        return new CampaignRequirementBrief(
                type, audience, offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, schedule, triggerEventCode, objective);
    }

    /**
     * This brief with the confirmed audience text replaced by a component-validated refinement.
     *
     * <p>It is typed and provenance-aware: it replaces only the audience, and it must only be called after
     * the owning component (Segment) has validated the refinement through its genuine resolution chain.
     * The replacement text is bounded and normalized exactly like the original, so a malformed or
     * over-bound refinement is refused at construction. All other verified facts — offer, message,
     * journey, schedule, objective — are unchanged.
     */
    public CampaignRequirementBrief withAudience(String refinedAudience) {
        return new CampaignRequirementBrief(
                type,
                CampaignRequirementTexts.boundedText(
                        refinedAudience, CampaignRequirementDelta.MAX_AUDIENCE_LENGTH, "audience"),
                offerDecision, offerDetail, communicationDecision, channels, message,
                branchDecision, branchDetail, cadence, branchRule, schedule, triggerEventCode,
                confirmedObjective);
    }

    /**
     * This brief with the confirmed message text replaced by a component-validated refinement.
     *
     * <p>It is typed and provenance-aware: it replaces only the message, and it must only be called
     * after the owning component (Communication) has validated the answer through its genuine Tool chain.
     * The replacement text is bounded and normalized exactly like the original, so a malformed or
     * over-bound refinement is refused at construction. All other verified facts — audience, offer,
     * channels, journey, schedule, objective — are unchanged. The communication decision is elevated
     * to {@code DEFINED} when the text is non-null and non-empty, so a brief with only channels
     * that later receives a validated message body is correctly recognised as communication-defined.
     */
    public CampaignRequirementBrief withMessage(String refinedMessage) {
        CommunicationDecision decision = refinedMessage == null || refinedMessage.isEmpty()
                ? communicationDecision
                : CommunicationDecision.DEFINED;
        return new CampaignRequirementBrief(
                type, audience, offerDecision, offerDetail, decision, channels,
                CampaignRequirementTexts.boundedText(
                        refinedMessage, CampaignRequirementDelta.MAX_MESSAGE_LENGTH, "message"),
                branchDecision, branchDetail, cadence, branchRule, schedule, triggerEventCode,
                confirmedObjective);
    }

    /**
     * The deterministic overlay of one turn's typed facts onto this brief.
     *
     * <p>A fact the delta did not supply keeps its accepted value; a scalar fact the delta supplied replaces it,
     * so a coherent explicit correction is exactly this overlay. A correction of the Offer or branch decision
     * away from "defined"/"required" clears the now-incoherent detail, because a stale description may never
     * survive its decision. Channels are corrected explicitly: a delta that names an operation applies it — an
     * addition unions, a replacement substitutes the whole set and a removal drops the named channels — so a
     * correction can remove a stale channel; a delta that names channels without an operation preserves the
     * accepted union behavior. Schedule evidence is a scalar overlay per field, so a corrected time or window
     * replaces just that field and an absent field never erases an accepted one.
     */
    public CampaignRequirementBrief merge(CampaignRequirementDelta delta) {
        Objects.requireNonNull(delta, "delta");
        OfferDecision mergedOfferDecision =
                delta.offerDecision() == null ? offerDecision : delta.offerDecision();
        String mergedOfferDetail;
        if (delta.offerDecision() != null && delta.offerDecision() != OfferDecision.DEFINED) {
            mergedOfferDetail = null;
        } else if (delta.offerDetail() != null) {
            mergedOfferDetail = delta.offerDetail();
        } else {
            mergedOfferDetail = offerDetail;
        }
        BranchDecision mergedBranchDecision =
                delta.branchDecision() == null ? branchDecision : delta.branchDecision();
        String mergedBranchDetail;
        CampaignBranchRule mergedBranchRule;
        if (delta.branchDecision() != null && delta.branchDecision() != BranchDecision.REQUIRED) {
            mergedBranchDetail = null;
            mergedBranchRule = null;
        } else if (delta.branchDetail() != null) {
            mergedBranchDetail = delta.branchDetail();
            mergedBranchRule = delta.branchRule() == null ? branchRule : delta.branchRule();
        } else {
            mergedBranchDetail = branchDetail;
            mergedBranchRule = delta.branchRule() == null ? branchRule : delta.branchRule();
        }
        CampaignChannelUpdate channelUpdate = delta.channelUpdate();
        CommunicationDecision mergedCommunication = delta.communicationDecision() == null
                ? communicationDecision
                : delta.communicationDecision();
        List<CampaignMessageInputs.Channel> mergedChannels = mergedCommunication == CommunicationDecision.NONE
                ? List.of()
                : channelUpdate == null
                        ? CampaignRequirementTexts.unionChannels(channels, delta.channels())
                        : channelUpdate.apply(channels);
        String mergedMessage = mergedCommunication == CommunicationDecision.NONE
                ? null
                : delta.message() == null ? message : delta.message();
        CampaignCadence mergedCadence = delta.cadence() == null ? cadence : delta.cadence();
        CampaignExecutionSchedule mergedSchedule = schedule.overlay(delta.schedule()).forCadence(mergedCadence);
        // Objective evidence in delta is untrusted; the accepted objective is never merged from it.
        // Only CampaignConversationAgent, after current-message provenance validation, may construct
        // CampaignBusinessObjective and place it via withConfirmedObjective().
        CampaignBusinessObjective mergedObjective = confirmedObjective;
        return new CampaignRequirementBrief(
                type,
                delta.audience() == null ? audience : delta.audience(),
                mergedOfferDecision,
                mergedOfferDetail,
                mergedCommunication,
                mergedChannels,
                mergedMessage,
                mergedBranchDecision,
                mergedBranchDetail,
                mergedCadence,
                mergedBranchRule,
                mergedSchedule,
                triggerEventCode,
                mergedObjective);
    }

    /**
     * The stable, ordered missing requirement codes of this brief.
     *
     * <p>The Offer decision, channel, message and branch decision are always required, and so is the audience
     * whenever {@link #audienceApplicable()} holds — that is, for every Campaign type except an {@code EVENT}
     * one, and for an {@code EVENT} Campaign only once a genuine additional restriction was stated. The Offer
     * description is required only when the Offer is defined and the branch rule only when branching is
     * required. An {@code EVENT} brief additionally requires its registered trigger event. The non-event type is
     * never asked: it is derived from the schedule mode, so a brief with no type simply needs its cadence and
     * then the mode-dependent schedule evidence — a timezone and an execution time for a single run, and a
     * timezone, a local time and an inclusive window for a repeated run.
     */
    public List<CampaignRequirementCode> missingRequirements() {
        List<CampaignRequirementCode> missing = new ArrayList<>();
        if (type == CampaignType.EVENT && triggerEventCode == null) {
            missing.add(CampaignRequirementCode.EVENT_REQUIRED);
        }
        if (audienceApplicable() && audience == null) {
            missing.add(CampaignRequirementCode.AUDIENCE_REQUIRED);
        }
        if (offerDecision == OfferDecision.UNKNOWN) {
            missing.add(CampaignRequirementCode.OFFER_DECISION_REQUIRED);
        } else if (offerDecision == OfferDecision.DEFINED && offerDetail == null) {
            missing.add(CampaignRequirementCode.OFFER_DETAIL_REQUIRED);
        }
        if (communicationDecision == CommunicationDecision.UNKNOWN) {
            missing.add(CampaignRequirementCode.CHANNEL_REQUIRED);
            missing.add(CampaignRequirementCode.MESSAGE_REQUIRED);
        } else if (communicationDecision == CommunicationDecision.DEFINED) {
            if (channels.isEmpty()) {
                missing.add(CampaignRequirementCode.CHANNEL_REQUIRED);
            }
            if (message == null) {
                missing.add(CampaignRequirementCode.MESSAGE_REQUIRED);
            }
        }
        if (branchDecision == BranchDecision.UNKNOWN) {
            missing.add(CampaignRequirementCode.BRANCH_DECISION_REQUIRED);
        } else if (branchDecision == BranchDecision.REQUIRED && branchRule == null) {
            missing.add(CampaignRequirementCode.BRANCH_RULE_REQUIRED);
        }
        if (type != CampaignType.EVENT) {
            if (cadence == null) {
                missing.add(CampaignRequirementCode.CADENCE_REQUIRED);
            } else {
                missing.addAll(schedule.missing(cadence));
            }
        }
        return List.copyOf(missing);
    }

    /** The first missing requirement code, or {@code null} when nothing is missing. */
    public CampaignRequirementCode nextQuestion() {
        List<CampaignRequirementCode> missing = missingRequirements();
        return missing.isEmpty() ? null : missing.get(0);
    }

    /** True only for a coherent, fully collected brief of a known type, including a registered event one. */
    public boolean completeForAssembly() {
        return type != null && missingRequirements().isEmpty();
    }

    /**
     * True when an entry-audience requirement belongs to this Campaign at all.
     *
     * <p>Every Campaign type except an event-triggered one has an entry audience that must be defined. An
     * {@code EVENT} Campaign's subject is exactly the member the registered trigger event identifies, so it has
     * no entry audience of its own: an audience restriction belongs to it only once the user stated a genuine
     * further restriction on which of those triggering members qualify. This one predicate is the authority for
     * both the missing-audience code and whether the Segment component is applicable, so a trigger-only
     * {@code EVENT} can never be blocked on a Segment it does not need.
     */
    public boolean audienceApplicable() {
        return type != CampaignType.EVENT || audience != null;
    }

    /** True for the recognized-but-unsupported Campaign type of this phase. */
    public boolean unsupported() {
        return false;
    }

    private static CommunicationDecision inferredCommunication(
            List<CampaignMessageInputs.Channel> channels, String message) {
        return (channels != null && !channels.isEmpty()) || message != null
                ? CommunicationDecision.DEFINED
                : CommunicationDecision.UNKNOWN;
    }
}
