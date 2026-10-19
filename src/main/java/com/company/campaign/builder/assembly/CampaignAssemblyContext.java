package com.company.campaign.builder.assembly;

import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.contracts.ContractPatterns;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The explicit host-supplied start facts of one ephemeral Campaign assembly.
 *
 * <p>It binds the exact conversation/run the workspace belongs to, the Proposal mode, the campaign identity and
 * base revisions, the bounded client presentation context and the exact closed Tool catalog the run is pinned
 * to. Every one of those is a host fact: nothing here is read from a model output or from saved Campaign state.
 *
 * <p>The workspace is in-memory and request-scoped, so this context is not a repository key, a tenant program
 * enrollment or a persisted Draft identity.
 */
public record CampaignAssemblyContext(
        String conversationId,
        String runId,
        ProposalMode mode,
        String campaignId,
        ProposalBaseRevisions baseRevisions,
        ProposalClientContext clientContext,
        CampaignToolCatalog toolCatalog
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignAssemblyContext {
        if (conversationId == null || !IDENTIFIER.matcher(conversationId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID,
                    "An assembly context requires a bounded conversation identifier.");
        }
        if (runId == null || !IDENTIFIER.matcher(runId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID,
                    "An assembly context requires a bounded run identifier.");
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(baseRevisions, "baseRevisions");
        Objects.requireNonNull(clientContext, "clientContext");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        if (mode == ProposalMode.CREATE) {
            if (campaignId != null || baseRevisions.campaignRevision() != null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.REQUEST_INVALID,
                        "A CREATE assembly must carry no campaign identity or campaign revision.");
            }
        } else if (campaignId == null || !IDENTIFIER.matcher(campaignId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID,
                    "An EDIT assembly requires a bounded campaign identity.");
        }
    }

    /** A bounded diagnostic: the run identity, the mode and the catalog version, never a trusted value. */
    @Override
    public String toString() {
        return "CampaignAssemblyContext[runId=" + runId
                + ", mode=" + mode
                + ", catalogVersion=" + toolCatalog.catalogVersion() + ']';
    }
}