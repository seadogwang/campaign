package com.company.campaign.evaluation.segment.reference;

/**
 * The closed set of human-review claims a reference corpus manifest may make about itself.
 *
 * <p>{@code PENDING} says review is due and no owner has decided yet; {@code COMPLETE} says every required
 * owner role approved exactly the revisions the manifest pins. The claim is not taken on trust: the corpus
 * validator re-derives it from the per-case review ledgers and refuses {@code COMPLETE} while any required
 * role is still pending, and refuses {@code PENDING} the moment a ledger records a real decision. A generated
 * ledger can therefore never be mistaken for a review that happened.
 */
public enum ReferenceHumanReviewStatus {

    /** Review is due; no required owner role has decided any case yet. */
    PENDING,

    /** Every required owner role approved exactly the case and expected revisions the manifest pins. */
    COMPLETE;
}