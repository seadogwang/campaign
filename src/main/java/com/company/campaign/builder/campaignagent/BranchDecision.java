package com.company.campaign.builder.campaignagent;

/**
 * The closed, product-owned branch decision of one Campaign requirement brief.
 *
 * <p>Branching is optional, so the decision is three-valued exactly like {@link OfferDecision}: a brief may
 * not yet know whether the Campaign branches ({@link #UNKNOWN}), may have been told explicitly that it does
 * not ({@link #NONE}), or may require branching ({@link #REQUIRED}) whose bounded description is a separate,
 * conditional fact.
 *
 * <p>The enum carries no product wording, so a decoder can only accept the exact server-owned wire name.
 */
public enum BranchDecision {

    /** The user has not yet said whether the Campaign branches. */
    UNKNOWN("the user has not yet decided whether the Campaign branches"),

    /** The user explicitly stated the Campaign does not branch. */
    NONE("the user explicitly stated the Campaign does not branch"),

    /** The user stated the Campaign requires branching. */
    REQUIRED("the user stated the Campaign requires branching");

    private final String description;

    BranchDecision(String description) {
        this.description = description;
    }

    /** The bounded product definition of this decision. */
    public String description() {
        return description;
    }

    /**
     * The exact enum name, matched case-sensitively; an unknown value and a differently-cased value both
     * return {@code null}, so a caller can refuse a non-canonical wire value rather than default a decision.
     */
    public static BranchDecision fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (BranchDecision decision : values()) {
            if (decision.name().equals(name)) {
                return decision;
            }
        }
        return null;
    }

    /** True only for an accepted, explicit decision — never for {@link #UNKNOWN}. */
    public boolean decided() {
        return this != UNKNOWN;
    }
}