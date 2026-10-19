package com.company.campaign.production.runtime;

/**
 * The bounded, aggregate backlog projection one narrow host observability port supplies.
 *
 * <p>It is deliberately four non-negative counters and nothing else: no tenant, member, recipient, Provider or
 * work-item identity, so a snapshot built from it can never carry a high-cardinality label. {@code leaseExpiries},
 * {@code unknownActions} and {@code needsReviewParticipants} are the three operator-visible stall kinds the
 * accepted T22/T23 state machines can leave behind, restated here as bounded counts only.
 */
public record ProviderRuntimeBacklog(
        long backlog,
        long leaseExpiries,
        long unknownActions,
        long needsReviewParticipants
) {

    public ProviderRuntimeBacklog {
        if (backlog < 0 || leaseExpiries < 0 || unknownActions < 0 || needsReviewParticipants < 0) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.OBSERVABILITY_PORT_REQUIRED,
                    "A host observability projection must carry non-negative bounded counts.");
        }
    }

    /** The one bounded empty projection, for a host that observed nothing. */
    public static ProviderRuntimeBacklog empty() {
        return new ProviderRuntimeBacklog(0L, 0L, 0L, 0L);
    }
}