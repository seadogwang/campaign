package com.company.campaign.production.runtime;

/**
 * The one narrow, host-owned observability port of the production runtime host.
 *
 * <p>It answers exactly one bounded aggregate question: how much work is currently backed up, how many leases
 * have expired, how many actions are unresolved {@code UNKNOWN} and how many Participants need a review. It is
 * deliberately not a query API: there is no method that accepts a tenant, a member, a recipient or a time range,
 * so the runtime host can publish a bounded snapshot without ever naming a person or disclosing a provider value.
 *
 * <p>A host that cannot answer throws; the runtime host then reports the counts it cannot obtain as unavailable
 * rather than inventing a value. The port is read only when a snapshot is requested, never on the hot path of a
 * worker.
 */
public interface ProviderRuntimeObservabilityPort {

    /** The bounded aggregate backlog/stall projection the host currently observes. */
    ProviderRuntimeBacklog totals();
}