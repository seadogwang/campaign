package com.company.campaign.builder.skill;

/**
 * Absolute bounds for one versioned Segment Skill definition.
 *
 * <p>Every declared Skill bound is positive and at most the corresponding absolute bound here. The bounds are
 * compile-time constants so a Skill can never raise a server-owned limit by editing its resource: the loader
 * refuses any declared value outside these ranges instead of clamping it.
 */
public final class SegmentSkillLimits {

    /** Maximum accepted Skill resource size in bytes. */
    public static final int MAX_RESOURCE_BYTES = 262_144;

    /** Maximum number of permitted, ordered Tool identifiers. */
    public static final int MAX_TOOLS = 32;

    /** Maximum number of Agent steps one Skill run may declare. */
    public static final int MAX_STEPS = 64;

    /** Maximum number of Tool calls one Skill run may declare. */
    public static final int MAX_TOOL_CALLS = 32;

    /** Maximum accepted canonical Tool payload size in bytes. */
    public static final int MAX_TOOL_PAYLOAD_BYTES = 262_144;

    /** Maximum accepted raw model output size in bytes. */
    public static final int MAX_MODEL_OUTPUT_BYTES = 262_144;

    /** Maximum accepted user utterance length in characters. */
    public static final int MAX_UTTERANCE_LENGTH = 4_096;

    /**
     * Maximum accepted conversation turns in one request.
     *
     * <p>This Skill version's formal request carries the utterance alone, so no adapter run consumes this bound
     * yet: it is the declared ceiling a future versioned history contract would have to respect, and it stays
     * part of the pinned Skill contract rather than being silently dropped here.
     */
    public static final int MAX_CONVERSATION_TURNS = 16;

    /** Maximum number of failure-behaviour entries. */
    public static final int MAX_FAILURE_CODES = 16;

    private SegmentSkillLimits() {
    }

    static void requireBounded(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.LIMIT_INVALID, "A Skill bound is outside its absolute range: " + field);
        }
    }
}