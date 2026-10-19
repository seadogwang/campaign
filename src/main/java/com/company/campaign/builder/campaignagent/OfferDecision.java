package com.company.campaign.builder.campaignagent;

/**
 * The closed, product-owned Offer decision of one Campaign requirement brief.
 *
 * <p>An Offer is optional, so the decision is deliberately three-valued rather than a boolean: a brief may
 * not yet know whether the Campaign carries an Offer ({@link #UNKNOWN}), may have been told explicitly that
 * it carries none ({@link #NONE}), or may have an Offer ({@link #DEFINED}) whose bounded description is a
 * separate, conditional fact.
 *
 * <p>The distinction between {@code UNKNOWN} and {@code NONE} is what makes completeness deterministic: an
 * unanswered Offer question is a missing requirement, whereas an explicit "no Offer" is an accepted fact and
 * never asks again. The enum carries no product wording, so a decoder can only accept the exact server-owned
 * wire name.
 */
public enum OfferDecision {

    /** The user has not yet said whether the Campaign carries an Offer. */
    UNKNOWN("the user has not yet decided whether the Campaign carries an Offer"),

    /** The user explicitly stated the Campaign carries no Offer. */
    NONE("the user explicitly stated the Campaign carries no Offer"),

    /** The user stated the Campaign carries an Offer. */
    DEFINED("the user stated the Campaign carries an Offer");

    private final String description;

    OfferDecision(String description) {
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
    public static OfferDecision fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (OfferDecision decision : values()) {
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