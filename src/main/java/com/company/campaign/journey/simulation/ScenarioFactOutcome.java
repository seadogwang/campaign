package com.company.campaign.journey.simulation;

/**
 * The one closed outcome a single scenario fact may declare.
 *
 * <p>A fact is either known with an exact typed canonical value, explicitly unknown, or an explicit bounded
 * error. There is no fourth state and no free-form outcome: an absent fact is not representable as a fact at
 * all, so "missing" is decided by the evaluator against a declared predicate rather than being smuggled in as
 * a value.
 */
public enum ScenarioFactOutcome {

    /** The fact is known and carries an exact typed canonical value. */
    KNOWN,

    /** The fact is explicitly unknown; it may never be treated as false. */
    UNKNOWN,

    /** The fact evaluation failed; it carries a bounded machine reason code and never branches. */
    ERROR
}
