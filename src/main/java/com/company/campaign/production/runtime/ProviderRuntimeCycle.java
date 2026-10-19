package com.company.campaign.production.runtime;

/**
 * The one bounded cycle the production scheduler drives.
 *
 * <p>It is a narrow functional contract beside {@link ProviderRuntimeCycleService} so a scheduler can be driven
 * and verified against a deterministic double without sleeping, and so the scheduler owns no knowledge of the
 * Runtime or Action workers beyond "run one bounded cycle for this server-generated worker identity".
 */
@FunctionalInterface
public interface ProviderRuntimeCycle {

    /** Runs one bounded cycle for the given stable worker identity and returns its bounded aggregate report. */
    ProviderRuntimeCycleReport runCycle(String workerId);
}