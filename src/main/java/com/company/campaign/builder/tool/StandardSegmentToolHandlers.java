package com.company.campaign.builder.tool;

import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.builder.agent.GroundedExplanationBuilder;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.plan.ResolvedDependency;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.IntentAudienceReference;
import com.company.campaign.segment.intent.IntentProvenance;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.reference.AudienceSummary;
import com.company.campaign.segment.reference.ReferenceDiscoveryLimits;
import com.company.campaign.segment.resolution.AudienceReferenceResolution;
import com.company.campaign.segment.resolution.AudienceReferenceResolver;
import com.company.campaign.segment.resolution.ClarificationChoiceService;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.resolution.ResolutionException;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.builder.tool.reference.ReferenceDiscoveryToolOutput;
import com.company.campaign.builder.tool.reference.ReferenceResolutionToolOutput;
import com.company.campaign.builder.tool.reference.ReferenceToolErrorCodes;
import com.company.campaign.builder.tool.reference.ReferenceToolException;
import com.company.campaign.segment.validation.SegmentValidationErrorCodes;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one server-owned implementation map of the allowed T11 Tools.
 *
 * <p>Each handler is the real execution boundary of its protected stage. It drives the accepted component —
 * the T10 resolver, the T07 validator, the T08 planner, the injected T09C Safe Preview port or the grounded
 * explanation builder — inside {@link SegmentToolHandler#execute}, and hands the accepted typed result back
 * through the host-private {@link SegmentToolArtifact}. None of them reads a backend, a trusted fact, a plan,
 * SQL, a storage identifier or a sample flag from the model-supplied {@link ToolInput}: the only model-callable
 * arguments any handler accepts are the bounded Metadata definition coordinates of {@code metadata.detail}.
 */
public final class StandardSegmentToolHandlers {

    /** The success outcome code of {@code metadata.discover}. */
    public static final String METADATA_LIST = "METADATA_LIST";

    /** The success outcome code of {@code metadata.detail}. */
    public static final String METADATA_DETAIL = "METADATA_DETAIL";

    /** The success outcome code of {@code intent.resolve} for a fully resolved Intent. */
    public static final String RESOLUTION = "INTENT_RESOLUTION";

    /** The success outcome code of {@code intent.resolve} when the deterministic resolver needs more input. */
    public static final String CLARIFICATION = "INTENT_CLARIFICATION";

    /** The success outcome code of {@code intent.resolve} for every other deterministic verdict. */
    public static final String RESOLUTION_VERDICT = "INTENT_RESOLUTION_VERDICT";

    /** The success outcome code of {@code dsl.validate}. */
    public static final String VALIDATION = "DSL_VALIDATION";

    /** The success outcome code of {@code plan.build}. */
    public static final String PLAN = "QUERY_PLAN";

    /** The success outcome code of {@code preview.safe}. */
    public static final String PREVIEW = "SAFE_PREVIEW";

    /** The success outcome code of {@code explanation.grounded}. */
    public static final String EXPLANATION = "GROUNDED_EXPLANATION";

    /** The success outcome code of the read-only audience reference discovery Tool. */
    public static final String REFERENCE_DISCOVERY = "AUDIENCE_REFERENCE_DISCOVERY";

    /** The success outcome code of the read-only audience reference resolution Tool. */
    public static final String REFERENCE_RESOLUTION = "AUDIENCE_REFERENCE_RESOLUTION";

    private StandardSegmentToolHandlers() {
    }

    /** The complete server-owned handler map for every permitted Tool identifier. */
    public static Map<String, SegmentToolHandler> all() {
        Map<String, SegmentToolHandler> handlers = new LinkedHashMap<>();
        handlers.put(SegmentToolIds.METADATA_DISCOVER, StandardSegmentToolHandlers::metadataDiscover);
        handlers.put(SegmentToolIds.METADATA_DETAIL, StandardSegmentToolHandlers::metadataDetail);
        handlers.put(SegmentToolIds.INTENT_RESOLVE, StandardSegmentToolHandlers::intentResolve);
        handlers.put(SegmentToolIds.DSL_VALIDATE, StandardSegmentToolHandlers::dslValidate);
        handlers.put(SegmentToolIds.PLAN_BUILD, StandardSegmentToolHandlers::planBuild);
        handlers.put(SegmentToolIds.PREVIEW_SAFE, StandardSegmentToolHandlers::previewSafe);
        handlers.put(SegmentToolIds.EXPLANATION_GROUNDED, StandardSegmentToolHandlers::explanationGrounded);
        handlers.put(SegmentToolIds.AUDIENCE_REFERENCE_DISCOVER,
                StandardSegmentToolHandlers::audienceReferenceDiscover);
        handlers.put(SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE,
                StandardSegmentToolHandlers::audienceReferenceResolve);
        return Map.copyOf(handlers);
    }

    // ------------------------------------------------------------------ metadata

    private static SegmentToolExecution metadataDiscover(SegmentToolRunContext run, ToolInput input) {
        if (run.trustedContext() == null || run.metadataSnapshot() == null || run.metadataQueryService() == null) {
            return missingEvidence();
        }
        TrustedExecutionContext context = run.trustedContext();
        MetadataSnapshot snapshot = run.metadataSnapshot();
        List<String> references = new ArrayList<>();
        try {
            for (MetadataKind kind : MetadataKind.values()) {
                List<MetadataDefinition> definitions =
                        run.metadataQueryService().list(context, snapshot.snapshotId(), kind);
                for (MetadataDefinition definition : definitions) {
                    references.add(definition.id());
                }
            }
        } catch (RuntimeException failure) {
            return SegmentToolExecution.failed(Disposition.DENIED, SegmentToolErrorCodes.TOOL_DENIED);
        }
        if (references.isEmpty()) {
            // No permitted definition means the authorization boundary denied the whole projection. The Tool
            // refuses rather than admitting an empty catalogue, so nothing reaches the model from this run.
            return SegmentToolExecution.failed(Disposition.DENIED, SegmentToolErrorCodes.TOOL_DENIED);
        }
        List<String> permitted = List.copyOf(references);
        return SegmentToolExecution.resolved(
                outcome(SegmentToolIds.METADATA_DISCOVER, METADATA_LIST, permitted.size(), snapshot.contentHash(),
                        permitted),
                new SegmentToolArtifact.MetadataDiscovery(permitted, snapshot.contentHash()));
    }

    private static SegmentToolExecution metadataDetail(SegmentToolRunContext run, ToolInput input) {
        if (run.trustedContext() == null || run.metadataSnapshot() == null || run.metadataQueryService() == null) {
            return missingEvidence();
        }
        Object rawKind = input.arguments().get("kind");
        Object rawId = input.arguments().get("definitionId");
        if (!(rawKind instanceof String kindText) || !(rawId instanceof String definitionId)) {
            return SegmentToolExecution.failed(Disposition.INVALID, SegmentToolErrorCodes.TOOL_ARGUMENT_INVALID);
        }
        MetadataKind kind;
        try {
            kind = MetadataKind.valueOf(kindText);
        } catch (IllegalArgumentException failure) {
            return SegmentToolExecution.failed(Disposition.INVALID, SegmentToolErrorCodes.TOOL_ARGUMENT_INVALID);
        }
        MetadataDefinition definition;
        try {
            definition = run.metadataQueryService()
                    .get(run.trustedContext(), run.metadataSnapshot().snapshotId(), kind, definitionId);
        } catch (RuntimeException failure) {
            // A missing definition, a denied one and a scope-filtered one are deliberately indistinguishable.
            return SegmentToolExecution.failed(Disposition.DENIED, SegmentToolErrorCodes.TOOL_RESOURCE_DENIED);
        }
        if (definition == null) {
            return SegmentToolExecution.failed(Disposition.DENIED, SegmentToolErrorCodes.TOOL_RESOURCE_DENIED);
        }
        return SegmentToolExecution.resolved(outcome(
                SegmentToolIds.METADATA_DETAIL, METADATA_DETAIL, 1,
                run.metadataSnapshot().contentHash(), List.of(definition.id())));
    }

    // ------------------------------------------------------------------ resolution and pipeline evidence

    private static SegmentToolExecution intentResolve(SegmentToolRunContext run, ToolInput input) {
        IntentResolver resolver = run.intentResolver();
        if (resolver == null || run.intent() == null) {
            return missingEvidence();
        }
        List<com.company.campaign.segment.resolution.ClarificationSelectionReceipt> receipts =
                run.continuationReceipts();
        ResolutionResult resolution;
        try {
            resolution = receipts.isEmpty()
                    ? resolver.resolve(run.intent())
                    : replayOrCurrent(resolver, run, receipts);
        } catch (ResolutionException rejected) {
            return SegmentToolExecution.failed(Disposition.INVALID, SegmentToolErrorCodes.TOOL_INVALID);
        } catch (RuntimeException failure) {
            return SegmentToolExecution.failed(Disposition.INVALID, SegmentToolErrorCodes.TOOL_INVALID);
        }
        if (resolution == null) {
            return missingEvidence();
        }
        List<String> references = new ArrayList<>();
        for (var clause : resolution.clauses()) {
            if (clause.accepted() != null) {
                references.add(clause.accepted());
            }
        }
        SegmentToolArtifact artifact = new SegmentToolArtifact.Resolution(resolution);
        if (resolution.isResolved()) {
            return SegmentToolExecution.resolved(
                    outcome(SegmentToolIds.INTENT_RESOLVE, RESOLUTION, references.size(),
                            resolution.baseIntentHash(), references),
                    artifact);
        }
        // The resolver ran to a deterministic verdict that is not a resolution. The public envelope stays
        // honest, and the exact clarification travels host-privately so the Agent can stop at NEEDS_INPUT.
        return SegmentToolExecution.failed(
                resolution.disposition(), resolutionCode(resolution.disposition()), artifact);
    }

    /**
     * Replays one exact continuation against the current pins, and refuses a receipt that the current Intent
     * does not need rather than accepting it as a silent no-op.
     */
    private static ResolutionResult replayOrCurrent(
            IntentResolver resolver,
            SegmentToolRunContext run,
            List<com.company.campaign.segment.resolution.ClarificationSelectionReceipt> receipts)
            throws ResolutionException {
        ClarificationChoiceService choiceService = new ClarificationChoiceService(resolver);
        try {
            return choiceService.replay(run.intent(), receipts);
        } catch (ResolutionException rejected) {
            if (resolver.resolve(run.intent()).isResolved()) {
                throw rejected;
            }
            return resolver.resolve(run.intent());
        }
    }

    private static SegmentToolExecution dslValidate(SegmentToolRunContext run, ToolInput input) {
        SegmentDsl document = run.dslDocument();
        if (document == null || run.validator() == null || run.metadataSnapshot() == null
                || run.trustedContext() == null || run.validationPolicy() == null) {
            return missingEvidence();
        }
        SegmentValidationResult validation =
                run.validator().validate(document, run.metadataSnapshot(), run.trustedContext(),
                        run.validationPolicy());
        if (validation == null) {
            return missingEvidence();
        }
        if (!validation.valid()) {
            boolean denied = validation.issues().stream()
                    .anyMatch(issue -> SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE.equals(issue.code()));
            // The genuine typed validation result travels host-privately even when it refused the document, so
            // an evaluation can compare its exact issue-code sequence. The public envelope stays bounded: the
            // artifact is absent from the model-visible result, the Agent result DTO and the audit projection.
            return SegmentToolExecution.failed(
                    denied ? Disposition.DENIED : Disposition.INVALID,
                    denied ? SegmentToolErrorCodes.TOOL_DENIED : SegmentToolErrorCodes.TOOL_INVALID,
                    new SegmentToolArtifact.Validation(validation));
        }
        List<String> references = SegmentToolEvidence.predicateNodeIds(document.predicate());
        return SegmentToolExecution.resolved(
                outcome(SegmentToolIds.DSL_VALIDATE, VALIDATION, references.size(),
                        validation.canonicalAstHash(), references),
                new SegmentToolArtifact.Validation(validation));
    }

    private static SegmentToolExecution planBuild(SegmentToolRunContext run, ToolInput input) {
        if (run.planner() == null || run.validation() == null || run.metadataSnapshot() == null
                || run.trustedContext() == null) {
            return missingEvidence();
        }
        SemanticQueryPlan plan;
        try {
            plan = run.planner().plan(run.validation(), run.metadataSnapshot(), run.trustedContext());
        } catch (QueryException denied) {
            Disposition disposition = QueryErrorCodes.QUERY_AUTHORIZATION_DENIED.equals(denied.code())
                    ? Disposition.DENIED
                    : Disposition.INVALID;
            return SegmentToolExecution.failed(
                    disposition,
                    Disposition.DENIED == disposition
                            ? SegmentToolErrorCodes.TOOL_DENIED
                            : SegmentToolErrorCodes.TOOL_INVALID);
        }
        if (plan == null) {
            return missingEvidence();
        }
        List<String> references = new ArrayList<>();
        for (ResolvedDependency dependency : plan.dependencies()) {
            references.add(dependency.id());
        }
        return SegmentToolExecution.resolved(
                outcome(SegmentToolIds.PLAN_BUILD, PLAN, references.size(), plan.planHash(), references),
                new SegmentToolArtifact.Plan(plan));
    }

    private static SegmentToolExecution previewSafe(SegmentToolRunContext run, ToolInput input) {
        SegmentDsl document = run.dslDocument();
        if (document == null || run.previewPort() == null) {
            return missingEvidence();
        }
        // This Skill version declares sampleOutputPermitted=false, so the host-owned presentation never asks
        // for a sample. A Preview implementation that returns one anyway is incoherent and fails closed.
        PreviewResponse response = run.previewPort().preview(document, new PreviewPresentation(false, true));
        if (response == null) {
            return missingEvidence();
        }
        if (response.sample() != null) {
            return SegmentToolExecution.failed(
                    Disposition.INVALID, SegmentToolErrorCodes.TOOL_SAMPLE_INCOHERENT);
        }
        List<String> references = new ArrayList<>();
        if (response.conditionStats() != null) {
            for (PreviewConditionStatistic statistic : response.conditionStats()) {
                references.add(statistic.path());
            }
        }
        return SegmentToolExecution.resolved(
                outcome(SegmentToolIds.PREVIEW_SAFE, PREVIEW, response.matchedCount(), response.contentHash(),
                        references),
                new SegmentToolArtifact.Preview(response));
    }

    private static SegmentToolExecution explanationGrounded(SegmentToolRunContext run, ToolInput input) {
        if (run.resolution() == null || run.dslDocument() == null || run.previewCard() == null) {
            return missingEvidence();
        }
        GroundedExplanation explanation = GroundedExplanationBuilder.build(
                run.resolution(), run.dslDocument().predicate(), run.previewCard());
        List<String> references = explanation.references();
        return SegmentToolExecution.resolved(
                outcome(SegmentToolIds.EXPLANATION_GROUNDED, EXPLANATION, references.size(),
                        explanation.explanationHash(), references),
                new SegmentToolArtifact.Explanation(explanation));
    }

    // ------------------------------------------------------------------ read-only audience references

    /**
     * Answers which audiences the run's Intent may reference, through the host-owned discovery directory.
     *
     * <p>It is a pure read: the candidate texts come from the already-accepted Intent rather than from a model
     * payload, so this Tool has no model-supplied argument at all, and the answer is the bounded, safe summary
     * list of the registered discovery contract. A run without a directory, without an Intent or without a
     * reference in that Intent fails closed with the accepted evidence-missing code instead of guessing.
     */
    private static SegmentToolExecution audienceReferenceDiscover(SegmentToolRunContext run, ToolInput input) {
        SegmentIntent intent = run.intent();
        AudienceReferenceDirectoryContext directory = run.referenceDirectory();
        if (directory == null || intent == null || !intent.hasAudienceReferences()) {
            return missingEvidence();
        }
        try {
            List<AudienceSummary> discovered = discoveredAudiences(directory, intent);
            ReferenceDiscoveryToolOutput output = new ReferenceDiscoveryToolOutput(discovered);
            return SegmentToolExecution.resolved(
                    outcome(SegmentToolIds.AUDIENCE_REFERENCE_DISCOVER, REFERENCE_DISCOVERY,
                            output.size(), directory.directoryHash(),
                            audienceIdentities(output.audiences())),
                    new SegmentToolArtifact.ReferencedAudiences(
                            SegmentToolIds.AUDIENCE_REFERENCE_DISCOVER, output, null, directory.directoryHash()));
        } catch (ReferenceToolException refused) {
            return SegmentToolExecution.failed(Disposition.INVALID, refused.code());
        }
    }

    /**
     * Resolves every reference the run's Intent carries to one immutable published version, or to its bounded
     * clarification, denial or unavailable outcome.
     *
     * <p>The resolver is driven here, inside the admitted handler, exactly like the Intent, validation,
     * planning and preview stages: no other code may reach it. Every accepted pin comes from the host-owned
     * directory, so a model can never choose an unchecked version, and the answer records only the one
     * deterministic disposition of each reference with its stable question codes.
     */
    private static SegmentToolExecution audienceReferenceResolve(SegmentToolRunContext run, ToolInput input) {
        SegmentIntent intent = run.intent();
        AudienceReferenceDirectoryContext directory = run.referenceDirectory();
        if (directory == null || intent == null || !intent.hasAudienceReferences()) {
            return missingEvidence();
        }
        try {
            AudienceReferenceResolver resolver = new AudienceReferenceResolver(directory);
            List<AudienceReferenceResolution> resolutions = new ArrayList<>(intent.audienceReferences().size());
            for (IntentAudienceReference reference : intent.audienceReferences()) {
                resolutions.add(resolver.resolve(
                        reference.referenceId(),
                        reference.candidateTexts(),
                        referenceText(reference.provenance()),
                        null,
                        null));
            }
            return referenceResolutionOutcome(directory, resolutions);
        } catch (ReferenceToolException refused) {
            return SegmentToolExecution.failed(Disposition.INVALID, refused.code());
        } catch (ResolutionException refused) {
            return SegmentToolExecution.failed(Disposition.INVALID, refused.code());
        }
    }

    /**
     * The bounded discovery answer of every candidate text the Intent names, in canonical audience order.
     *
     * <p>De-duplication is by audience identity so two candidate texts naming one audience answer once, and the
     * order never depends on which candidate text matched first. An over-bounded or failing directory answer is
     * refused rather than truncated, and the failure never echoes the candidate text.
     */
    private static List<AudienceSummary> discoveredAudiences(
            AudienceReferenceDirectoryContext directory, SegmentIntent intent) {
        Map<String, AudienceSummary> byId = new java.util.TreeMap<>();
        for (IntentAudienceReference reference : intent.audienceReferences()) {
            for (String text : reference.candidateTexts()) {
                List<AudienceSummary> found;
                try {
                    found = directory.directory().discover(text);
                } catch (RuntimeException failure) {
                    throw ReferenceToolException.invalid(
                            ReferenceToolErrorCodes.REFERENCE_TOOL_DIRECTORY_INVALID,
                            "The audience reference directory did not answer with acceptable material");
                }
                if (found == null || found.size() > ReferenceDiscoveryLimits.MAX_CANDIDATES) {
                    throw ReferenceToolException.invalid(
                            ReferenceToolErrorCodes.REFERENCE_TOOL_DIRECTORY_INVALID,
                            "The audience reference directory did not answer with acceptable material");
                }
                for (AudienceSummary summary : found) {
                    if (summary == null) {
                        throw ReferenceToolException.invalid(
                                ReferenceToolErrorCodes.REFERENCE_TOOL_DIRECTORY_INVALID,
                                "The audience reference directory did not answer with acceptable material");
                    }
                    byId.putIfAbsent(summary.audienceId(), summary);
                }
            }
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }

    /**
     * The one bounded public outcome of a reference resolution Tool call.
     *
     * <p>The run resolves at most one reference per Tool call in the committed flow; when an Intent carries
     * several, the first deterministic outcome is the public one and the full ordered list travels in the
     * host-private artifact. A resolution that needs input or is denied is a bounded verdict rather than a
     * failure, exactly like the Intent resolution Tool, so the Agent can terminate with the honest disposition.
     */
    private static SegmentToolExecution referenceResolutionOutcome(
            AudienceReferenceDirectoryContext directory, List<AudienceReferenceResolution> resolutions) {
        AudienceReferenceResolution first = resolutions.get(0);
        ReferenceResolutionToolOutput output = ReferenceResolutionToolOutput.of(first);
        List<String> references = new ArrayList<>();
        for (AudienceReferenceResolution resolution : resolutions) {
            references.add(resolution.referenceId());
        }
        ContentHash evidence = first.evidence() == null ? directory.directoryHash() : first.evidence().evidenceHash();
        SegmentToolArtifact.ReferencedAudiences artifact = new SegmentToolArtifact.ReferencedAudiences(
                SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE, null, output, evidence);
        if (first.isResolved()) {
            return SegmentToolExecution.resolved(
                    outcome(SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE, REFERENCE_RESOLUTION,
                            resolutions.size(), evidence, references),
                    artifact);
        }
        return SegmentToolExecution.failed(
                first.disposition(), resolutionCode(first.disposition()), artifact);
    }

    /**
     * The grounded source text of one reference, or null when its provenance is a registered default.
     *
     * <p>The dynamic-wording reading is taken from the utterance the request actually grounded in, never from a
     * model-supplied flag: a caller cannot switch the check off by omitting one.
     */
    private static String referenceText(IntentProvenance provenance) {
        if (provenance instanceof IntentProvenance.FromSourceSpan fromSpan) {
            return fromSpan.span().text();
        }
        return null;
    }

    private static List<String> audienceIdentities(List<AudienceSummary> summaries) {
        List<String> identities = new ArrayList<>(summaries.size());
        for (AudienceSummary summary : summaries) {
            identities.add(summary.audienceId());
        }
        return identities;
    }

    // ------------------------------------------------------------------ helpers

    private static SegmentToolOutcome outcome(
            String toolId, String code, int count, com.company.campaign.shared.canonical.ContentHash hash,
            List<String> references) {
        return new SegmentToolOutcome(toolId, code, count, hash, references);
    }

    private static SegmentToolExecution missingEvidence() {
        return SegmentToolExecution.failed(Disposition.INVALID, SegmentToolErrorCodes.TOOL_EVIDENCE_MISSING);
    }

    private static String resolutionCode(Disposition disposition) {
        return switch (disposition) {
            case NEEDS_INPUT -> SegmentToolErrorCodes.TOOL_NEEDS_INPUT;
            case UNSUPPORTED -> SegmentToolErrorCodes.TOOL_UNSUPPORTED;
            case DENIED -> SegmentToolErrorCodes.TOOL_DENIED;
            default -> SegmentToolErrorCodes.TOOL_INVALID;
        };
    }
}