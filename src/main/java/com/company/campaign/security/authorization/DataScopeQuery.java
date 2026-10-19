package com.company.campaign.security.authorization;

import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.Objects;

/**
 * Input to a {@link DataScopePolicy}.
 *
 * <p>The context and resource are the already-validated host values; a later Metadata, Segment or query
 * implementation uses them to build the DataScope predicate without accepting scope from a payload.
 */
public record DataScopeQuery(
        TrustedExecutionContext context,
        ResourceKind resourceKind,
        String resourceId
) {
    public DataScopeQuery {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(resourceKind, "resourceKind");
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
    }
}