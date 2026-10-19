package com.company.campaign.builder.capability.flow;

/**
 * Stable machine-readable failure codes owned by the T19 Flow node capability packs.
 *
 * <p>These values are part of the wire contract: a host or an API error envelope branches on them, so an
 * existing code is never renamed or reused for a different meaning and a new failure mode gets a new code.
 * Every code carries fixed prose only; no code ever echoes a payload value, a field name, a logical
 * identifier, a capability payload or a thrown cause.
 */
public final class FlowPackErrorCodes {

    /** The capability request itself was null, so no payload could be read. */
    public static final String INPUT_MISSING = "FLOW_PACK_INPUT_MISSING";

    /** The payload carried a field the pack's closed payload contract does not declare. */
    public static final String UNKNOWN_FIELD = "FLOW_PAYLOAD_UNKNOWN_FIELD";

    /** The payload omitted a field the pack's closed payload contract declares as required. */
    public static final String MISSING_FIELD = "FLOW_PAYLOAD_MISSING_FIELD";

    /** A declared payload field was present but not the scalar text the contract declares. */
    public static final String TYPE_INVALID = "FLOW_PAYLOAD_TYPE_INVALID";

    /** A declared payload field was present and text, but not a value this pack accepts. */
    public static final String VALUE_INVALID = "FLOW_PAYLOAD_VALUE_INVALID";

    /** A Flow component reference is not a bounded stable logical identifier. */
    public static final String REFERENCE_INVALID = "FLOW_REFERENCE_INVALID";

    /** The Wait node duration is absent, not an ISO-8601 duration, not positive or over the policy maximum. */
    public static final String WAIT_DURATION_INVALID = "FLOW_WAIT_DURATION_INVALID";

    /** The Wait node anchor is not one of the two accepted anchors, or a dynamic resume instant was declared. */
    public static final String WAIT_ANCHOR_INVALID = "FLOW_WAIT_ANCHOR_INVALID";

    /** The Condition node predicate is not one coherent typed fact predicate of the closed contract. */
    public static final String CONDITION_INVALID = "FLOW_CONDITION_INVALID";

    /** The End node terminal code is not one of the two accepted terminal codes. */
    public static final String END_TERMINAL_INVALID = "FLOW_END_TERMINAL_INVALID";

    /** The pack could not project a safe card for a payload it had already normalized. */
    public static final String PACK_BOUNDARY_INVALID = "FLOW_PACK_BOUNDARY_INVALID";

    private FlowPackErrorCodes() {
    }
}