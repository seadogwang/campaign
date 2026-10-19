package com.company.campaign.evaluation.segment.contract;

/**
 * What a fixture is used for inside one case.
 *
 * <p>A {@link #PRIMARY} fixture is the ordinary discriminating dataset. A {@link #COUNTEREXAMPLE}
 * fixture is built so that a common wrong interpretation of the utterance produces a different member
 * set, which is what makes member-set comparison meaningful instead of decorative.
 *
 * <p>{@link #EXECUTION_CONTEXT} is a fixture whose payload is a trusted execution context rather than a
 * member universe. Trusted context is host-supplied by definition, so a case may only *reference* it and
 * never inline tenant, user or DataScope fields.
 */
public enum FixtureRole {

    /** An ordinary dataset whose expected member set discriminates the correct reading. */
    PRIMARY,

    /** A dataset chosen so a common wrong reading yields a different member set. */
    COUNTEREXAMPLE,

    /** A fixture carrying the host-supplied trusted execution context for the case. */
    EXECUTION_CONTEXT;

    /** True for fixtures that declare a member universe and may appear in a case's fixture list. */
    public boolean isMemberSetFixture() {
        return this != EXECUTION_CONTEXT;
    }
}