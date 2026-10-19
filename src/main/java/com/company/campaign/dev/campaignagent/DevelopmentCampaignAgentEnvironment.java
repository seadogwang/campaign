package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceCatalog;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceTool;
import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidenceRegistry;
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
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.security.context.TrustedContextProvider;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The one development {@link CampaignAgentEnvironment} of the local slice.
 *
 * <p>Every trusted input is a host-owned explicit dependency: the routing model, the repair policy, the frozen
 * per-run Tool evidence, the accepted Segment authoring integration, the host Segment source provider and the
 * typed Skill input resolver. It wires the accepted Tool catalog, Tool handlers and Capability Packs exactly as
 * the accepted {@code CampaignSkillRouter} expects, so Message, Promotion, Journey, Schedule and Governance run
 * through their accepted T27 Tool paths and Segment runs through its accepted T16 integration — nothing here
 * constructs a {@code CampaignSkillArtifact} directly or bypasses Tool admission.
 *
 * <p>It is visibly development-only: its frozen inputs come from {@link DevelopmentCampaignAgentAssets}, and its
 * evidence is never production evidence. Schedule authoring stays authoring only — the environment never
 * executes an occurrence.
 */
public final class DevelopmentCampaignAgentEnvironment implements CampaignAgentEnvironment {

    private final CampaignRoutingModelPort routingModel;
    private final CampaignSchemaRepairPolicy repairPolicy;
    private final CampaignToolRunContext toolRunContext;
    private final SegmentAuthoringIntegration segmentIntegration;
    private final DevelopmentSegmentSourceProvider segmentSourceProvider;
    private final CampaignSkillInputResolver inputResolver;
    private final CampaignRequirementExtractorPort requirementExtractor;
    private final Clock trustedClock;
    private final TrustedContextProvider trustedContextProvider;
    private final CampaignBusinessResourceTool businessResourceTool;
    private final CampaignCompileEvidenceRegistry compileEvidenceRegistry;

    public DevelopmentCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            CampaignToolRunContext toolRunContext,
            SegmentAuthoringIntegration segmentIntegration,
            DevelopmentSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver) {
        this(routingModel, repairPolicy, toolRunContext, segmentIntegration, segmentSourceProvider,
                inputResolver, null);
    }

    public DevelopmentCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            CampaignToolRunContext toolRunContext,
            SegmentAuthoringIntegration segmentIntegration,
            DevelopmentSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor) {
        this(routingModel, repairPolicy, toolRunContext, segmentIntegration, segmentSourceProvider,
                inputResolver, requirementExtractor, null, null);
    }

    public DevelopmentCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            CampaignToolRunContext toolRunContext,
            SegmentAuthoringIntegration segmentIntegration,
            DevelopmentSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor,
            Clock trustedClock) {
        this(routingModel, repairPolicy, toolRunContext, segmentIntegration, segmentSourceProvider,
                inputResolver, requirementExtractor, trustedClock, null);
    }

    public DevelopmentCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            CampaignToolRunContext toolRunContext,
            SegmentAuthoringIntegration segmentIntegration,
            DevelopmentSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor,
            Clock trustedClock,
            TrustedContextProvider trustedContextProvider) {
        this(routingModel, repairPolicy, toolRunContext, segmentIntegration, segmentSourceProvider,
                inputResolver, requirementExtractor, trustedClock, trustedContextProvider, null);
    }

    public DevelopmentCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            CampaignToolRunContext toolRunContext,
            SegmentAuthoringIntegration segmentIntegration,
            DevelopmentSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor,
            Clock trustedClock,
            TrustedContextProvider trustedContextProvider,
            CampaignBusinessResourceTool businessResourceTool) {
        this(routingModel, repairPolicy, toolRunContext, segmentIntegration, segmentSourceProvider,
                inputResolver, requirementExtractor, trustedClock, trustedContextProvider,
                businessResourceTool, null);
    }

    public DevelopmentCampaignAgentEnvironment(
            CampaignRoutingModelPort routingModel,
            CampaignSchemaRepairPolicy repairPolicy,
            CampaignToolRunContext toolRunContext,
            SegmentAuthoringIntegration segmentIntegration,
            DevelopmentSegmentSourceProvider segmentSourceProvider,
            CampaignSkillInputResolver inputResolver,
            CampaignRequirementExtractorPort requirementExtractor,
            Clock trustedClock,
            TrustedContextProvider trustedContextProvider,
            CampaignBusinessResourceTool businessResourceTool,
            CampaignCompileEvidenceRegistry compileEvidenceRegistry) {
        this.routingModel = Objects.requireNonNull(routingModel, "routingModel");
        this.repairPolicy = repairPolicy == null ? CampaignSchemaRepairPolicy.NONE : repairPolicy;
        this.toolRunContext = toolRunContext;
        this.segmentIntegration = segmentIntegration;
        this.segmentSourceProvider = segmentSourceProvider == null
                ? DevelopmentSegmentSourceProvider.none()
                : segmentSourceProvider;
        this.inputResolver = inputResolver == null
                ? CampaignSkillInputResolver.unresolved()
                : inputResolver;
        this.requirementExtractor = requirementExtractor;
        this.trustedClock = trustedClock;
        this.trustedContextProvider = trustedContextProvider;
        this.businessResourceTool = businessResourceTool;
        this.compileEvidenceRegistry = compileEvidenceRegistry;
    }

    @Override
    public CampaignRoutingModelPort routingModel() {
        return routingModel;
    }

    @Override
    public CampaignTurnTime trustedTime() {
        Clock source = trustedClock != null ? trustedClock : Clock.systemUTC();
        return new CampaignTurnTime(
                source.instant(),
                trustedContextProvider == null
                        ? source.getZone()
                        : trustedContextProvider.trustedContext().snapshotRef().timezone());
    }

    @Override
    public CampaignRequirementExtractorPort requirementExtractor() {
        return requirementExtractor;
    }

    @Override
    public CampaignBusinessResourceCatalog businessResourceCatalog() {
        if (businessResourceTool == null || trustedContextProvider == null) {
            return CampaignBusinessResourceCatalog.empty();
        }
        var context = trustedContextProvider.trustedContext();
        return businessResourceTool.discover(context.tenantId(), context.programId());
    }

    @Override
    public CampaignSchemaRepairPolicy repairPolicy() {
        return repairPolicy;
    }

    @Override
    public CampaignToolRunContext toolRunContext() {
        return trustedContextProvider == null
                ? toolRunContext
                : DevelopmentCampaignAgentAssets.toolRunContext(trustedContextProvider.trustedContext());
    }

    @Override
    public SegmentAuthoringIntegration segmentIntegration() {
        return segmentIntegration;
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

    @Override
    public CampaignCompileEvidenceRegistry compileEvidenceRegistry() {
        return compileEvidenceRegistry;
    }

    @Override
    public T19PackIndex journeyPackIndex() {
        CampaignToolRunContext ctx = toolRunContext();
        return ctx != null ? ctx.journeyPackIndex() : null;
    }

    /** A bounded diagnostic: the wiring facts only, never a host value. */
    @Override
    public String toString() {
        return "DevelopmentCampaignAgentEnvironment[profile=" + DevelopmentCampaignAgentAssets.PROFILE
                + ", tools=" + (toolRunContext != null)
                + ", segment=" + (segmentIntegration != null) + ']';
    }
}
