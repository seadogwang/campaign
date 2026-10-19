package com.company.campaign.production.campaignagent;

import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.campaignagent.CampaignAssemblyContextFactory;
import com.company.campaign.security.context.TrustedExecutionContext;

/**
 * The one explicit, immutable port through which a host supplies the frozen Campaign/Skill assets of the
 * production Campaign Agent host.
 *
 * <p>It exists because this repository ships only <em>development</em> assets: the {@code
 * com.company.campaign.dev} package owns the frozen Core Loyalty development catalog, policy and readiness, and
 * production code must never import it. A production host therefore supplies its own asset set here — the Tool
 * run context, the accepted Segment authoring integration, the frozen capability catalog and authoring policy,
 * the Segment readiness fact and the assembly-context factory — together with the {@link
 * ProductionAssetProvenance} of exactly those assets.
 *
 * <p>It is deliberately immutable and closed: every accessor returns one host-owned value, there is no map, no
 * registry, no callback and no mutable state, and the port is required exactly once by the host. A host that
 * wraps the repository's frozen Core Loyalty assets must declare {@link ProductionAssetProvenance#DEVELOPMENT_ONLY},
 * which keeps readiness {@code DEVELOPMENT_ONLY} until exact human Gate approvals exist; it must never relabel
 * such assets ACTIVE.
 */
public interface ProductionCampaignAssets {

    /** The host-owned per-run Tool evidence of one trusted execution context. */
    CampaignToolRunContext toolRunContext(TrustedExecutionContext context);

    /** The accepted Segment authoring integration over the host's own authorization boundary. */
    SegmentAuthoringIntegration segmentIntegration();

    /** The frozen capability catalog the assembly context and the Tool handlers pin to. */
    FrozenCapabilityCatalog capabilityCatalog();

    /** The frozen authoring policy the compile pins to. */
    FrozenAuthoringPolicy authoringPolicy();

    /** The host-owned readiness fact of the {@code segment.rule-based} capability. */
    CapabilityReadiness segmentReadiness();

    /** The host-owned factory of the ephemeral assembly context of one conversation. */
    CampaignAssemblyContextFactory assemblyContextFactory();

    /** The provenance of exactly these assets; {@code DEVELOPMENT_ONLY} unless human Gate approvals exist. */
    ProductionAssetProvenance provenance();
}