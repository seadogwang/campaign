package com.company.campaign.readiness.adapter;

import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.EvidenceSubmission;
import com.company.campaign.readiness.ReadinessHostContext;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessSupport;
import com.company.campaign.readiness.RequirementEvidence;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;

/**
 * The trusted projector for the T24 frontend environment and evaluation assets.
 *
 * <p>A T24 evaluation asset is never production Gate evidence: {@link #PRODUCTION_GATE_EVIDENCE} is
 * {@code false} and every record this projector emits carries {@code productionGateEvidence=false}. An asset
 * therefore cannot prove browser end-to-end coverage or a LIVE acceptance scenario, however many cases it
 * declares — it is regression evidence for the frontend contract, and a real browser run is a different,
 * separately observed fact.
 */
public final class UiEnvironmentEvidenceProjector {

    /** True when a T24 environment asset is frozen development evidence rather than a production claim. */
    public static final boolean PRODUCTION_GATE_EVIDENCE = false;

    /** Evidence identifier suffix for the browser end-to-end requirement. */
    public static final String BROWSER_SUFFIX = "-browser-e2e";

    /** Evidence identifier suffix for the production-like acceptance-scenario requirement. */
    public static final String ACCEPTANCE_SUFFIX = "-ui-live-acceptance";

    private UiEnvironmentEvidenceProjector() {
    }

    /**
     * The bounded frontend environment facts a host may state.
     *
     * @param assetId a bounded identifier for the asset, such as the T24 cases asset id
     * @param assetReference the non-sensitive reference of the asset or run, such as a frozen asset path
     * @param assetHash the exact hash of the asset or run report
     * @param evaluationAsset true when the source is a frozen evaluation asset under {@code evaluation/...}
     * @param browserEndToEndRun true only when a real browser end-to-end run was actually performed
     */
    public record UiEvidenceFacts(
            String assetId,
            String assetReference,
            ContentHash assetHash,
            boolean evaluationAsset,
            boolean browserEndToEndRun) {

        public UiEvidenceFacts {
            ReadinessSupport.requireId(assetId, "assetId");
            ReadinessSupport.requireReference(assetReference, "assetReference");
            ReadinessSupport.requireNonNull(assetHash, "assetHash");
        }
    }

    /** Projects the browser end-to-end requirement and the production-like acceptance requirement. */
    public static List<RequirementEvidence> project(
            ReadinessHostContext context,
            String evidenceIdPrefix,
            UiEvidenceFacts facts,
            Instant observedAt,
            Instant expiresAt) {
        ReadinessHostContext host = ReadinessSupport.requireNonNull(context, "context");
        UiEvidenceFacts value = ReadinessSupport.requireNonNull(facts, "facts");
        String prefix = ReadinessSupport.requireId(evidenceIdPrefix, "evidenceIdPrefix");
        ReadinessSupport.requireInstant(observedAt, "observedAt");
        boolean production = host.environment().production();

        // A frozen evaluation asset can never be the origin of a satisfied requirement.
        boolean browserProved = value.browserEndToEndRun() && !value.evaluationAsset();
        EvidenceSubmission browser = new EvidenceSubmission(
                prefix + BROWSER_SUFFIX,
                ReadinessRequirement.LOCAL_BROWSER_END_TO_END,
                browserProved ? EvidenceStatus.SATISFIED : EvidenceStatus.BLOCKED,
                browserProved ? (production ? EvidenceOrigin.PRODUCTION : EvidenceOrigin.REAL_LOCAL)
                        : EvidenceOrigin.EVALUATION_ASSET,
                value.assetId(),
                value.assetHash().value(),
                value.assetHash(),
                value.assetReference(),
                browserProved && production,
                observedAt,
                expiresAt,
                browserProved ? null : ReadinessReasonCodes.BROWSER_END_TO_END_ABSENT);

        EvidenceSubmission acceptance = new EvidenceSubmission(
                prefix + ACCEPTANCE_SUFFIX,
                ReadinessRequirement.LIVE_ACCEPTANCE_SCENARIOS_PRODUCTION_LIKE,
                EvidenceStatus.BLOCKED,
                value.evaluationAsset() ? EvidenceOrigin.EVALUATION_ASSET : EvidenceOrigin.REAL_LOCAL,
                value.assetId(),
                value.assetHash().value(),
                value.assetHash(),
                value.assetReference(),
                false,
                observedAt,
                expiresAt,
                value.evaluationAsset()
                        ? ReadinessReasonCodes.UI_ASSETS_NOT_PRODUCTION_EVIDENCE
                        : ReadinessReasonCodes.PRODUCTION_RUN_NOT_PERFORMED);

        return List.of(
                RequirementEvidence.of(host.environment(), browser),
                RequirementEvidence.of(host.environment(), acceptance));
    }

    /** True when a T24 environment asset is admissible as production Gate evidence. Never. */
    public static boolean admissibleAsProductionGateEvidence() {
        return PRODUCTION_GATE_EVIDENCE;
    }
}