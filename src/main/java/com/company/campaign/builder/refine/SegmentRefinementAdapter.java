package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.GroundedExplanation;
import com.company.campaign.builder.agent.SegmentAuditToolCode;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.agent.SegmentRunTimings;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.builder.tool.SegmentToolAdmission;
import com.company.campaign.builder.tool.SegmentToolArtifact;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.builder.tool.SegmentToolException;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.builder.tool.SegmentToolInvoker;
import com.company.campaign.builder.tool.SegmentToolRunContext;
import com.company.campaign.builder.tool.StandardSegmentToolHandlers;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.ToolEnvelope;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The framework-independent T12 refinement orchestrator.
 *
 * <p>It accepts the current host-owned typed DSL plus a strict typed refinement patch and the host-owned T11
 * environment, then: resolves the trusted context and the exact permitted Metadata snapshot from injected host
 * services; runs the admitted {@code metadata.discover} Tool before any further protected work; applies the
 * deterministic patch as a host-owned <em>non-Tool</em> structural stage; and executes genuine T07 validation,
 * T08 planning and T09C Safe Preview through the admitted {@code dsl.validate}, {@code plan.build} and
 * {@code preview.safe} Tools. A grounded explanation is rebuilt through the admitted
 * {@code explanation.grounded} Tool only when a conservative clause-to-node proof shows the conversation's prior
 * accepted resolution still grounds the refined document; otherwise no explanation is produced.
 *
 * <p>The admitted Tool handler is the control point of every protected stage: the Agent never runs the
 * Validator, the Planner, the Preview or the explanation builder itself, and a Tool that is unknown, absent from
 * the Skill, stale, over limit, out of order or post-terminal is refused <em>before</em> its implementation is
 * entered. Refinement legitimately skips {@code intent.resolve} — this adapter resolves no Intent — and the
 * invoker permits that host-owned forward rank jump while still refusing every reversed, repeated or
 * over-budget call.
 *
 * <p>Every terminal outcome is exactly {@code READY_FOR_PREVIEW}, {@code DENIED} or {@code INVALID}, carries
 * complete audit evidence, and binds the base/refined DSL hashes, the canonical validation hash, the plan hash,
 * the Preview hash, the Metadata/Skill/Tool pins, the trusted time pins and an audit-safe patch fingerprint.
 *
 * <p>The adapter reads no wall clock, random source, default locale or timezone, thread-local or global context:
 * run and conversation identity and every time pin are host inputs, and the Safe Preview is always invoked with
 * {@code includeSample=false} because the committed Skill forbids samples.
 */
public final class SegmentRefinementAdapter {

    private static final String TRANSITION_DISCOVERING = "REFINEMENT_DISCOVER_METADATA";
    private static final String TRANSITION_APPLYING = "REFINEMENT_APPLY_PATCH";
    private static final String TRANSITION_VALIDATING = "REFINEMENT_VALIDATE_DSL";
    private static final String TRANSITION_PLANNING = "REFINEMENT_BUILD_PLAN";
    private static final String TRANSITION_PREVIEWING = "REFINEMENT_SAFE_PREVIEW";
    private static final String TRANSITION_READY = "REFINEMENT_READY_FOR_PREVIEW";

    private final SegmentRefinementEnvironment environment;
    private final SegmentRefinementEditor editor = new SegmentRefinementEditor();
    private final SegmentValidator validator;
    private final SegmentQueryPlanner planner;

    public SegmentRefinementAdapter(SegmentRefinementEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.validator = new SegmentValidator(environment.authorizationService());
        this.planner = new SegmentQueryPlanner(environment.authorizationService());
    }

    /** Runs the complete deterministic refinement flow for one envelope. */
    public SegmentRefinementResult invoke(AgentEnvelope<SegmentRefinementRequest> envelope) {
        SegmentRefinementRequest request = validateEnvelope(envelope);
        String runId = envelope.agentRunId();
        String conversationId = envelope.conversationId();
        SegmentSkillDefinition skill = environment.skill();
        ContentHash patchFingerprint = request.patch().patchFingerprint();
        ContentHash baseDslHash = request.currentDsl().editableDslHash();

        TrustedExecutionContext context;
        try {
            context = environment.trustedContextProvider().trustedContext();
        } catch (RuntimeException failure) {
            context = null;
        }
        if (context == null) {
            return publishEarly(runId, conversationId, baseDslHash, patchFingerprint, Disposition.DENIED,
                    SegmentRefinementAdapterErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE);
        }

        MetadataSnapshot snapshot;
        try {
            snapshot = environment.snapshotSource().resolve(context);
        } catch (RuntimeException failure) {
            snapshot = null;
        }
        if (snapshot == null || !snapshot.snapshotId().equals(context.snapshotRef().metadataSnapshotId())) {
            return publishEarly(runId, conversationId, baseDslHash, patchFingerprint, Disposition.INVALID,
                    SegmentRefinementAdapterErrorCodes.METADATA_PIN_MISMATCH);
        }
        if (!snapshot.tenantId().equals(context.tenantId()) || !snapshot.programId().equals(context.programId())) {
            return publishEarly(runId, conversationId, baseDslHash, patchFingerprint, Disposition.DENIED,
                    SegmentRefinementAdapterErrorCodes.METADATA_PIN_MISMATCH);
        }

        Run run = new Run(snapshot, context, request);
        run.machine.start();

        // 1. Discover and authorize the exact permitted Metadata before any further protected work.
        run.machine.transition(SegmentRefinementState.DISCOVERING, TRANSITION_DISCOVERING,
                SegmentToolIds.METADATA_DISCOVER);
        SegmentToolAdmission discovery = run.call(SegmentToolIds.METADATA_DISCOVER);
        if (!discovery.resolved()) {
            return terminalFromTool(run, runId, conversationId, discovery, SegmentToolIds.METADATA_DISCOVER);
        }
        SegmentToolArtifact discovered = claim(run, discovery, SegmentToolIds.METADATA_DISCOVER);
        if (!(discovered instanceof SegmentToolArtifact.MetadataDiscovery metadata)) {
            return terminal(run, runId, conversationId, Disposition.INVALID,
                    SegmentRefinementAdapterErrorCodes.TOOL_REJECTED);
        }
        if (!metadata.snapshotHash().equals(snapshot.contentHash())) {
            return terminal(run, runId, conversationId, Disposition.INVALID,
                    SegmentRefinementAdapterErrorCodes.METADATA_PIN_MISMATCH);
        }

        // 2. The host-owned, non-Tool structural stage: re-prove the binding and apply the patch atomically.
        run.machine.transition(SegmentRefinementState.APPLYING_PATCH, TRANSITION_APPLYING, null);
        if (!bindsExactly(request.currentDsl(), snapshot)) {
            return terminal(run, runId, conversationId, Disposition.INVALID,
                    SegmentRefinementAdapterErrorCodes.DSL_BINDING_MISMATCH);
        }
        SegmentRefinementEditor.Applied applied;
        try {
            applied = editor.applyWithEvidence(
                    request.currentDsl(), request.patch(), request.referenceEvidence());
        } catch (SegmentRefinementException refused) {
            // The contract's own bounded code is surfaced, so a caller can tell a stale base hash from a
            // missing anchor without reading a free-text message.
            return terminal(run, runId, conversationId, Disposition.INVALID, refused.code());
        }
        SegmentDsl refined = applied.document();
        run.document = refined;
        if (!observe(() -> environment.stageObserver().refinementApplied(refined))) {
            return unobservable(run, runId, conversationId);
        }
        // The typed receipt of the patch the editor just accepted is observed beside the document, so an
        // evaluation can compare the refinement the run actually applied instead of diffing two trees. It is
        // built from the accepted patch, the accepted documents and the editor's own operation-time parent
        // evidence, and it changes no result.
        SegmentRefinementReceipt receipt = SegmentRefinementReceipt.of(
                request.currentDsl(), request.patch(), refined, applied.operationParents());
        if (!observe(() -> environment.stageObserver().refinementReceiptAccepted(receipt))) {
            return unobservable(run, runId, conversationId);
        }

        // 3. Validate and canonicalize inside the admitted dsl.validate Tool.
        run.machine.transition(SegmentRefinementState.VALIDATING, TRANSITION_VALIDATING, SegmentToolIds.DSL_VALIDATE);
        SegmentToolAdmission validationAdmission = run.call(SegmentToolIds.DSL_VALIDATE);
        SegmentToolArtifact validationArtifact = validationAdmission.hasArtifact()
                ? claim(run, validationAdmission, SegmentToolIds.DSL_VALIDATE)
                : null;
        if (validationArtifact instanceof SegmentToolArtifact.Validation observedValidation) {
            run.validation = observedValidation.validation();
            SegmentValidationResult validation = observedValidation.validation();
            if (!observe(() -> environment.stageObserver().validationAccepted(validation))) {
                return unobservable(run, runId, conversationId);
            }
        }
        if (!validationAdmission.resolved()) {
            return terminalFromTool(run, runId, conversationId, validationAdmission, SegmentToolIds.DSL_VALIDATE);
        }
        if (!(validationArtifact instanceof SegmentToolArtifact.Validation)) {
            return terminal(run, runId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.VALIDATION_FAILURE, SegmentRefinementAdapterErrorCodes.VALIDATION_REJECTED));
        }

        // 4. Plan inside the admitted plan.build Tool.
        run.machine.transition(SegmentRefinementState.PLANNING, TRANSITION_PLANNING, SegmentToolIds.PLAN_BUILD);
        SegmentToolAdmission planAdmission = run.call(SegmentToolIds.PLAN_BUILD);
        if (!planAdmission.resolved()) {
            return terminalFromTool(run, runId, conversationId, planAdmission, SegmentToolIds.PLAN_BUILD);
        }
        SegmentToolArtifact planArtifact = claim(run, planAdmission, SegmentToolIds.PLAN_BUILD);
        if (!(planArtifact instanceof SegmentToolArtifact.Plan admittedPlan)) {
            return terminal(run, runId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.PLANNING_FAILURE, SegmentRefinementAdapterErrorCodes.PLANNING_FAILED));
        }
        run.plan = admittedPlan.plan();
        SemanticQueryPlan plan = admittedPlan.plan();
        if (!observe(() -> environment.stageObserver().planAccepted(plan))) {
            return unobservable(run, runId, conversationId);
        }

        // 5. Run the genuine T09C Safe Preview inside the admitted preview.safe Tool, with samples disabled.
        run.machine.transition(SegmentRefinementState.PREVIEWING, TRANSITION_PREVIEWING, SegmentToolIds.PREVIEW_SAFE);
        SegmentToolAdmission previewAdmission = run.call(SegmentToolIds.PREVIEW_SAFE);
        if (!previewAdmission.resolved()) {
            return terminalFromTool(run, runId, conversationId, previewAdmission, SegmentToolIds.PREVIEW_SAFE);
        }
        SegmentToolArtifact previewArtifact = claim(run, previewAdmission, SegmentToolIds.PREVIEW_SAFE);
        if (!(previewArtifact instanceof SegmentToolArtifact.Preview admittedPreview)) {
            return terminal(run, runId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.PREVIEW_FAILURE, SegmentRefinementAdapterErrorCodes.PREVIEW_FAILED));
        }
        run.previewResponse = admittedPreview.preview();
        run.previewCard = card(run, run.previewResponse);
        PreviewResponse previewResponse = run.previewResponse;
        SegmentPreviewCard previewCard = run.previewCard;
        if (!observe(() -> environment.stageObserver().previewAccepted(previewResponse, previewCard))) {
            return unobservable(run, runId, conversationId);
        }

        // 6. Rebuild the grounded explanation only when its required evidence is honestly available.
        if (SegmentRefinementGrounding.canRebuild(request.priorResolution(), refined.predicate(), request.patch())) {
            SegmentToolAdmission explanationAdmission = run.call(SegmentToolIds.EXPLANATION_GROUNDED);
            if (!explanationAdmission.resolved()) {
                return terminalFromTool(
                        run, runId, conversationId, explanationAdmission, SegmentToolIds.EXPLANATION_GROUNDED);
            }
            SegmentToolArtifact explanationArtifact =
                    claim(run, explanationAdmission, SegmentToolIds.EXPLANATION_GROUNDED);
            if (!(explanationArtifact instanceof SegmentToolArtifact.Explanation admittedExplanation)) {
                return terminal(run, runId, conversationId, Disposition.INVALID,
                        failureCode(SkillCodes.EXPLANATION_FAILURE,
                                SegmentRefinementAdapterErrorCodes.EXPLANATION_FAILED));
            }
            run.explanation = admittedExplanation.explanation();
            GroundedExplanation explanation = run.explanation;
            if (!observe(() -> environment.stageObserver().explanationAccepted(explanation))) {
                return unobservable(run, runId, conversationId);
            }
        }

        // 7. Reach the terminal success state, then satisfy the fail-closed audit policy before returning.
        run.machine.transition(SegmentRefinementState.READY_FOR_PREVIEW, TRANSITION_READY, null);
        run.invoker.markTerminal();
        return result(run, runId, conversationId);
    }

    // ------------------------------------------------------------------ observation boundary

    /**
     * Invokes the injected observation boundary and reports whether it succeeded.
     *
     * <p>The observer returns {@code void} and receives an already-constructed immutable artifact, so it has no
     * channel through which it could change the stage result. A throw is reported to the caller, which fails
     * the run closed rather than continuing with a stage that could not be observed.
     */
    private boolean observe(Runnable observation) {
        SegmentStageObserver observer = environment.stageObserver();
        if (observer == SegmentStageObserver.NOOP) {
            return true;
        }
        try {
            observation.run();
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** The fail-closed terminal used when the injected observation boundary refused an admitted artifact. */
    private SegmentRefinementResult unobservable(Run run, String runId, String conversationId) {
        return terminal(run, runId, conversationId, Disposition.INVALID,
                SegmentRefinementAdapterErrorCodes.OBSERVATION_FAILED);
    }

    // ------------------------------------------------------------------ Tool boundary

    /**
     * Claims the host-private typed artifact of one admitted call, or returns null when the binding refused it
     * so the caller can terminate with one bounded failure instead of propagating an exception.
     */
    private SegmentToolArtifact claim(Run run, SegmentToolAdmission admission, String toolId) {
        try {
            return admission.claim(run.lastCallId, toolId, environment.skill().canonicalHash());
        } catch (SegmentToolException refused) {
            return null;
        }
    }

    private SegmentRefinementResult terminalFromTool(
            Run run, String runId, String conversationId, SegmentToolAdmission admission, String toolId) {
        String category = failureCategoryFor(toolId);
        String code = category == null
                ? (admission.failure() == null
                        ? SegmentRefinementAdapterErrorCodes.TOOL_REJECTED
                        : admission.failure().code())
                : failureCode(category, SegmentRefinementAdapterErrorCodes.TOOL_REJECTED);
        Disposition disposition = admission.disposition() == Disposition.DENIED
                ? Disposition.DENIED
                : Disposition.INVALID;
        return terminal(run, runId, conversationId, disposition, code);
    }

    private static String failureCategoryFor(String toolId) {
        return switch (toolId) {
            case SegmentToolIds.METADATA_DISCOVER -> SkillCodes.METADATA_DISCOVERY_FAILURE;
            case SegmentToolIds.DSL_VALIDATE -> SkillCodes.VALIDATION_FAILURE;
            case SegmentToolIds.PLAN_BUILD -> SkillCodes.PLANNING_FAILURE;
            case SegmentToolIds.PREVIEW_SAFE -> SkillCodes.PREVIEW_FAILURE;
            case SegmentToolIds.EXPLANATION_GROUNDED -> SkillCodes.EXPLANATION_FAILURE;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ results and audit

    private SegmentRefinementResult terminal(
            Run run, String runId, String conversationId, Disposition disposition, String code) {
        if (!run.machine.isTerminal()) {
            run.machine.transition(stateFor(disposition), code, null);
        }
        run.invoker.markTerminal();
        // A refused run still binds the identity of the request it refused — the base document it was applied
        // to and the patch fingerprint — but publishes no document, plan, Preview or explanation artifact.
        return publish(run, runId, conversationId, disposition, code, run.machine.trace(), run.invoker.trace(),
                run.baseDslHash(), run.request.patch().patchFingerprint(),
                null, null, null, null, null, null, true);
    }

    /** Publishes a run that terminated before any pipeline artifact or Tool call existed. */
    private SegmentRefinementResult publishEarly(
            String runId,
            String conversationId,
            ContentHash baseDslHash,
            ContentHash patchFingerprint,
            Disposition disposition,
            String code) {
        return publish(null, runId, conversationId, disposition, code, List.of(), List.of(),
                baseDslHash, patchFingerprint, null, null, null, null, null, null, true);
    }

    private SegmentRefinementResult result(Run run, String runId, String conversationId) {
        return publish(run, runId, conversationId, Disposition.RESOLVED, null, run.machine.trace(),
                run.invoker.trace(), run.baseDslHash(), run.request.patch().patchFingerprint(),
                run.document, run.validation, run.plan, run.previewResponse, run.previewCard, run.explanation,
                true);
    }

    private SegmentRefinementResult publish(
            Run run,
            String runId,
            String conversationId,
            Disposition disposition,
            String code,
            List<SegmentRefinementTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            ContentHash baseDslHash,
            ContentHash patchFingerprint,
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            PreviewResponse preview,
            SegmentPreviewCard card,
            GroundedExplanation explanation,
            boolean audit) {
        if (conversationId == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement run requires a conversation identity");
        }
        SegmentRefinementResult candidate = SegmentRefinementResult.publish(
                environment.skill().skillId(), environment.skill().skillVersion(),
                environment.skill().canonicalHash(), environment.catalog().allowedToolIds(),
                disposition, stateFor(disposition), code, baseDslHash,
                document == null ? null : document.editableDslHash(),
                document,
                validation == null ? null : validation.canonicalAstHash(),
                plan == null ? null : plan.planHash(),
                preview == null ? null : preview.contentHash(),
                run == null ? null : run.snapshotHash(),
                run == null ? null : run.asOf(),
                run == null ? null : run.timezone(),
                run == null ? null : run.dataWatermark(),
                patchFingerprint, stateTrace, toolTrace, card, explanation);
        return audit ? record(run, runId, conversationId, candidate) : candidate;
    }

    /**
     * Records exactly one audit projection for one terminal result, or fails the run closed when the sink
     * refuses. The fail-closed result is returned unrecorded, so a broken sink can never recurse.
     */
    private SegmentRefinementResult record(
            Run run, String runId, String conversationId, SegmentRefinementResult candidate) {
        SegmentRefinementAuditSink sink = environment.auditSink();
        if (sink == null) {
            return candidate;
        }
        try {
            sink.record(audit(run, runId, conversationId, candidate));
            return candidate;
        } catch (RuntimeException failure) {
            return auditFailure(run, runId, conversationId, candidate);
        }
    }

    /**
     * Fails closed when a present audit sink refuses: the run returns {@code INVALID} with no artifact and the
     * state trace ends at the fail-closed terminal transition rather than claiming a success that was never
     * audited. The fail-closed result is deliberately published without a further audit attempt.
     */
    private SegmentRefinementResult auditFailure(
            Run run, String runId, String conversationId, SegmentRefinementResult candidate) {
        String code = failureCode(SkillCodes.AUDIT_FAILURE, SegmentRefinementAdapterErrorCodes.AUDIT_UNAVAILABLE);
        List<SegmentRefinementTransition> trace = new ArrayList<>(candidate.stateTrace());
        SegmentRefinementState from = trace.isEmpty() ? null : trace.get(trace.size() - 1).fromState();
        if (!trace.isEmpty()) {
            trace.remove(trace.size() - 1);
        }
        trace.add(new SegmentRefinementTransition(from, SegmentRefinementState.INVALID, code, null, trace.size()));
        return publish(run, runId, conversationId, Disposition.INVALID, code, trace,
                candidate.toolTrace(), candidate.baseDslHash(), candidate.patchFingerprint(),
                null, null, null, null, null, null, false);
    }

    private SegmentRefinementAuditRecord audit(
            Run run, String runId, String conversationId, SegmentRefinementResult candidate) {
        Long duration = null;
        SegmentRunTimings timings = environment.timings();
        if (timings != null) {
            duration = timings.durationNanos(runId);
        }
        List<String> stateCodes = new ArrayList<>();
        for (SegmentRefinementTransition transition : candidate.stateTrace()) {
            stateCodes.add(transition.evidenceCode());
        }
        List<SegmentAuditToolCode> toolCodes = new ArrayList<>();
        for (SegmentToolCallRecord call : candidate.toolTrace()) {
            toolCodes.add(new SegmentAuditToolCode(call.toolId(), call.code(), call.inputFingerprint()));
        }
        return new SegmentRefinementAuditRecord(
                runId, conversationId, environment.skill().skillId(), environment.skill().skillVersion(),
                environment.skill().canonicalHash(), candidate.disposition(), candidate.terminalState().name(),
                candidate.failureCode(), stateCodes, toolCodes,
                run == null ? null : run.snapshotHash(), candidate.baseDslHash(), candidate.refinedDslHash(),
                candidate.canonicalAstHash(), candidate.planHash(), candidate.previewHash(),
                candidate.patchFingerprint(), candidate.resultHash(), duration);
    }

    // ------------------------------------------------------------------ helpers

    private static SegmentRefinementState stateFor(Disposition disposition) {
        return switch (disposition) {
            case RESOLVED -> SegmentRefinementState.READY_FOR_PREVIEW;
            case DENIED -> SegmentRefinementState.DENIED;
            case INVALID -> SegmentRefinementState.INVALID;
            case NEEDS_INPUT, UNSUPPORTED -> throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.ILLEGAL_TRANSITION,
                    "A refinement can only terminate as READY_FOR_PREVIEW, DENIED or INVALID");
        };
    }

    private String failureCode(String category, String fallback) {
        String declared = environment.skill().failureCode(category);
        return declared == null ? fallback : declared;
    }

    /** True when the document immutably binds exactly the resolved snapshot. */
    private static boolean bindsExactly(SegmentDsl document, MetadataSnapshot snapshot) {
        try {
            document.metadata().verify(snapshot);
            return true;
        } catch (RuntimeException mismatch) {
            return false;
        }
    }

    private static SegmentRefinementRequest validateEnvelope(AgentEnvelope<SegmentRefinementRequest> envelope) {
        if (envelope == null || envelope.payload() == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement run requires an envelope and payload");
        }
        if (!ContractVersions.isCurrent(envelope.schemaVersion())) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement envelope requires the current schema version");
        }
        if (envelope.agentRunId() == null || !envelope.agentRunId().matches(ContractPatterns.REFERENCE_ID)
                || envelope.conversationId() == null
                || !envelope.conversationId().matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.REQUEST_INVALID,
                    "A refinement envelope requires bounded correlation references");
        }
        return envelope.payload();
    }

    private SegmentPreviewCard card(Run run, PreviewResponse response) {
        List<PreviewConditionStatistic> statistics = null;
        if (response.conditionStats() != null) {
            statistics = new ArrayList<>(response.conditionStats());
        }
        return new SegmentPreviewCard(
                response.status(), response.matchedCount(), response.executionContext().metadataSnapshotId(),
                response.executionContext().asOf(), response.executionContext().timezone(),
                response.executionContext().dataWatermark(), statistics, response.sample(),
                response.contentHash(), run.plan.planHash(), run.document.editableDslHash(),
                run.validation.canonicalAstHash(), run.snapshotHash(), environment.skill().canonicalHash());
    }

    /** The Skill failure categories this orchestrator binds. */
    private static final class SkillCodes {

        private static final String METADATA_DISCOVERY_FAILURE = "metadataDiscoveryFailure";
        private static final String VALIDATION_FAILURE = "validationFailure";
        private static final String PLANNING_FAILURE = "planningFailure";
        private static final String PREVIEW_FAILURE = "previewFailure";
        private static final String EXPLANATION_FAILURE = "explanationFailure";
        private static final String AUDIT_FAILURE = "auditFailure";

        private SkillCodes() {
        }
    }

    /** The mutable evidence of one run; every field is host-owned and populated only from admitted Tools. */
    private final class Run implements SegmentToolRunContext {

        private final MetadataSnapshot metadataSnapshot;
        private final TrustedExecutionContext context;
        private final SegmentRefinementRequest request;
        private final SegmentRefinementStateMachine machine;
        private final SegmentToolInvoker invoker;
        private final MetadataQueryService metadataQueryService = environment.metadataQueryService();

        private SegmentDsl document;
        private SegmentValidationResult validation;
        private SemanticQueryPlan plan;
        private PreviewResponse previewResponse;
        private SegmentPreviewCard previewCard;
        private GroundedExplanation explanation;
        private int toolSequence;
        private String lastCallId;

        private Run(
                MetadataSnapshot metadataSnapshot,
                TrustedExecutionContext context,
                SegmentRefinementRequest request) {
            this.metadataSnapshot = metadataSnapshot;
            this.context = context;
            this.request = request;
            this.machine = new SegmentRefinementStateMachine(environment.skill().limits().maxSteps());
            this.invoker = new SegmentToolInvoker(environment.catalog(), StandardSegmentToolHandlers.all(), this);
        }

        /** Submits one host-orchestrated Tool call through the closed invoker. */
        private SegmentToolAdmission call(String toolId) {
            int sequence = ++toolSequence;
            lastCallId = "call-" + sequence;
            return invoker.invoke(
                    new ToolEnvelope(ContractVersions.CURRENT, lastCallId, toolId, false, ToolInput.empty()));
        }

        private ContentHash snapshotHash() {
            return metadataSnapshot == null ? null : metadataSnapshot.contentHash();
        }

        private ContentHash baseDslHash() {
            return request.currentDsl().editableDslHash();
        }

        private String asOf() {
            return context == null ? null : context.snapshotRef().asOf().toString();
        }

        private String timezone() {
            return context == null ? null : context.snapshotRef().timezone().getId();
        }

        private String dataWatermark() {
            return context == null ? null : context.snapshotRef().dataWatermark().toString();
        }

        @Override
        public TrustedExecutionContext trustedContext() {
            return context;
        }

        @Override
        public MetadataSnapshot metadataSnapshot() {
            return metadataSnapshot;
        }

        @Override
        public MetadataQueryService metadataQueryService() {
            return metadataQueryService;
        }

        /**
         * Always null: the refinement flow resolves no Intent, so no Intent is ever visible to a Tool handler.
         * A handler that requires one therefore fails closed rather than reading a substitution.
         */
        @Override
        public com.company.campaign.segment.intent.SegmentIntent intent() {
            return null;
        }

        @Override
        public SegmentDsl dslDocument() {
            return document;
        }

        @Override
        public SegmentValidationResult validation() {
            return validation;
        }

        @Override
        public SemanticQueryPlan plan() {
            return plan;
        }

        @Override
        public PreviewResponse previewResponse() {
            return previewResponse;
        }

        @Override
        public SegmentPreviewCard previewCard() {
            return previewCard;
        }

        @Override
        public ResolutionResult resolution() {
            return request.priorResolution();
        }

        @Override
        public SegmentValidator validator() {
            return validator;
        }

        @Override
        public com.company.campaign.segment.validation.SegmentValidationPolicy validationPolicy() {
            return environment.validationPolicy();
        }

        @Override
        public SegmentQueryPlanner planner() {
            return planner;
        }

        @Override
        public SegmentPreviewPort previewPort() {
            return environment.previewPort();
        }
    }
}