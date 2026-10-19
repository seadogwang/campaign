package com.company.campaign.builder.capability.promotion;

/**
 * The closed, ordered vocabulary of stable issues the {@code promotion.reference} pack reports.
 *
 * <p>Each code names exactly one deterministic rejection so a caller can branch on it, and none of them
 * echoes a rejected value: a payload, a reference code, a quantity or an amount that failed is never
 * reproduced in an issue, because those are client-influenced input.
 */
public final class RewardReferenceErrorCodes {

    /** The payload is absent or is not the one closed shape the pack declares. */
    public static final String REWARD_REFERENCE_PAYLOAD_INVALID = "REWARD_REFERENCE_PAYLOAD_INVALID";

    /** The payload declares a field the pack contract does not declare. */
    public static final String REWARD_REFERENCE_UNKNOWN_FIELD = "REWARD_REFERENCE_UNKNOWN_FIELD";

    /** The declared quantity is not a positive whole number within its bound. */
    public static final String REWARD_REFERENCE_QUANTITY_INVALID = "REWARD_REFERENCE_QUANTITY_INVALID";

    /** The payload's reference code names no reward version in the frozen catalog. */
    public static final String REWARD_REFERENCE_UNKNOWN_REFERENCE = "REWARD_REFERENCE_UNKNOWN_REFERENCE";

    /** The exact reward version the reference code names is disabled for authoring. */
    public static final String REWARD_REFERENCE_DISABLED = "REWARD_REFERENCE_DISABLED";

    /** The exact reward version the reference code names is not runtime-available. */
    public static final String REWARD_REFERENCE_UNAVAILABLE = "REWARD_REFERENCE_UNAVAILABLE";

    /** The exact total value of the grant overflows the precision a reward amount may carry. */
    public static final String REWARD_REFERENCE_AMOUNT_OVERFLOW = "REWARD_REFERENCE_AMOUNT_OVERFLOW";

    /** The exact total value of the grant exceeds the version's per-grant maximum. */
    public static final String REWARD_REFERENCE_EXCEEDS_MAX_PER_GRANT =
            "REWARD_REFERENCE_EXCEEDS_MAX_PER_GRANT";

    private RewardReferenceErrorCodes() {
    }
}