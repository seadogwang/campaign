package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.Objects;

/**
 * One deterministic final-Manifest revalidation.
 *
 * <p>It carries the server-stored Plan's own resolved Manifest plus the accepted trusted context, and
 * nothing a caller could supply: the validation port re-runs the frozen authoring policy against the
 * exact final material, so a Manifest that has drifted from the policy in force is rejected before any
 * mutation rather than being silently reinterpreted.
 */
public record ManifestValidationRequest(
        ResolvedCampaignManifest manifest,
        TrustedExecutionContext context
) {
    public ManifestValidationRequest {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(context, "context");
    }
}