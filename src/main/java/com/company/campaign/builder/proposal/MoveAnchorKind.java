package com.company.campaign.builder.proposal;

import java.util.Objects;

/**
 * The two relative anchors a MOVE operation may use.
 *
 * <p>Ordering is always expressed relative to another stable identity, never as an array index, so the same
 * proposal means the same thing regardless of how many objects happen to precede the anchor.
 */
public enum MoveAnchorKind {

    BEFORE,
    AFTER;

    /** Resolves the exact canonical wire spelling, or null when the value is not part of the closed set. */
    public static MoveAnchorKind fromWireName(String wireName) {
        for (MoveAnchorKind kind : values()) {
            if (Objects.equals(kind.name(), wireName)) {
                return kind;
            }
        }
        return null;
    }
}