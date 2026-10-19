package com.company.campaign.builder.compiler;

import java.util.List;

/**
 * Test-only support for one thing the accepted compiler cannot yet produce: a Plan carrying a non-empty
 * {@link RiskSummary}.
 *
 * <p>T15 records the bounded risk slot and always leaves it empty; T19 populates it. Until then the only way
 * to exercise the lifecycle's exact-risk-confirmation rule is to re-derive a genuine compiled Plan with a
 * populated summary, which needs the package-private plan factory this class sits next to.
 *
 * <p>It rebuilds the Plan from the source Plan's own immutable facts and changes nothing else, so the result
 * is a real Plan with a real hash — not a stand-in.
 */
public final class PlanRiskFixtures {

    private PlanRiskFixtures() {
    }

    /** The same Plan, re-derived with exactly the given risk codes. */
    public static CampaignPlan withRisks(CampaignPlan source, List<String> riskCodes) {
        return CampaignPlan.create(
                source.planFormatVersion(),
                source.compilerVersion(),
                source.compiledAt(),
                source.expiresAt(),
                source.tenantId(),
                source.programId(),
                source.userId(),
                source.campaignId(),
                source.baseCampaignRevision(),
                source.baseDraftId(),
                source.baseDraftRevision(),
                source.forkBase(),
                source.proposalHash(),
                source.manifestHash(),
                source.capabilityPins(),
                source.catalogVersion(),
                source.catalogHash(),
                source.policyVersion(),
                source.policyHash(),
                source.manifest(),
                source.validationIssues(),
                source.semanticDiff(),
                source.changeSet(),
                source.clientRefBindings(),
                source.previewCards(),
                source.capabilityPreviewCards(),
                new RiskSummary(riskCodes));
    }
}