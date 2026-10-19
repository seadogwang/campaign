package com.company.campaign.evaluation.segment.reference;

/**
 * The closed set of release claims a reference corpus manifest may make about itself.
 *
 * <p>The three constants are the honest automated states of an unreviewed capability, and there is deliberately
 * no {@code PASSED}, {@code APPROVED} or {@code ACTIVE}: those are Gate <em>decisions</em> over a corpus, a
 * model run and genuine owner approvals, so a corpus file that spelled one would be claiming an approval it
 * cannot produce. Because the constant does not exist, such a file fails to load rather than being caught by
 * a rule that someone could later relax.
 */
public enum ReferenceReleaseStatus {

    /** The corpus is a development artifact: every case is expected, none is approved. */
    DEVELOPMENT_ONLY,

    /** A pinned input moved, so the previous deterministic evidence no longer covers this revision. */
    EVALUATION_REQUIRED,

    /** The corpus is machine-complete under its own rules and still awaiting genuine human review. */
    CANDIDATE_COMPLETE;
}