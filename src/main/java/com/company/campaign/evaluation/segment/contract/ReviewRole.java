package com.company.campaign.evaluation.segment.contract;

import java.util.List;

/**
 * The exact owner roles that must independently review one Golden Corpus case.
 *
 * <p>Segment specification section 20 states that "Product, data and test owners review the corpus" and T06
 * requires that review evidence be recorded. The three roles are a closed set and are declared in the order
 * a review ledger stores them, so one case has exactly one entry per role and two ledgers that say the same
 * thing serialize to identical bytes.
 *
 * <p>The enum name is the wire value; no aliases are accepted, because a renamed role would silently read
 * as a missing reviewer rather than as a rename.
 */
public enum ReviewRole {

    /** The product owner: does the case express what the product actually asks for? */
    PRODUCT,

    /** The data owner: are the fixture universes, member sets and Metadata references sound? */
    DATA,

    /** The test owner: is the case discriminating, deterministic and correctly expected? */
    TEST;

    /** Every role, in deterministic review-ledger order. */
    public static List<ReviewRole> ordered() {
        return List.of(values());
    }
}