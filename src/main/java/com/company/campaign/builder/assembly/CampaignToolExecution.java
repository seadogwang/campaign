package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.Disposition;

/**
 * One bounded Tool implementation result before it is admitted into a {@link CampaignToolOutcome} envelope.
 *
 * <p>A resolved execution carries the public outcome and no code; a non-resolved execution carries one stable
 * code and no outcome. The invariant is enforced here so a contradictory execution cannot be built.
 *
 * <p>Either shape may additionally carry the host-private typed {@link CampaignToolArtifact} of the operation
 * the handler really executed: that is how an admitted Tool hands the host adapters the accepted component
 * result instead of a model-visible value.
 */
public record CampaignToolExecution(
        Disposition disposition,
        String code,
        CampaignToolOutcome outcome,
        CampaignToolArtifact artifact) {

    public CampaignToolExecution {
        if (disposition == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool execution requires a disposition.");
        }
        if (disposition.isResolved() != (outcome != null)) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "Only a resolved Tool execution may carry an outcome.");
        }
        if (disposition.isResolved() != (code == null)) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "Only a non-resolved Tool execution may carry a code.");
        }
    }

    /** A resolved execution carrying the bounded public outcome and no host-private artifact. */
    public static CampaignToolExecution resolved(CampaignToolOutcome outcome) {
        return new CampaignToolExecution(Disposition.RESOLVED, null, outcome, null);
    }

    /** A resolved execution carrying the bounded public outcome and its host-private typed artifact. */
    public static CampaignToolExecution resolved(CampaignToolOutcome outcome, CampaignToolArtifact artifact) {
        return new CampaignToolExecution(Disposition.RESOLVED, null, outcome, artifact);
    }

    /** A non-resolved execution carrying one stable code and no host-private artifact. */
    public static CampaignToolExecution failed(Disposition disposition, String code) {
        return new CampaignToolExecution(disposition, code, null, null);
    }
}