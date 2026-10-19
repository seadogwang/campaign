package com.company.campaign.production.campaignagent;

import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceCatalog;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceTool;
import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.CampaignGovernanceInputs;
import com.company.campaign.builder.campaignagent.CampaignJourneyInputs;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.campaignagent.CampaignPromotionInputs;
import com.company.campaign.builder.campaignagent.CampaignRoutingModelPort;
import com.company.campaign.builder.campaignagent.CampaignRequirementExtractorPort;
import com.company.campaign.builder.campaignagent.CampaignSchemaRepairPolicy;
import com.company.campaign.builder.campaignagent.CampaignScheduleInputs;
import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentSubmission;
import com.company.campaign.builder.campaignagent.CampaignSkillInputResolver;
import com.company.campaign.builder.campaignagent.CampaignTurnTime;
import com.company.campaign.campaignagent.CampaignSegmentSourceProvider;
import com.company.campaign.security.context.TrustedContextProvider;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The one production {@link CampaignAgentEnvironment} of the production Campaign Agent host.
 *
 * <p>Every trusted input is a host-owned explicit production dependency: the bounded routing model over the one
 * production structured model, the repair policy, the host production assets, the host Segment source provider
 * and the typed Skill input resolver over the same production model. It wires the accepted Tool catalog, Tool
 * handlers and Capability Packs exactly as the accepted {@code CampaignSkillRouter} expects, so Message,
 * Promotion, Journey, Schedule and Governance run through their accepted T27 Tool paths and Segment runs through
 * its accepted T16 integration — nothing here constructs a {@code CampaignSkillArtifact} directly or bypasses
 * Tool admission.
 *
 * <p>The per-run Tool evidence is resolved lazily from the request-scoped production trusted-context provider,
 * so it is always the trusted context of the request being served and never a fixed development identity. The
 * environment carries no development identity, no in-memory store, no fixture Preview and no simulated provider.
 */
public final class ProductionCampaignAgentEnvironment implements CampaignAgentEnvironment {

    private final CampaignRoutingModelPort routingModel;
    private final CampaignSchemaRepairPolicy repairPolicy;
    private final ProductionCampaignAssets assets;
    private final TrustedContextProvider trustedContextProvider;
    private final CampaignSegmentSourceProvider segmentSourceProvider;
    private final CampaignSkillInputResolver inputResolver;
    private final CampaignRequirementExtractorPort requirementExtractor;
    private final Clock trustedClock;
    private final CampaignBusinessResourceTool businessResourceTool;

    public ProductionCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            ProductionCampaignAssets assets,
            TrustedContextProvider trustedContextProvider,
            CampaignSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver) {
        this(routingModel, repairPolicy, assets, trustedContextProvider, segmentSourceProvider,
                inputResolver, null);
    }

    public ProductionCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            ProductionCampaignAssets assets,
            TrustedContextProvider trustedContextProvider,
            CampaignSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor) {
        this(routingModel, repairPolicy, assets, trustedContextProvider, segmentSourceProvider,
                inputResolver, requirementExtractor, null, null);
    }

    public ProductionCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            ProductionCampaignAssets assets,
            TrustedContextProvider trustedContextProvider,
            CampaignSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor,
            Clock trustedClock) {
        this(routingModel, repairPolicy, assets, trustedContextProvider, segmentSourceProvider,
                inputResolver, requirementExtractor, trustedClock, null);
    }

    public ProductionCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            ProductionCampaignAssets assets,
            TrustedContextProvider trustedContextProvider,
            CampaignSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor,
            Clock trustedClock,
            CampaignBusinessResourceTool businessResourceTool) {
        this.routingModel = Objects.requireNonNull(routingModel, "routingModel");
        this.repairPolicy = repairPolicy == null ? CampaignSchemaRepairPolicy.NONE : repairPolicy;
        this.assets = Objects.requireNonNull(assets, "assets");
        this.trustedContextProvider = Objects.requireNonNull(trustedContextProvider, "trustedContextProvider");
        this.segmentSourceProvider = segmentSourceProvider == null
                ? CampaignSegmentSourceProvider.none()
                : segmentSourceProvider;
        this.inputResolver = inputResolver == null
                ? CampaignSkillInputResolver.unresolved()
                : inputResolver;
        this.requirementExtractor = requirementExtractor;
        this.trustedClock = trustedClock;
        this.businessResourceTool = businessResourceTool;
    }

    @Override
    public CampaignRoutingModelPort routingModel() {
        return routingModel;
    }

    @Override
    public CampaignTurnTime trustedTime() {
        Clock source = trustedClock != null ? trustedClock : Clock.systemUTC();
        return new CampaignTurnTime(
                source.instant(), trustedContextProvider.trustedContext().snapshotRef().timezone());
    }

    @Override
    public CampaignRequirementExtractorPort requirementExtractor() {
        return requirementExtractor;
    }

    @Override
    public CampaignBusinessResourceCatalog businessResourceCatalog() {
        if (businessResourceTool == null) {
            return CampaignBusinessResourceCatalog.empty();
        }
        var context = trustedContextProvider.trustedContext();
        return businessResourceTool.discover(context.tenantId(), context.programId());
    }

    @Override
    public CampaignSchemaRepairPolicy repairPolicy() {
        return repairPolicy;
    }

    /** The per-run Tool evidence of exactly the trusted context this request resolved. */
    @Override
    public CampaignToolRunContext toolRunContext() {
        return assets.toolRunContext(trustedContextProvider.trustedContext());
    }

    @Override
    public SegmentAuthoringIntegration segmentIntegration() {
        return assets.segmentIntegration();
    }

    @Override
    public CampaignSegmentSubmission segmentSource(String conversationId, String runId, String utterance) {
        return segmentSource(conversationId, runId, utterance, null);
    }

    @Override
    public CampaignSegmentSubmission segmentSource(
            String conversationId,
            String runId,
            String utterance,
            CampaignSegmentContinuation continuation) {
        return segmentSourceProvider.source(conversationId, runId, utterance, continuation);
    }

    @Override
    public CampaignMessageInputs messageInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        return inputResolver.messageInputs(utterance, answers);
    }

    @Override
    public CampaignPromotionInputs promotionInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        return inputResolver.promotionInputs(utterance, answers);
    }

    @Override
    public CampaignJourneyInputs journeyInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        return inputResolver.journeyInputs(utterance, answers);
    }

    @Override
    public CampaignScheduleInputs scheduleInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        return inputResolver.scheduleInputs(utterance, answers);
    }

    @Override
    public CampaignGovernanceInputs governanceInputs(String utterance, List<CampaignClarificationAnswer> answers) {
        return inputResolver.governanceInputs(utterance, answers);
    }

    /** A bounded diagnostic: the wiring facts only, never a host value. */
    @Override
    public String toString() {
        return "ProductionCampaignAgentEnvironment[provenance=" + assets.provenance()
                + ", tools=" + (trustedContextProvider != null)
                + ", segment=" + (assets.segmentIntegration() != null) + ']';
    }
}
