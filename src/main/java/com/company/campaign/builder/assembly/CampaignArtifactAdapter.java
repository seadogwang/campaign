package com.company.campaign.builder.assembly;

import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.journey.JourneyEdgeDeclaration;
import com.company.campaign.builder.journey.JourneyNodeComponent;
import com.company.campaign.builder.journey.JourneyValidationOutcome;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import com.company.campaign.contracts.ContractPatterns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The host-only adapter that turns one final accepted Skill Tool result into one typed Proposal operation.
 *
 * <p>Only the final accepted Tool result of a Skill is eligible — {@code message.preview.safe},
 * {@code reward.preview.safe}, {@code schedule.validate} and {@code governance.validate}: a discovery, a
 * normalization, a calculation or a partial resolution result is refused, and the accepted
 * {@code message.template} / {@code promotion.reference} / {@code schedule.basic} / {@code governance.limit}
 * capability packs remain the authority on what a valid component payload is. The adapter copies the catalog,
 * version and content hashes the artifact already carries into the staged artifact, so a stale catalog or
 * version is detectable after staging, and it never reads a clock, a Provider, a repository or a database.
 */
public final class CampaignArtifactAdapter {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final String conversationId;
    private final String runId;
    private final CampaignToolCatalog toolCatalog;

    public CampaignArtifactAdapter(String conversationId, String runId, CampaignToolCatalog toolCatalog) {
        if (conversationId == null || !IDENTIFIER.matcher(conversationId).matches()
                || runId == null || !IDENTIFIER.matcher(runId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE,
                    "A host artifact adapter requires a bounded conversation and run identity.");
        }
        this.conversationId = conversationId;
        this.runId = runId;
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    }

    /** The staged Message artifact of one admitted final accepted Message Tool result, or empty. */
    public Optional<CampaignSkillArtifact> message(CampaignToolAdmission admission, ProposalIdentity target) {
        if (admission == null || !admission.resolved()
                || !CampaignToolIds.MESSAGE_PREVIEW_SAFE.equals(admission.toolId())) {
            return Optional.empty();
        }
        CampaignToolArtifact artifact =
                admission.claim(admission.callId(), admission.toolId(), toolCatalog.catalogHash());
        if (!(artifact instanceof CampaignToolArtifact.MessageSafePreview preview)) {
            return Optional.empty();
        }
        MessageTemplateVersion version = preview.version();
        ProposalOperation operation = upsert(
                CampaignSkillIds.MESSAGE_TEMPLATE, target, preview.normalized());
        return Optional.of(CampaignSkillArtifact.of(
                conversationId, runId,
                CampaignSkillIds.MESSAGE_TEMPLATE, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.MESSAGE_PREVIEW_SAFE, admission.toolVersion(),
                toolCatalog.catalogHash(), operation, referencesOf(version.resolvedReference())));
    }

    /** The staged Message artifact of one admitted exact inline channel/body preview. */
    public Optional<CampaignSkillArtifact> messageInline(
            CampaignToolAdmission admission, ProposalIdentity target) {
        return messageInline(admission, target, null);
    }

    /**
     * The staged Message artifact of one admitted inline preview, bound to a resolved template version.
     *
     * <p>When a version is supplied, the inline body is placed into the version's declared variables and
     * the payload carries the version's reference code, so the compiler can resolve it against the
     * accepted {@code message.template} contract. The version must be the accepted template for the
     * preview channel; a null version stages the legacy inline payload for backward compatibility only.
     */
    public Optional<CampaignSkillArtifact> messageInline(
            CampaignToolAdmission admission, ProposalIdentity target, MessageTemplateVersion version) {
        if (admission == null || !admission.resolved()
                || !CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE.equals(admission.toolId())) {
            return Optional.empty();
        }
        CampaignToolArtifact artifact =
                admission.claim(admission.callId(), admission.toolId(), toolCatalog.catalogHash());
        if (!(artifact instanceof CampaignToolArtifact.MessageInlineSafePreview preview)) {
            return Optional.empty();
        }
        CapabilityPayload value;
        List<ResolvedCatalogReference> references;
        if (version != null) {
            if (!preview.channel().equals(version.channel())) {
                return Optional.empty();
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("referenceCode", version.referenceCode());
            fields.put("channel", version.channel());
            fields.put("variables", Map.of("bodyText", preview.body()));
            value = CapabilityPayload.of(fields);
            references = referencesOf(version.resolvedReference());
        } else {
            value = CapabilityPayload.of(Map.of(
                    "channel", preview.channel(),
                    "body", preview.body()));
            references = List.of();
        }
        ProposalOperation operation = upsert(CampaignSkillIds.MESSAGE_TEMPLATE, target, value);
        return Optional.of(CampaignSkillArtifact.of(
                conversationId, runId,
                CampaignSkillIds.MESSAGE_TEMPLATE, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE, admission.toolVersion(),
                toolCatalog.catalogHash(), operation, references));
    }

    /** The staged Promotion artifact of one admitted final accepted Promotion Tool result, or empty. */
    public Optional<CampaignSkillArtifact> promotion(CampaignToolAdmission admission, ProposalIdentity target) {
        if (admission == null || !admission.resolved()
                || !CampaignToolIds.REWARD_PREVIEW_SAFE.equals(admission.toolId())) {
            return Optional.empty();
        }
        CampaignToolArtifact artifact =
                admission.claim(admission.callId(), admission.toolId(), toolCatalog.catalogHash());
        if (!(artifact instanceof CampaignToolArtifact.RewardSafePreview preview)) {
            return Optional.empty();
        }
        RewardVersion version = preview.version();
        ProposalOperation operation = upsert(
                CampaignSkillIds.PROMOTION_REFERENCE, target, preview.normalized());
        return Optional.of(CampaignSkillArtifact.of(
                conversationId, runId,
                CampaignSkillIds.PROMOTION_REFERENCE, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.REWARD_PREVIEW_SAFE, admission.toolVersion(),
                toolCatalog.catalogHash(), operation, referencesOf(version.resolvedReference())));
    }

    /** The staged Schedule artifact of one admitted final accepted Schedule Tool result, or empty. */
    public Optional<CampaignSkillArtifact> schedule(CampaignToolAdmission admission, ProposalIdentity target) {
        if (admission == null || !admission.resolved()
                || !CampaignToolIds.SCHEDULE_VALIDATE.equals(admission.toolId())) {
            return Optional.empty();
        }
        CampaignToolArtifact artifact =
                admission.claim(admission.callId(), admission.toolId(), toolCatalog.catalogHash());
        if (!(artifact instanceof CampaignToolArtifact.ScheduleValidation validation)) {
            return Optional.empty();
        }
        ProposalOperation operation = upsert(
                CampaignSkillIds.SCHEDULE_BASIC, target, validation.normalized());
        // The accepted schedule.basic pack resolves no catalog reference, so the staged artifact carries none
        // rather than inventing one.
        return Optional.of(CampaignSkillArtifact.of(
                conversationId, runId,
                CampaignSkillIds.SCHEDULE_BASIC, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.SCHEDULE_VALIDATE, admission.toolVersion(),
                toolCatalog.catalogHash(), operation, List.of()));
    }

    /** The staged Governance artifact of one admitted final accepted Governance Tool result, or empty. */
    public Optional<CampaignSkillArtifact> governance(CampaignToolAdmission admission, ProposalIdentity target) {
        if (admission == null || !admission.resolved()
                || !CampaignToolIds.GOVERNANCE_VALIDATE.equals(admission.toolId())) {
            return Optional.empty();
        }
        CampaignToolArtifact artifact =
                admission.claim(admission.callId(), admission.toolId(), toolCatalog.catalogHash());
        if (!(artifact instanceof CampaignToolArtifact.GovernanceValidation validation)) {
            return Optional.empty();
        }
        ProposalOperation operation = upsert(
                CampaignSkillIds.GOVERNANCE_LIMIT, target, validation.normalized());
        // The accepted governance.limit pack resolves no catalog reference, so the staged artifact carries none.
        return Optional.of(CampaignSkillArtifact.of(
                conversationId, runId,
                CampaignSkillIds.GOVERNANCE_LIMIT, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.GOVERNANCE_VALIDATE, admission.toolVersion(),
                toolCatalog.catalogHash(), operation, List.of()));
    }

    /**
     * The atomic Journey bundle of one admitted final accepted Journey validation result, or empty.
     *
     * <p>Only {@code journey.validate} is eligible: the node catalog, the composition and the pure simulation
     * are never stageable. The bundle is emitted exactly as the accepted validation produced it — every
     * normalized Flow operation and one strict topology mutation whose endpoints are the declared node
     * references — so no synthetic capability of the Skill's own name can ever appear in a Proposal.
     */
    public Optional<CampaignJourneyArtifact> journey(CampaignToolAdmission admission) {
        if (admission == null || !admission.resolved()
                || !CampaignToolIds.JOURNEY_VALIDATE.equals(admission.toolId())) {
            return Optional.empty();
        }
        CampaignToolArtifact artifact =
                admission.claim(admission.callId(), admission.toolId(), toolCatalog.catalogHash());
        if (!(artifact instanceof CampaignToolArtifact.JourneyValidation validation)) {
            return Optional.empty();
        }
        JourneyValidationOutcome outcome = validation.outcome();
        List<ProposalOperation> operations = new ArrayList<>(outcome.nodes().size());
        for (JourneyNodeComponent node : outcome.nodes()) {
            operations.add(ProposalOperation.upsertNew(node.capabilityCode(), node.ref(), node.normalized()));
        }
        List<ProposalTopologyEdge> edges = new ArrayList<>(outcome.edges().size());
        for (JourneyEdgeDeclaration edge : outcome.edges()) {
            edges.add(ProposalTopologyEdge.ofDeclaredEndpoints(
                    edge.edgeId(), edge.fromRef(), edge.toRef(), edge.branchName()));
        }
        return Optional.of(CampaignJourneyArtifact.of(
                conversationId, runId,
                CampaignSkillIds.JOURNEY_FLOW, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.JOURNEY_VALIDATE, admission.toolVersion(),
                toolCatalog.catalogHash(), operations, JourneyTopologyMutation.replace(edges), List.of()));
    }

    private static ProposalOperation upsert(
            String capabilityCode, ProposalIdentity target, com.company.campaign.builder.compiler.CapabilityPayload payload) {
        return target.isLogicalId()
                ? ProposalOperation.upsertExisting(capabilityCode, target.logicalId(), payload)
                : ProposalOperation.upsertNew(capabilityCode, target.clientRef(), payload);
    }

    private static List<ResolvedCatalogReference> referencesOf(ResolvedCatalogReference reference) {
        return List.of(reference);
    }
}
