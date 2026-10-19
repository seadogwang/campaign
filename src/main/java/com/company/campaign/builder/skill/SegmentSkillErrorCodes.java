package com.company.campaign.builder.skill;

/**
 * Stable machine codes for the T11 versioned Segment Skill loader and value model.
 *
 * <p>One rejection has one code, and no code is ever renamed: a Skill load failure is a wire-observable
 * decision, so a caller must be able to branch on it without matching human prose. None of these codes is a
 * capability code — the Skill's declared capability lives in the machine-readable resource, never in Java.
 */
public final class SegmentSkillErrorCodes {

    /** The named classpath Skill resource does not exist. */
    public static final String RESOURCE_MISSING = "SEGMENT_SKILL_RESOURCE_MISSING";

    /** The Skill resource exceeds the absolute byte bound before any parse is attempted. */
    public static final String RESOURCE_OVERSIZED = "SEGMENT_SKILL_RESOURCE_OVERSIZED";

    /** The Skill bytes are not strict UTF-8. */
    public static final String ENCODING_INVALID = "SEGMENT_SKILL_ENCODING_INVALID";

    /** The Skill text is not one well-formed JSON object. */
    public static final String JSON_MALFORMED = "SEGMENT_SKILL_JSON_MALFORMED";

    /** The Skill text repeats an object key at some nesting level. */
    public static final String JSON_DUPLICATE_KEY = "SEGMENT_SKILL_JSON_DUPLICATE_KEY";

    /** The Skill object carries a field this contract does not admit. */
    public static final String UNKNOWN_FIELD = "SEGMENT_SKILL_UNKNOWN_FIELD";

    /** A required field is absent. */
    public static final String MISSING_FIELD = "SEGMENT_SKILL_MISSING_FIELD";

    /** A required field is JSON null. */
    public static final String NULL_FIELD = "SEGMENT_SKILL_NULL_FIELD";

    /** A closed-vocabulary field is spelled in a casing the contract does not admit. */
    public static final String CASING_INVALID = "SEGMENT_SKILL_CASING_INVALID";

    /** The Skill identifier is not a bounded reference identifier. */
    public static final String ID_INVALID = "SEGMENT_SKILL_ID_INVALID";

    /** The Skill contract schema version is not the one this build understands. */
    public static final String VERSION_UNSUPPORTED = "SEGMENT_SKILL_VERSION_UNSUPPORTED";

    /** A pinned contract version is not the accepted version of that contract. */
    public static final String CONTRACT_VERSION_UNKNOWN = "SEGMENT_SKILL_CONTRACT_VERSION_UNKNOWN";

    /** The capability state is not {@code EXPERIMENTAL}. */
    public static final String CAPABILITY_STATE_UNSUPPORTED = "SEGMENT_SKILL_CAPABILITY_STATE_UNSUPPORTED";

    /** The evidence class is not the accepted development-only class. */
    public static final String EVIDENCE_CLASS_INVALID = "SEGMENT_SKILL_EVIDENCE_CLASS_INVALID";

    /** A Tool identifier is not one of the closed server-owned Tool identifiers. */
    public static final String TOOL_UNKNOWN = "SEGMENT_SKILL_TOOL_UNKNOWN";

    /** A Tool identifier is repeated in the ordered allowlist. */
    public static final String TOOL_DUPLICATE = "SEGMENT_SKILL_TOOL_DUPLICATE";

    /** A Tool version is not the accepted server-owned version of that Tool. */
    public static final String TOOL_VERSION_UNKNOWN = "SEGMENT_SKILL_TOOL_VERSION_UNKNOWN";

    /** The allowlist attempts a Save/Apply/Publish/Pause/Retry/Runtime/SQL/repository/credential capability. */
    public static final String TOOL_FORBIDDEN_CAPABILITY = "SEGMENT_SKILL_TOOL_FORBIDDEN_CAPABILITY";

    /** A declared terminal Agent state is not one of the closed terminal states. */
    public static final String TERMINAL_STATE_UNSUPPORTED = "SEGMENT_SKILL_TERMINAL_STATE_UNSUPPORTED";

    /** A declared input or output disposition is not one of the closed canonical dispositions. */
    public static final String DISPOSITION_UNSUPPORTED = "SEGMENT_SKILL_DISPOSITION_UNSUPPORTED";

    /** The declared data classification is unsafe for this Skill. */
    public static final String DATA_CLASSIFICATION_UNSAFE = "SEGMENT_SKILL_DATA_CLASSIFICATION_UNSAFE";

    /** The Skill attempts to permit sample output, which this task does not allow. */
    public static final String SAMPLE_OUTPUT_FORBIDDEN = "SEGMENT_SKILL_SAMPLE_OUTPUT_FORBIDDEN";

    /** Ambiguity is declared not to stop at {@code NEEDS_INPUT}. */
    public static final String AMBIGUITY_TERMINAL_INVALID = "SEGMENT_SKILL_AMBIGUITY_TERMINAL_INVALID";

    /** A declared bound is zero, negative or above its absolute maximum. */
    public static final String LIMIT_INVALID = "SEGMENT_SKILL_LIMIT_INVALID";

    /** The declared canonical Skill hash is not the hash of the definition's semantic fields. */
    public static final String HASH_INVALID = "SEGMENT_SKILL_HASH_INVALID";

    private SegmentSkillErrorCodes() {
    }
}