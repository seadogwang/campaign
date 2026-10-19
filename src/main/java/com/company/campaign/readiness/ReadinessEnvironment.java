package com.company.campaign.readiness;

/**
 * The closed set of environments a readiness scope may declare.
 *
 * <p>The environment is supplied by the host, never by an evidence payload, and it is the axis that makes a
 * production claim falsifiable: production-capable evidence recorded outside {@link #PRODUCTION} is refused
 * by the evaluator, so a simulated or local run cannot be presented as a production run by editing a flag.
 */
public enum ReadinessEnvironment {

    LOCAL,
    CI,
    STAGING,
    PRODUCTION;

    /** True only for {@link #PRODUCTION}. */
    public boolean production() {
        return this == PRODUCTION;
    }
}