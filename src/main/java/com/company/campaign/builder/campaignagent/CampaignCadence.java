package com.company.campaign.builder.campaignagent;

/**
 * The closed, product-owned execution cadence of one Campaign requirement brief.
 *
 * <p>There are exactly two values — a single execution and a daily execution — and neither is a default:
 * an unanswered cadence is a missing requirement rather than one of the two. The enum carries no product
 * wording, so a decoder can only accept the exact server-owned wire name.
 */
public enum CampaignCadence {

    /** One single execution. */
    ONE_TIME("one single execution"),

    /** Repeated execution once per day. */
    DAILY("repeated execution once per day");

    private final String description;

    CampaignCadence(String description) {
        this.description = description;
    }

    /** The bounded product definition of this cadence. */
    public String description() {
        return description;
    }

    /**
     * The exact enum name, matched case-sensitively; an unknown value and a differently-cased value both
     * return {@code null}, so a caller can refuse a non-canonical wire value rather than default a cadence.
     */
    public static CampaignCadence fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (CampaignCadence cadence : values()) {
            if (cadence.name().equals(name)) {
                return cadence;
            }
        }
        return null;
    }
}