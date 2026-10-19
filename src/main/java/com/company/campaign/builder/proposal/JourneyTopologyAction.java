package com.company.campaign.builder.proposal;

import java.util.Objects;

/**
 * The closed verb of one Journey-topology mutation inside a Campaign authoring request.
 *
 * <p>The set exists so an authoring request can say exactly one of two things about a Campaign's single
 * Journey topology: replace it with one complete bounded draft, or remove it outright. <em>Omission</em> is the
 * third, and only, inherit path: it is represented by the absence of the mutation, never by a third enum value,
 * so "inherit" can never be confused with "clear".
 *
 * <p>There is deliberately no {@code MERGE}, {@code PATCH} or {@code APPEND} verb: a partial topology edit
 * would let a caller change one edge without re-proving the complete graph, which is exactly the property the
 * strict contract forbids.
 */
public enum JourneyTopologyAction {

    /** Supply one complete bounded topology draft in place of the current one. */
    REPLACE,

    /** Remove the current topology outright; valid only when no Flow node remains. */
    REMOVE;

    /** Resolves the exact canonical wire spelling, or null when the value is not part of the closed set. */
    public static JourneyTopologyAction fromWireName(String wireName) {
        for (JourneyTopologyAction action : values()) {
            if (Objects.equals(action.name(), wireName)) {
                return action;
            }
        }
        return null;
    }
}