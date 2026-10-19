package com.company.campaign.builder.proposal;

import java.util.Objects;

/**
 * The closed operation vocabulary of a CampaignProposalPatch.
 *
 * <p>Omission means inherit and never deletion; {@link #DELETE} is the only deletion path and {@link #MOVE}
 * is the only way to relocate an ordered object. {@code DELETE} plus a re-created object is never equivalent
 * to an update or a move, because the two operations address different identities and are validated
 * separately.
 */
public enum ProposalOperationKind {

    UPSERT,
    DELETE,
    MOVE;

    /** Resolves the exact canonical wire spelling, or null when the value is not part of the closed set. */
    public static ProposalOperationKind fromWireName(String wireName) {
        for (ProposalOperationKind kind : values()) {
            if (Objects.equals(kind.name(), wireName)) {
                return kind;
            }
        }
        return null;
    }
}