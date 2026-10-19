package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.ReviewRole;

import java.util.List;

/**
 * The accountable owner roles that may approve a Segment Gate decision.
 *
 * <p>They are exactly the product, data and test owners who review the Golden Corpus, because a Gate approval
 * is the same accountability applied to a whole report rather than to one Case. The owner is named by an
 * opaque bounded reference, never by an email address, a credential or a tenant/user/DataScope fact.
 */
public enum GateOwnerRole {

    /** The product owner: does the evaluated capability express what the product actually asks for? */
    PRODUCT,

    /** The data owner: are the corpus, fixtures, member sets and Metadata references sound? */
    DATA,

    /** The test owner: is the evaluation discriminating, deterministic and correctly reported? */
    TEST;

    /** The review role this owner role corresponds to, so the two accountabilities cannot diverge. */
    public ReviewRole reviewRole() {
        return switch (this) {
            case PRODUCT -> ReviewRole.PRODUCT;
            case DATA -> ReviewRole.DATA;
            case TEST -> ReviewRole.TEST;
        };
    }

    /**
     * The exact roles a complete approval quorum must contain, in stable canonical order.
     *
     * <p>The order is the declaration order, so canonicalizing an arbitrary set of owner approvals by this
     * order produces identical bytes regardless of the caller's input order.
     */
    public static List<GateOwnerRole> requiredRoles() {
        return List.of(values());
    }
}