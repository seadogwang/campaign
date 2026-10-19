package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CapabilityPin;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * One frozen-input compatibility recheck of a server-stored Plan.
 *
 * <p>It carries only facts the Plan already owns — its catalog and policy version/hash, its capability
 * contract pins and the trusted context — so the port compares the Plan against the frozen inputs in force
 * rather than accepting a caller's claim that they still match. A Plan whose pins moved is stale, not
 * silently reinterpreted.
 */
public record FrozenCompatibilityQuery(
        String catalogVersion,
        ContentHash catalogHash,
        String policyVersion,
        ContentHash policyHash,
        List<CapabilityPin> capabilityPins,
        TrustedExecutionContext context
) {
    public FrozenCompatibilityQuery {
        Objects.requireNonNull(catalogVersion, "catalogVersion");
        Objects.requireNonNull(catalogHash, "catalogHash");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        capabilityPins = List.copyOf(Objects.requireNonNull(capabilityPins, "capabilityPins"));
        Objects.requireNonNull(context, "context");
    }
}