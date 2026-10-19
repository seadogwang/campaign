package com.company.campaign.journey.graph;

/**
 * Stable machine-readable failure codes owned by the T20 journey graph and topology contracts.
 *
 * <p>These values are part of the wire contract: a host or an API error envelope branches on them, so an
 * existing code is never renamed or reused for a different meaning and a new failure mode gets a new code.
 * Every code carries fixed prose only; no code ever echoes a payload value, a logical identifier, template or
 * reward data, PII or an unrestricted exception.
 */
public final class JourneyGraphErrorCodes {

    /** The topology format version is not the one supported version. */
    public static final String TOPOLOGY_VERSION_INVALID = "JOURNEY_TOPOLOGY_VERSION_INVALID";

    /** The topology edge collection is absent or exceeds the hard edge bound. */
    public static final String TOPOLOGY_EDGES_INVALID = "JOURNEY_TOPOLOGY_EDGES_INVALID";

    /** A topology edge is null, or carries a null or unbounded field. */
    public static final String TOPOLOGY_EDGE_INVALID = "JOURNEY_TOPOLOGY_EDGE_INVALID";

    /** Two topology edges share one stable logical identity. */
    public static final String TOPOLOGY_DUPLICATE_EDGE_ID = "JOURNEY_TOPOLOGY_DUPLICATE_EDGE_ID";

    /** Two topology edges describe the same from/to/branch semantic edge. */
    public static final String TOPOLOGY_DUPLICATE_SEMANTIC_EDGE = "JOURNEY_TOPOLOGY_DUPLICATE_SEMANTIC_EDGE";

    /** A topology edge names the same node as both its source and its destination. */
    public static final String TOPOLOGY_SELF_EDGE = "JOURNEY_TOPOLOGY_SELF_EDGE";

    /** The derived node set exceeds the hard node bound. */
    public static final String NODE_LIMIT_EXCEEDED = "JOURNEY_NODE_LIMIT_EXCEEDED";

    /** A flow capability component's capability is not present in the frozen pack index. */
    public static final String CAPABILITY_NOT_IN_INDEX = "JOURNEY_CAPABILITY_NOT_IN_INDEX";

    /** A flow capability component's pack is disabled for authoring. */
    public static final String CAPABILITY_DISABLED = "JOURNEY_CAPABILITY_DISABLED";

    /** A flow capability component's contract version is not the enabled pack's exact version. */
    public static final String CAPABILITY_CONTRACT_MISMATCH = "JOURNEY_CAPABILITY_CONTRACT_MISMATCH";

    /** A flow capability component's composition slot is not the frozen flow slot. */
    public static final String CAPABILITY_SLOT_MISMATCH = "JOURNEY_CAPABILITY_SLOT_MISMATCH";

    /** A flow capability component's pack declares a runtime this build cannot author or preview. */
    public static final String CAPABILITY_RUNTIME_INCOMPATIBLE = "JOURNEY_CAPABILITY_RUNTIME_INCOMPATIBLE";

    /** A flow component's payload is not the exact configuration the node kind requires. */
    public static final String NODE_PAYLOAD_INVALID = "JOURNEY_NODE_PAYLOAD_INVALID";

    /** The graph does not hold exactly one Start node. */
    public static final String START_CARDINALITY = "JOURNEY_START_CARDINALITY";

    /** The graph holds no terminal END or EXIT node. */
    public static final String TERMINAL_MISSING = "JOURNEY_TERMINAL_MISSING";

    /** The graph holds more than one Reward node. */
    public static final String REWARD_CARDINALITY = "JOURNEY_REWARD_CARDINALITY";

    /** An edge endpoint is not a node of the derived node set. */
    public static final String EDGE_ENDPOINT_UNKNOWN = "JOURNEY_EDGE_ENDPOINT_UNKNOWN";

    /** A node's incoming, outgoing or branch degree violates the node-kind contract. */
    public static final String NODE_DEGREE = "JOURNEY_NODE_DEGREE";

    /** A Condition node does not have exactly one TRUE and one FALSE out-edge and no DEFAULT. */
    public static final String CONDITION_BRANCHES = "JOURNEY_CONDITION_BRANCHES";

    /** Two edges leave one source with the same branch kind. */
    public static final String DUPLICATE_BRANCH = "JOURNEY_DUPLICATE_BRANCH";

    /** A non-condition node carries a TRUE or FALSE edge. */
    public static final String BRANCH_KIND_INVALID = "JOURNEY_BRANCH_KIND_INVALID";

    /** A node is not reachable from the Start node. */
    public static final String UNREACHABLE_NODE = "JOURNEY_UNREACHABLE_NODE";

    /** The graph holds a directed cycle. */
    public static final String CYCLE = "JOURNEY_CYCLE";

    /** A reachable node cannot reach a terminal END or EXIT node. */
    public static final String NON_TERMINATING = "JOURNEY_NON_TERMINATING";

    /** A Wait node's anchor or duration is not the exact validated configuration. */
    public static final String WAIT_CONFIG_INVALID = "JOURNEY_WAIT_CONFIG_INVALID";

    /** A Message node's reference does not target an existing message.template component. */
    public static final String MESSAGE_REFERENCE_UNRESOLVED = "JOURNEY_MESSAGE_REFERENCE_UNRESOLVED";

    /** A Reward node's reference does not target an existing promotion.reference component. */
    public static final String REWARD_REFERENCE_UNRESOLVED = "JOURNEY_REWARD_REFERENCE_UNRESOLVED";

    /** A terminal node's payload is not one of the two accepted terminal codes. */
    public static final String TERMINAL_PAYLOAD_INVALID = "JOURNEY_TERMINAL_PAYLOAD_INVALID";

    private JourneyGraphErrorCodes() {
    }
}
