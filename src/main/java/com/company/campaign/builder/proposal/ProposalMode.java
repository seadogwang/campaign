package com.company.campaign.builder.proposal;

import java.util.Objects;

/**
 * The two modes a CampaignProposalPatch can declare.
 *
 * <p>{@link #CREATE} must not carry a campaign identifier or a campaign base revision; {@link #EDIT} must
 * carry both. The set is closed and the wire spelling is the exact enum name, so {@code create},
 * {@code Create} and {@code EDIT } are all refused rather than silently normalized.
 */
public enum ProposalMode {

    CREATE,
    EDIT;

    /** Resolves the exact canonical wire spelling, or null when the value is not part of the closed set. */
    public static ProposalMode fromWireName(String wireName) {
        for (ProposalMode mode : values()) {
            if (Objects.equals(mode.name(), wireName)) {
                return mode;
            }
        }
        return null;
    }
}