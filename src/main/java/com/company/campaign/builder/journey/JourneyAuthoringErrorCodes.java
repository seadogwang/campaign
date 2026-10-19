package com.company.campaign.builder.journey;

/**
 * Stable machine codes owned by the host-owned Journey authoring adapter.
 *
 * <p>Every value is a bounded machine code a Tool handler, the assembly layer or a later task branches on; no
 * code ever echoes a rejected payload value, node reference, edge identity, template body, reward secret,
 * Provider fact or thrown cause.
 */
public final class JourneyAuthoringErrorCodes {

    /** The node capability catalog is not the closed accepted Flow node set. */
    public static final String CATALOG_INVALID = "CAMPAIGN_JOURNEY_CATALOG_INVALID";

    /** A node declaration is not a bounded reference, capability and immutable payload triple. */
    public static final String NODE_INVALID = "CAMPAIGN_JOURNEY_NODE_INVALID";

    /** A Journey node declaration names a capability the closed Flow node catalog does not own. */
    public static final String NODE_CAPABILITY_UNKNOWN = "CAMPAIGN_JOURNEY_NODE_CAPABILITY_UNKNOWN";

    /** A Journey node declaration repeats one node reference. */
    public static final String NODE_DUPLICATE = "CAMPAIGN_JOURNEY_NODE_DUPLICATE";

    /** A Journey authoring request exceeds the bounded node or edge count. */
    public static final String GRAPH_LIMIT_EXCEEDED = "CAMPAIGN_JOURNEY_GRAPH_LIMIT_EXCEEDED";

    /** A Journey authoring request declares no node. */
    public static final String GRAPH_EMPTY = "CAMPAIGN_JOURNEY_GRAPH_EMPTY";

    /** A topology edge declaration is not a bounded identity, two node references and a closed branch. */
    public static final String EDGE_INVALID = "CAMPAIGN_JOURNEY_EDGE_INVALID";

    /** A topology edge declaration begins and ends at one node reference. */
    public static final String EDGE_SELF = "CAMPAIGN_JOURNEY_EDGE_SELF";

    /** A topology edge declaration repeats one edge identity. */
    public static final String EDGE_DUPLICATE = "CAMPAIGN_JOURNEY_EDGE_DUPLICATE";

    /** A topology edge endpoint names a node the same request does not declare. */
    public static final String EDGE_ENDPOINT_UNKNOWN = "CAMPAIGN_JOURNEY_EDGE_ENDPOINT_UNKNOWN";

    /** A declared reference target is not a bounded reference and capability pair. */
    public static final String REFERENCE_INVALID = "CAMPAIGN_JOURNEY_REFERENCE_INVALID";

    /** A declared reference target repeats one reference identity. */
    public static final String REFERENCE_DUPLICATE = "CAMPAIGN_JOURNEY_REFERENCE_DUPLICATE";

    /** A node declaration and a declared reference target claim one reference identity. */
    public static final String REFERENCE_CONFLICT = "CAMPAIGN_JOURNEY_REFERENCE_CONFLICT";

    /** A Journey node payload was refused by its accepted T19 Flow node pack. */
    public static final String NODE_PAYLOAD_REJECTED = "CAMPAIGN_JOURNEY_NODE_PAYLOAD_REJECTED";

    /** The frozen pack index a Journey graph must be decided against is absent. */
    public static final String PACK_INDEX_MISSING = "CAMPAIGN_JOURNEY_PACK_INDEX_MISSING";

    /** The complete node and edge set is not a valid Journey graph. */
    public static final String GRAPH_INVALID = "CAMPAIGN_JOURNEY_GRAPH_INVALID";

    /** A simulation input is not a bounded explicit virtual-clock scenario. */
    public static final String SIMULATION_INPUT_INVALID = "CAMPAIGN_JOURNEY_SIMULATION_INPUT_INVALID";

    /** A simulation failed closed on a hard limit or an unrepresentable node configuration. */
    public static final String SIMULATION_FAILED = "CAMPAIGN_JOURNEY_SIMULATION_FAILED";

    private JourneyAuthoringErrorCodes() {
    }
}