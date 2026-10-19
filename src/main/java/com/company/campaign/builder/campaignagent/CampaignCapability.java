package com.company.campaign.builder.campaignagent;

/**
 * The closed, server-owned set of Campaign capabilities one conversation may decide.
 *
 * <p>Each capability maps to one {@link CampaignComponentCode component group} that owns its
 * requirement facts and Skill validations. The mapping is host-owned and deterministic:
 * {@link CampaignComponentCode#fromCapability(CampaignCapability)} is the one authority.
 *
 * <p>EVENT, MESSAGE and PROMOTION are deterministic-resource capabilities: a REQUIRED
 * decision for any of them must bind one or more real, selectable resources from the host
 * business-resource catalog before the component can reach READY.
 */
public enum CampaignCapability {

    /** Target audience definition, resolved by the Segment component. */
    SEGMENT,

    /** Trigger event selection, gated by the host trigger-event catalog. */
    EVENT,

    /** Delivery-channel and message-content decisions, gated by the host channel catalog. */
    MESSAGE,

    /** Coupon/reward/promotion decisions, gated by the host promotion catalog. */
    PROMOTION,

    /** Journey flow, branching and step definitions. */
    FLOW,

    /** Execution schedule, cadence and time-window definitions. */
    SCHEDULE;

    /** The exact stable machine code. */
    public String code() {
        return name();
    }

    /**
     * The declared capability whose exact code equals the argument, or {@code null} when none does.
     *
     * <p>It is the one strict decoder for a capability wire name: an unknown value returns
     * {@code null} rather than selecting a capability, so a model-invented capability is
     * silently refused.
     */
    public static CampaignCapability fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (CampaignCapability capability : values()) {
            if (capability.name().equals(code)) {
                return capability;
            }
        }
        return null;
    }

    /**
     * True when this capability requires real catalog resource binding.
     *
     * <p>EVENT, MESSAGE and PROMOTION decisions gate on the host business-resource catalog:
     * a REQUIRED decision must bind at least one selectable resource before the owning
     * component can reach READY. The model may only judge whether the capability is needed;
     * it may never invent a resource code.
     */
    public boolean requiresResourceBinding() {
        return this == EVENT || this == MESSAGE || this == PROMOTION;
    }

    /**
     * The {@link CampaignBusinessResourceType} this capability maps to for resource binding,
     * or {@code null} when this capability does not require a resource.
     */
    public CampaignBusinessResourceType resourceType() {
        return switch (this) {
            case EVENT -> CampaignBusinessResourceType.TRIGGER_EVENT;
            case MESSAGE -> CampaignBusinessResourceType.COMMUNICATION_CHANNEL;
            case PROMOTION -> CampaignBusinessResourceType.PROMOTION;
            default -> null;
        };
    }
}