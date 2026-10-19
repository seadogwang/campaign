package com.company.campaign.builder.compiler;

import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.List;
import java.util.Objects;

/**
 * The frozen facts an authoring policy validator may inspect.
 *
 * <p>It carries only the assembled target state — the campaign identity, the complete ordered component
 * list and the trusted execution context — so a validator is a pure function of the compile inputs and can
 * never reach a repository, a clock, a random source or a mutable registry.
 */
public record AuthoringValidationRequest(
        String campaignId,
        String tenantId,
        String programId,
        List<ResolvedComponent> components,
        TrustedExecutionContext context) {

    public AuthoringValidationRequest {
        // campaignId is null for a CREATE compile: no Campaign exists yet, so there is no identity to name.
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(programId, "programId");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        Objects.requireNonNull(context, "context");
    }
}