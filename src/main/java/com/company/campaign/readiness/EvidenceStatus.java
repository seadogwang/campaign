package com.company.campaign.readiness;

/**
 * The vocabulary a recorded evidence record uses about itself, distinct from the assessment status.
 *
 * <ul>
 *   <li>{@link #SATISFIED} — the evidence claims the requirement is met and carries no contradiction.</li>
 *   <li>{@link #BLOCKED} — the evidence is a truthful statement that the requirement is <em>not</em> met
 *       (a missing approval, an unavailable environment, an absent external run). It carries a bounded
 *       reason code and can never satisfy a requirement.</li>
 *   <li>{@link #INVALID} — the evidence is unusable: it contradicts itself, its scope or its origin. It
 *       makes the whole level unassessable rather than merely unmet.</li>
 * </ul>
 *
 * <p>A {@link #SATISFIED} record still has to pass the evaluator's origin, expiry, freshness and
 * production-capability checks; "satisfied" is a claim, not a verdict.
 */
public enum EvidenceStatus {

    SATISFIED,
    BLOCKED,
    INVALID;

    /** True only for {@link #SATISFIED}, the single status that may satisfy a requirement. */
    public boolean satisfied() {
        return this == SATISFIED;
    }
}