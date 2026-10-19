package com.company.campaign.builder.assembly;

import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.authoring.SegmentAuthoringException;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.authoring.SegmentAuthoringProof;
import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.SegmentAuthoringResult;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.contracts.ContractPatterns;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The narrow host-only adapter from an accepted Segment artifact into the assembly workspace.
 *
 * <p>A resolved Segment is adapted by re-running the one accepted {@link SegmentAuthoringIntegration}: the
 * integration re-proves the result against its typed proof, writes the one canonical
 * {@code segment.rule-based} payload and compiles the accepted Proposal. The adapter then stages the exact
 * accepted component payload and references, and never reimplements the Segment flow, the Skill resource, the
 * envelope writer or the capability contract.
 *
 * <p>A {@code NEEDS_INPUT}, {@code UNSUPPORTED}, {@code DENIED} or {@code INVALID} Segment is preserved as a
 * bounded {@link CampaignSegmentStaging.NotAcceptable} with its status and clarification; it is never staged,
 * and no payload, Manifest or Plan is produced for it. Nothing here persists, applies, publishes or schedules
 * anything.
 */
public final class CampaignSegmentAdapter {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final SegmentAuthoringIntegration integration;
    private final String conversationId;
    private final String runId;
    private final CampaignToolCatalog toolCatalog;

    public CampaignSegmentAdapter(
            SegmentAuthoringIntegration integration,
            String conversationId,
            String runId,
            CampaignToolCatalog toolCatalog) {
        this.integration = Objects.requireNonNull(integration, "integration");
        if (conversationId == null || !IDENTIFIER.matcher(conversationId).matches()
                || runId == null || !IDENTIFIER.matcher(runId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE,
                    "A Segment adapter requires a bounded conversation and run identity.");
        }
        this.conversationId = conversationId;
        this.runId = runId;
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    }

    /**
     * Adapts one accepted resolved {@code SegmentAgentResult} paired with its typed proof.
     *
     * <p>Only {@code READY_FOR_PREVIEW} is adaptable; every other terminal disposition is preserved without a
     * payload. The result and its proof are paired through the accepted source union, so the integration still
     * re-proves that the result really is the artifact its proof holds.
     */
    public CampaignSegmentStaging stageResult(
            SegmentAgentResult result, SegmentAuthoringProof proof, SegmentAuthoringRequest request) {
        if (result == null) {
            return new CampaignSegmentStaging.Refused(CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE);
        }
        if (!result.isReadyForPreview()) {
            return terminal(result.disposition(), result.failureCode(), result.clarification());
        }
        if (proof == null) {
            return new CampaignSegmentStaging.Refused(CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE);
        }
        return stage(new SegmentAuthoringSource.AiResolved(result, proof), request);
    }

    /** Adapts one terminal {@code SegmentAgentResult} that must never be staged. */
    public CampaignSegmentStaging stageTerminalResult(SegmentAgentResult result) {
        if (result == null || result.isReadyForPreview()) {
            return new CampaignSegmentStaging.Refused(CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE);
        }
        return terminal(result.disposition(), result.failureCode(), result.clarification());
    }

    /** Adapts one accepted Segment source through the one accepted authoring integration. */
    public CampaignSegmentStaging stage(SegmentAuthoringSource source, SegmentAuthoringRequest request) {
        if (source == null || request == null) {
            return new CampaignSegmentStaging.Refused(CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE);
        }
        SegmentAuthoringRequest boundRequest = new SegmentAuthoringRequest(
                source,
                request.target(),
                request.current(),
                request.catalog(),
                request.policy(),
                request.instants(),
                request.context(),
                request.capabilityContext());
        SegmentAuthoringResult result;
        try {
            result = integration.author(boundRequest);
        } catch (SegmentAuthoringException failure) {
            // The bounded code is kept; the rejected submission and its cause are never echoed.
            return new CampaignSegmentStaging.Refused(failure.code());
        } catch (RuntimeException failure) {
            return new CampaignSegmentStaging.Refused(CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE);
        }
        if (result instanceof SegmentAuthoringResult.Terminal terminal) {
            return terminal(terminal.disposition(), terminal.code(), terminal.clarification());
        }
        SegmentAuthoringResult.Compiled compiled = (SegmentAuthoringResult.Compiled) result;
        ProposalOperation operation = compiled.patch().operations().get(0);
        ResolvedComponent component = compiled.plan().manifest().component(compiled.logicalId());
        List<com.company.campaign.builder.compiler.ResolvedCatalogReference> references =
                component == null ? List.of() : component.references();
        CampaignSkillArtifact artifact = CampaignSkillArtifact.of(
                conversationId, runId,
                CampaignSkillIds.SEGMENT_RULE_BASED, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.SEGMENT_ARTIFACT_REFERENCE, "1.0",
                toolCatalog.catalogHash(), operation, references);
        return new CampaignSegmentStaging.Staged(artifact);
    }

    private static CampaignSegmentStaging terminal(
            com.company.campaign.contracts.Disposition disposition, String code,
            com.company.campaign.segment.resolution.Clarification clarification) {
        if (disposition == null || disposition.isResolved() || code == null) {
            return new CampaignSegmentStaging.Refused(CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE);
        }
        return new CampaignSegmentStaging.NotAcceptable(disposition, code, clarification);
    }
}
