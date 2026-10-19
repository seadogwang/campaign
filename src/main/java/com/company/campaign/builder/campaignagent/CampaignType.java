package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The closed, product-owned Campaign type vocabulary.
 *
 * <p>The type is the first Campaign-level requirement decision: it says what kind of Campaign is being built
 * before any Message, Promotion, Schedule, Journey or other Skill configuration is chosen. There are exactly
 * three values and their definitions are product schema, not test-specific heuristics:
 * <ul>
 *   <li>{@link #AD_HOC}: one bounded business initiative or occasion;</li>
 *   <li>{@link #LIFECYCLE}: an ongoing Campaign that repeatedly evaluates customer state;</li>
 *   <li>{@link #EVENT}: an individual journey triggered by a registered event.</li>
 * </ul>
 *
 * <p>The enum carries no channel, audience, discount, template or other concrete product copy, so a decoder
 * can never accept a type by matching an example word: only the exact server-owned wire name is accepted.
 */
public enum CampaignType {

    /** One bounded business initiative or occasion. */
    AD_HOC("one bounded business initiative or occasion; audience lifecycle facts, waits, or branches do not "
            + "make it LIFECYCLE"),

    /** An ongoing Campaign that repeatedly evaluates customer state. */
    LIFECYCLE("an ongoing Campaign that repeatedly re-evaluates customer eligibility or state over time"),

    /** An individual journey triggered by a registered event. */
    EVENT("an individual journey initiated when a registered business or customer event occurs");

    private final String description;

    CampaignType(String description) {
        this.description = description;
    }

    /** The bounded product definition of this Campaign type. */
    public String description() {
        return description;
    }

    /**
     * The non-event Campaign type a complete schedule mode derives, or {@code null} for an absent cadence.
     *
     * <p>For non-event Campaigns the one-time/repeated choice is not an independent business question: a single
     * execution schedule derives {@link #AD_HOC} and a repeated daily schedule derives {@link #LIFECYCLE}. This is
     * a pure function of the closed cadence vocabulary, so a decoded type is never inferred from any wording.
     */
    public static CampaignType derivedFrom(CampaignCadence cadence) {
        if (cadence == null) {
            return null;
        }
        return switch (cadence) {
            case ONE_TIME -> AD_HOC;
            case DAILY -> LIFECYCLE;
        };
    }

    /**
     * The exact enum name, matched case-sensitively; no alias and no case normalization is accepted.
     *
     * <p>This is the one strict decoder for the type wire name: an unknown value and a lowercase value are both
     * refused here, before any decision that carries them can exist.
     */
    public static CampaignType fromWireName(String name) {
        if (name == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "campaignType",
                    "A Campaign type is required.");
        }
        for (CampaignType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        throw CampaignAgentException.invalid(
                CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "campaignType",
                "The Campaign type is not server-owned.");
    }

    /**
     * The three wire names in the canonical order the answer options of a type clarification must follow.
     *
     * <p>It is the ascending wire-name order, which is the same canonical order every bounded option list must
     * satisfy, so it is derived from the closed vocabulary rather than from any user-facing copy.
     */
    public static List<String> orderedWireNames() {
        List<String> names = new ArrayList<>(values().length);
        for (CampaignType type : values()) {
            names.add(type.name());
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }
}
