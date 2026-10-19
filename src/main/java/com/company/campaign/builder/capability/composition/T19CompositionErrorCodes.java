package com.company.campaign.builder.capability.composition;

/**
 * The bounded, stable machine codes the deterministic T19 composition validator reports.
 *
 * <p>Every code is a closed constant: a caller branches on the code, never on the message, and no code ever
 * echoes a rejected value, a payload, a template body, a reward description, a credential or a member
 * identifier. The set is exhaustive for the T19 composition checks so a new rule is a new contract version
 * rather than an ambiguous reused code.
 */
public final class T19CompositionErrorCodes {

    /** The validator was handed no validation request. */
    public static final String INPUT_MISSING = "CAMPAIGN_T19_COMPOSITION_INPUT_MISSING";

    /** A component names a T19 capability the supplied immutable pack index does not enable. */
    public static final String CAPABILITY_NOT_IN_CATALOG = "CAMPAIGN_T19_CAPABILITY_NOT_IN_CATALOG";

    /** A component names a T19 capability whose pack is disabled for authoring. */
    public static final String CAPABILITY_NOT_ENABLED = "CAMPAIGN_T19_CAPABILITY_NOT_ENABLED";

    /** A component declares a contract version that is not the enabled pack's exact contract version. */
    public static final String CONTRACT_VERSION_MISMATCH = "CAMPAIGN_T19_CONTRACT_VERSION_MISMATCH";

    /** A component declares a composition slot that is not the enabled pack's exact frozen slot. */
    public static final String SLOT_MISMATCH = "CAMPAIGN_T19_SLOT_MISMATCH";

    /** A component's pack declares a runtime schema this build cannot author or preview. */
    public static final String RUNTIME_INCOMPATIBLE = "CAMPAIGN_T19_RUNTIME_INCOMPATIBLE";

    /** A T19 component carries a resolved reference into Segment truth. */
    public static final String SEGMENT_REFERENCE = "CAMPAIGN_T19_SEGMENT_REFERENCE";

    /** A campaign holds more than one Flow Start node. */
    public static final String START_CARDINALITY = "CAMPAIGN_T19_START_CARDINALITY";

    /** A campaign holds more than one Flow Reward node. */
    public static final String REWARD_CARDINALITY = "CAMPAIGN_T19_REWARD_CARDINALITY";

    /** A campaign holds more than one basic schedule. */
    public static final String SCHEDULE_CARDINALITY = "CAMPAIGN_T19_SCHEDULE_CARDINALITY";

    /** A campaign holds more than one governance limit. */
    public static final String GOVERNANCE_CARDINALITY = "CAMPAIGN_T19_GOVERNANCE_CARDINALITY";

    /** A Flow Message node references a logical id that no resolved message.template component owns. */
    public static final String MESSAGE_REFERENCE_UNRESOLVED = "CAMPAIGN_T19_MESSAGE_REFERENCE_UNRESOLVED";

    /** A Flow Reward node references a logical id that no resolved promotion.reference component owns. */
    public static final String REWARD_REFERENCE_UNRESOLVED = "CAMPAIGN_T19_REWARD_REFERENCE_UNRESOLVED";

    /** A Flow Reward node exists without a governance.limit component. */
    public static final String GOVERNANCE_REQUIRED = "CAMPAIGN_T19_GOVERNANCE_REQUIRED";

    /** A reward's currency is not the governance limit's currency. */
    public static final String REWARD_CURRENCY_MISMATCH = "CAMPAIGN_T19_REWARD_CURRENCY_MISMATCH";

    /** The conservative reward grant exposure exceeds the governance grant limit. */
    public static final String REWARD_GRANTS_EXCEEDED = "CAMPAIGN_T19_REWARD_GRANTS_EXCEEDED";

    /** The conservative reward face-value exposure exceeds the governance face-value limit. */
    public static final String REWARD_FACE_VALUE_EXCEEDED = "CAMPAIGN_T19_REWARD_FACE_VALUE_EXCEEDED";

    /** The relative manifest slot order is not Segment -> Message -> Promotion -> Flow -> Schedule -> Governance. */
    public static final String SLOT_ORDER_INVALID = "CAMPAIGN_T19_SLOT_ORDER_INVALID";

    private T19CompositionErrorCodes() {
    }
}