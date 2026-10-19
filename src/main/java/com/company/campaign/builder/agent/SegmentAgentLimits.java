package com.company.campaign.builder.agent;

/**
 * Absolute bounds for the bounded, model-controllable part of one Agent request.
 *
 * <p>These are the hard ceilings; the effective bound for one run is the smaller of these and the exact
 * Skill's declared bound. The values are compile-time constants, so no request and no Skill resource can raise
 * them.
 */
public final class SegmentAgentLimits {

    /** Maximum accepted utterance length in characters. */
    public static final int MAX_UTTERANCE_LENGTH = 4_096;

    /** Maximum accepted continuation receipts. */
    public static final int MAX_CONTINUATION_RECEIPTS = 16;

    /** Maximum accepted condition statistics projected onto a safe Preview card. */
    public static final int MAX_CARD_CONDITION_STATS = 128;

    /** Maximum accepted hashed sample tokens projected onto a safe Preview card. */
    public static final int MAX_CARD_SAMPLE_TOKENS = 100;

    private SegmentAgentLimits() {
    }
}