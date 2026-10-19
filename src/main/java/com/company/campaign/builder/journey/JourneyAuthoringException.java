package com.company.campaign.builder.journey;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Raised when the host-owned Journey authoring adapter rejects a declaration.
 *
 * <p>Every failure is a stable bounded code with fixed prose. The message never contains a rejected payload
 * value, a node or edge reference, a template body, a reward secret, a Provider fact or a thrown cause, so a
 * refused request cannot be used as a probe.
 */
public final class JourneyAuthoringException extends ContractException {

    private static final long serialVersionUID = 1L;

    public JourneyAuthoringException(String code) {
        super(code, messageFor(code), Map.of());
    }

    /** The one fixed message for a code; it is part of the stable contract, not diagnostics. */
    public static String messageFor(String code) {
        return switch (code) {
            case JourneyAuthoringErrorCodes.CATALOG_INVALID -> "The Journey node catalog is not supported";
            case JourneyAuthoringErrorCodes.NODE_INVALID -> "A Journey node declaration is invalid";
            case JourneyAuthoringErrorCodes.NODE_CAPABILITY_UNKNOWN ->
                    "A Journey node capability is not an accepted Flow node capability";
            case JourneyAuthoringErrorCodes.NODE_DUPLICATE -> "A Journey request repeats a node reference";
            case JourneyAuthoringErrorCodes.GRAPH_LIMIT_EXCEEDED -> "The Journey graph exceeds a hard bound";
            case JourneyAuthoringErrorCodes.GRAPH_EMPTY -> "A Journey request must declare at least one node";
            case JourneyAuthoringErrorCodes.EDGE_INVALID -> "A Journey topology edge declaration is invalid";
            case JourneyAuthoringErrorCodes.EDGE_SELF -> "A Journey topology edge is a self-edge";
            case JourneyAuthoringErrorCodes.EDGE_DUPLICATE -> "A Journey request repeats a topology edge";
            case JourneyAuthoringErrorCodes.EDGE_ENDPOINT_UNKNOWN ->
                    "A Journey topology edge names a node the request does not declare";
            case JourneyAuthoringErrorCodes.REFERENCE_INVALID -> "A Journey reference declaration is invalid";
            case JourneyAuthoringErrorCodes.REFERENCE_DUPLICATE -> "A Journey request repeats a reference";
            case JourneyAuthoringErrorCodes.REFERENCE_CONFLICT ->
                    "A Journey reference collides with a node declaration";
            case JourneyAuthoringErrorCodes.NODE_PAYLOAD_REJECTED ->
                    "A Journey node payload was refused by its accepted Flow node contract";
            case JourneyAuthoringErrorCodes.PACK_INDEX_MISSING ->
                    "A Journey graph requires the frozen pack index it is decided against";
            case JourneyAuthoringErrorCodes.GRAPH_INVALID -> "The Journey graph is not valid";
            case JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID ->
                    "A Journey simulation input is invalid";
            default -> "The Journey request is invalid";
        };
    }
}