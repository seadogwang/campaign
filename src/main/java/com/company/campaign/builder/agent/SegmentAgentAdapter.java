package com.company.campaign.builder.agent;

import com.company.campaign.builder.segment.DslCompilationResult;
import com.company.campaign.builder.segment.ResolvedIntentDslCompiler;
import com.company.campaign.builder.segment.SegmentAdapterErrorCodes;
import com.company.campaign.builder.segment.SegmentAdapterException;
import com.company.campaign.builder.segment.SegmentModelOutputGuard;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.builder.segment.SegmentModelRequestFactory;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.builder.tool.SegmentToolAdmission;
import com.company.campaign.builder.tool.SegmentToolArtifact;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.builder.tool.SegmentToolInvoker;
import com.company.campaign.builder.tool.SegmentToolException;
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
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentHasher;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The framework-independent formal T11 Campaign Builder Agent adapter.
 *
 * <p>The admitted Tool handler is the control point of every protected stage. The Agent never runs the T10
 * resolver, the T07 validator, the T08 planner, the T09C Safe Preview or the grounded explanation itself: it
 * submits one host-orchestrated {@link ToolEnvelope} to the closed invoker, the exact Skill catalog decides
 * whether the call is admitted, and the handler that runs is the code that drives the genuine component and
 * returns the accepted typed artifact. A Tool that is unknown, absent from the Skill, stale, over limit, out of
 * order or post-terminal is refused before its implementation is entered, so a disallowed Preview can never
 * execute and then be rejected afterwards.
 *
 * <p>Metadata discovery authorization is admitted before the bounded safe model request is built, and the
 * request projects only the definitions that discovery authorized, so a denied discovery cannot disclose a
 * Metadata projection to the model. An Event Selector is included only when that exact admitted set also
 * contains its complete dependency closure — relation, identity Attribute, ordering Attributes, fixed-filter
 * Attribute keys and scope-filter Attributes; otherwise the whole selector is omitted.
 * DSL generation stays a host deterministic stage because it is not a
 * model-callable Tool, but it consumes only the typed resolution artifact the admitted {@code intent.resolve}
 * Tool returned.
 *
 * <p>The results of the protected stages travel beside the model-visible outcome in the closed, sealed
 * {@link SegmentToolArtifact} union, bound to the exact call identifier, Tool identifier, Skill/catalog hash
 * and public outcome hash through {@link SegmentToolAdmission}. The model-visible envelope stays bounded,
 * redacted and hash-based.
 *
 * <p>The adapter owns a closed state machine with explicit transition evidence, terminates only at
 * {@code NEEDS_INPUT}, {@code UNSUPPORTED}, {@code DENIED}, {@code INVALID} or {@code READY_FOR_PREVIEW}, and
 * can never Save, Apply, Publish, activate Runtime or expose a member sample: this Skill version always calls
 * the Safe Preview with {@code includeSample=false}. It reads no wall clock, random source, default
 * locale/timezone, thread-local or global context; run and conversation identity and every time pin are host
 * inputs.
 */
public final class SegmentAgentAdapter {

    private static final String TRANSITION_DISCOVERING = "AGENT_DISCOVER_METADATA";
    private static final String TRANSITION_RESOLVING = "AGENT_RESOLVE_INTENT";
    private static final String TRANSITION_GENERATING = "AGENT_GENERATE_DSL";
    private static final String TRANSITION_VALIDATING = "AGENT_VALIDATE_DSL";
    private static final String TRANSITION_PLANNING = "AGENT_BUILD_PLAN";
    private static final String TRANSITION_PREVIEWING = "AGENT_SAFE_PREVIEW";
    private static final String TRANSITION_READY = "AGENT_READY_FOR_PREVIEW";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final SegmentAgentEnvironment environment;
    private final SegmentModelRequestFactory requestFactory = new SegmentModelRequestFactory();
    private final SegmentModelOutputGuard outputGuard = new SegmentModelOutputGuard();
    private final SegmentValidator validator;
    private final SegmentQueryPlanner planner;

    public SegmentAgentAdapter(SegmentAgentEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.validator = new SegmentValidator(environment.authorizationService());
        this.planner = new SegmentQueryPlanner(environment.authorizationService());
    }

    /** Runs the complete deterministic Agent flow for one envelope. */
    public SegmentAgentResult invoke(AgentEnvelope<SegmentAgentRequest> envelope) {
        SegmentAgentRequest request = validateEnvelope(envelope);
        String agentRunId = envelope.agentRunId();
        String conversationId = envelope.conversationId();
        ContentHash utteranceFingerprint = CANONICAL_JSON.hash(request.utterance());
        SegmentSkillDefinition skill = environment.skill();

        TrustedExecutionContext context;
        try {
            context = environment.trustedContextProvider().trustedContext();
        } catch (RuntimeException failure) {
            context = null;
        }
        if (context == null) {
            return publishEarly(agentRunId, conversationId, utteranceFingerprint, Disposition.DENIED,
                    SegmentAgentErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE);
        }

        MetadataSnapshot snapshot;
        try {
            snapshot = environment.snapshotSource().resolve(context);
        } catch (RuntimeException failure) {
            snapshot = null;
        }
        if (snapshot == null || !snapshot.snapshotId().equals(context.snapshotRef().metadataSnapshotId())) {
            return publishEarly(agentRunId, conversationId, utteranceFingerprint, Disposition.INVALID,
                    SegmentAgentErrorCodes.METADATA_PIN_MISMATCH);
        }
        if (!snapshot.tenantId().equals(context.tenantId()) || !snapshot.programId().equals(context.programId())) {
            return publishEarly(agentRunId, conversationId, utteranceFingerprint, Disposition.DENIED,
                    SegmentAgentErrorCodes.METADATA_PIN_MISMATCH);
        }
        if (request.utterance().length() > skill.limits().maxUtteranceLength()) {
            return publishEarly(agentRunId, conversationId, utteranceFingerprint, Disposition.INVALID,
                    SegmentAgentErrorCodes.REQUEST_INVALID);
        }

        Run run = new Run(snapshot, context, request, utteranceFingerprint);
        run.machine.start();

        // 1. Discover and authorize the exact permitted Metadata before any projection reaches the model.
        run.machine.transition(SegmentAgentState.DISCOVERING, TRANSITION_DISCOVERING, SegmentToolIds.METADATA_DISCOVER);
        SegmentToolAdmission discovery = run.call(SegmentToolIds.METADATA_DISCOVER);
        if (!discovery.resolved()) {
            return terminalFromTool(run, agentRunId, conversationId, discovery, SegmentToolIds.METADATA_DISCOVER, null);
        }
        SegmentToolArtifact discovered = claim(run, discovery, SegmentToolIds.METADATA_DISCOVER);
        if (discovered == null) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    SegmentAgentErrorCodes.TOOL_REJECTED, null);
        }
        Set<String> authorizedDefinitionIds = new LinkedHashSet<>(
                ((SegmentToolArtifact.MetadataDiscovery) discovered).definitionIds());

        // 2. The bounded safe model request projects only what discovery authorized.
        SegmentModelRequest modelRequest;
        try {
            modelRequest = requestFactory.build(
                    request.utterance(), context.snapshotRef().locale().value(), snapshot,
                    environment.resolutionPolicy(), context.snapshotRef().asOf(), context.snapshotRef().timezone(),
                    environment.registeredDefaults(), authorizedDefinitionIds);
        } catch (SegmentAdapterException failure) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    SegmentAgentErrorCodes.MODEL_REQUEST_INVALID, null);
        }

        byte[] raw;
        try {
            raw = environment.modelPort().invoke(modelRequest);
        } catch (RuntimeException failure) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.PROVIDER_FAILURE, SegmentAgentErrorCodes.PROVIDER_FAILED), null);
        }

        // One first-pass call, then at most one bounded schema-only repair and only when the host opted in.
        SegmentIntent intent;
        ModelAttempt firstPass = attempt(modelRequest, raw, request.utterance(), skill);
        if (firstPass.accepted()) {
            intent = firstPass.intent();
        } else if (!environment.repairPolicy().permitsOneRepair()) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.MODEL_OUTPUT_FAILURE, SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED), null);
        } else {
            byte[] repaired;
            try {
                repaired = environment.modelPort().invoke(modelRequest.forSchemaRepair(List.of(firstPass.issueCode())));
            } catch (RuntimeException failure) {
                return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                        failureCode(SkillCodes.PROVIDER_FAILURE, SegmentAgentErrorCodes.PROVIDER_FAILED), null);
            }
            ModelAttempt repair = attempt(modelRequest, repaired, request.utterance(), skill);
            if (!repair.accepted()) {
                return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                        failureCode(SkillCodes.MODEL_OUTPUT_FAILURE, SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED), null);
            }
            intent = repair.intent();
        }
        SegmentIntent acceptedIntent = intent;
        run.intent = acceptedIntent;
        run.intentHash = SegmentIntentHasher.hash(acceptedIntent);
        if (!observe(() -> environment.stageObserver().intentAccepted(acceptedIntent, run.intentHash))) {
            return unobservable(run, agentRunId, conversationId);
        }

        if (!intent.unsupportedRequirements().isEmpty()) {
            run.machine.transition(
                    SegmentAgentState.UNSUPPORTED, SegmentAgentErrorCodes.UNSUPPORTED_REQUIREMENT, null);
            run.invoker.markTerminal();
            return result(run, agentRunId, conversationId, Disposition.UNSUPPORTED,
                    SegmentAgentErrorCodes.UNSUPPORTED_REQUIREMENT, null, null, null, null);
        }

        // 3. Resolve deterministically inside the admitted intent.resolve Tool.
        run.machine.transition(SegmentAgentState.RESOLVING, TRANSITION_RESOLVING, SegmentToolIds.INTENT_RESOLVE);
        SegmentToolAdmission resolutionAdmission = run.call(SegmentToolIds.INTENT_RESOLVE);
        SegmentToolArtifact resolutionArtifact = resolutionAdmission.hasArtifact()
                ? claim(run, resolutionAdmission, SegmentToolIds.INTENT_RESOLVE)
                : null;
        // A deterministic resolution verdict that is not a resolution — a clarification, an unsupported
        // capability or a denial — is observed exactly like an admitted one, because its question code and its
        // candidates are the evidence an evaluation must compare. The artifact is host-private, so the
        // model-visible envelope and the audit projection are unchanged.
        if (resolutionArtifact instanceof SegmentToolArtifact.Resolution observedResolution) {
            run.resolution = observedResolution.resolution();
            if (!observe(() -> environment.stageObserver().resolutionAccepted(observedResolution.resolution()))) {
                return unobservable(run, agentRunId, conversationId);
            }
        }
        if (!resolutionAdmission.resolved()) {
            Clarification clarification = resolutionArtifact instanceof SegmentToolArtifact.Resolution resolved
                    ? resolved.resolution().clarification()
                    : null;
            return terminalFromTool(
                    run, agentRunId, conversationId, resolutionAdmission, SegmentToolIds.INTENT_RESOLVE,
                    clarification);
        }
        if (!(resolutionArtifact instanceof SegmentToolArtifact.Resolution admittedResolution)) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.RESOLUTION_FAILURE, SegmentAgentErrorCodes.RESOLUTION_REJECTED), null);
        }
        ResolutionResult resolution = admittedResolution.resolution();

        // 4. Compile the host-owned T05 DSL from the admitted resolution artifact only.
        run.machine.transition(SegmentAgentState.GENERATING_DSL, TRANSITION_GENERATING, null);
        DslCompilationResult compilation = new ResolvedIntentDslCompiler(
                environment.resolutionPolicy(), context.snapshotRef().asOf(), context.snapshotRef().timezone())
                .compile(intent, resolution, snapshot, environment.registeredDefaults());
        if (!compilation.isResolved()) {
            return terminal(run, agentRunId, conversationId, compilation.disposition(),
                    failureCode(SkillCodes.COMPILATION_FAILURE, SegmentAgentErrorCodes.DSL_COMPILATION_FAILED), null);
        }
        run.document = compilation.document();
        SegmentDsl compiled = compilation.document();
        if (!observe(() -> environment.stageObserver().dslCompiled(compiled))) {
            return unobservable(run, agentRunId, conversationId);
        }

        // 5. Validate and canonicalize inside the admitted dsl.validate Tool.
        run.machine.transition(SegmentAgentState.VALIDATING, TRANSITION_VALIDATING, SegmentToolIds.DSL_VALIDATE);
        SegmentToolAdmission validationAdmission = run.call(SegmentToolIds.DSL_VALIDATE);
        // The genuine validation result is claimed and observed before the admission is branched on, so the
        // exact deterministic issue-code sequence of an invalid or denied document is comparably observed
        // instead of being discarded with the failure. It travels host-privately: the bounded envelope, the
        // result DTO and the audit projection are unchanged.
        SegmentToolArtifact validationArtifact = validationAdmission.hasArtifact()
                ? claim(run, validationAdmission, SegmentToolIds.DSL_VALIDATE)
                : null;
        if (validationArtifact instanceof SegmentToolArtifact.Validation observedValidation) {
            run.validation = observedValidation.validation();
            SegmentValidationResult validation = observedValidation.validation();
            if (!observe(() -> environment.stageObserver().validationAccepted(validation))) {
                return unobservable(run, agentRunId, conversationId);
            }
        }
        if (!validationAdmission.resolved()) {
            return terminalFromTool(
                    run, agentRunId, conversationId, validationAdmission, SegmentToolIds.DSL_VALIDATE, null);
        }
        if (!(validationArtifact instanceof SegmentToolArtifact.Validation)) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.VALIDATION_FAILURE, SegmentAgentErrorCodes.VALIDATION_REJECTED), null);
        }

        // 6. Plan inside the admitted plan.build Tool.
        run.machine.transition(SegmentAgentState.PLANNING, TRANSITION_PLANNING, SegmentToolIds.PLAN_BUILD);
        SegmentToolAdmission planAdmission = run.call(SegmentToolIds.PLAN_BUILD);
        if (!planAdmission.resolved()) {
            return terminalFromTool(run, agentRunId, conversationId, planAdmission, SegmentToolIds.PLAN_BUILD, null);
        }
        SegmentToolArtifact planArtifact = claim(run, planAdmission, SegmentToolIds.PLAN_BUILD);
        if (!(planArtifact instanceof SegmentToolArtifact.Plan admittedPlan)) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.PLANNING_FAILURE, SegmentAgentErrorCodes.PLANNING_FAILED), null);
        }
        run.plan = admittedPlan.plan();
        SemanticQueryPlan plan = admittedPlan.plan();
        if (!observe(() -> environment.stageObserver().planAccepted(plan))) {
            return unobservable(run, agentRunId, conversationId);
        }

        // 7. Run the genuine T09C Safe Preview inside the admitted preview.safe Tool.
        run.machine.transition(SegmentAgentState.PREVIEWING, TRANSITION_PREVIEWING, SegmentToolIds.PREVIEW_SAFE);
        SegmentToolAdmission previewAdmission = run.call(SegmentToolIds.PREVIEW_SAFE);
        if (!previewAdmission.resolved()) {
            return terminalFromTool(run, agentRunId, conversationId, previewAdmission, SegmentToolIds.PREVIEW_SAFE, null);
        }
        SegmentToolArtifact previewArtifact = claim(run, previewAdmission, SegmentToolIds.PREVIEW_SAFE);
        if (!(previewArtifact instanceof SegmentToolArtifact.Preview admittedPreview)) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.PREVIEW_FAILURE, SegmentAgentErrorCodes.PREVIEW_FAILED), null);
        }
        run.previewResponse = admittedPreview.preview();
        run.previewCard = card(run, run.previewResponse);
        PreviewResponse previewResponse = run.previewResponse;
        SegmentPreviewCard previewCard = run.previewCard;
        if (!observe(() -> environment.stageObserver().previewAccepted(previewResponse, previewCard))) {
            return unobservable(run, agentRunId, conversationId);
        }

        // 8. Build the grounded explanation inside the admitted explanation.grounded Tool.
        SegmentToolAdmission explanationAdmission = run.call(SegmentToolIds.EXPLANATION_GROUNDED);
        if (!explanationAdmission.resolved()) {
            return terminalFromTool(
                    run, agentRunId, conversationId, explanationAdmission, SegmentToolIds.EXPLANATION_GROUNDED, null);
        }
        SegmentToolArtifact explanationArtifact = claim(run, explanationAdmission, SegmentToolIds.EXPLANATION_GROUNDED);
        if (!(explanationArtifact instanceof SegmentToolArtifact.Explanation admittedExplanation)) {
            return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                    failureCode(SkillCodes.EXPLANATION_FAILURE, SegmentAgentErrorCodes.EXPLANATION_FAILED), null);
        }
        run.explanation = admittedExplanation.explanation();
        GroundedExplanation explanation = run.explanation;
        if (!observe(() -> environment.stageObserver().explanationAccepted(explanation))) {
            return unobservable(run, agentRunId, conversationId);
        }

        // 9. Reach the terminal success state, then satisfy the fail-closed audit policy before returning.
        run.machine.transition(SegmentAgentState.READY_FOR_PREVIEW, TRANSITION_READY, null);
        run.invoker.markTerminal();
        return result(run, agentRunId, conversationId, Disposition.RESOLVED, null,
                run.document, run.validation, run.plan, run.previewResponse);
    }

    // ------------------------------------------------------------------ provider and observation boundary

    /**
     * Runs one provider call plus its strict-parse attempt, mapping every bounded rejection to a machine code.
     *
     * <p>The size check is the contract's own bounded limit rather than a second, weaker definition of the
     * response contract, so an oversized response is the same class of schema rejection as a malformed one.
     */
    private ModelAttempt attempt(
            SegmentModelRequest request, byte[] raw, String utterance, SegmentSkillDefinition skill) {
        if (raw != null && raw.length > skill.limits().maxModelOutputBytes()) {
            return ModelAttempt.rejected(SegmentAdapterErrorCodes.OUTPUT_OVERSIZED);
        }
        try {
            return ModelAttempt.accepted(outputGuard.parse(raw, utterance, environment.registeredDefaults()));
        } catch (SegmentAdapterException failure) {
            return ModelAttempt.rejected(failure.code());
        }
    }

    /**
     * Invokes the injected observation boundary and reports whether it succeeded.
     *
     * <p>The observer returns {@code void} and receives an already-constructed artifact, so it has no channel
     * through which it could change the stage result. A throw is reported to the caller, which fails the run
     * closed rather than continuing with a stage that could not be observed.
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
    private SegmentAgentResult unobservable(Run run, String agentRunId, String conversationId) {
        return terminal(run, agentRunId, conversationId, Disposition.INVALID,
                SegmentAgentErrorCodes.OBSERVATION_FAILED, null);
    }

    /** The bounded outcome of one provider call and its strict-parse attempt. */
    private record ModelAttempt(SegmentIntent intent, String issueCode) {

        static ModelAttempt accepted(SegmentIntent intent) {
            return new ModelAttempt(intent, null);
        }

        static ModelAttempt rejected(String issueCode) {
            return new ModelAttempt(null, issueCode);
        }

        boolean accepted() {
            return intent != null;
        }
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

    private SegmentAgentResult terminalFromTool(
            Run run,
            String agentRunId,
            String conversationId,
            SegmentToolAdmission admission,
            String toolId,
            Clarification clarification) {
        Disposition disposition = admission.disposition();
        String category = failureCategoryFor(toolId);
        String code = category == null
                ? (admission.failure() == null
                        ? SegmentAgentErrorCodes.TOOL_REJECTED
                        : admission.failure().code())
                : failureCode(category, SegmentAgentErrorCodes.TOOL_REJECTED);
        if (Disposition.NEEDS_INPUT == disposition && clarification == null) {
            // A NEEDS_INPUT terminal is only honest with the genuine T10 clarification; without it the run is
            // incoherent and must not claim that the user has an answerable question.
            disposition = Disposition.INVALID;
            code = failureCode(SkillCodes.RESOLUTION_FAILURE, SegmentAgentErrorCodes.RESOLUTION_REJECTED);
        }
        return terminal(run, agentRunId, conversationId, disposition, code,
                Disposition.NEEDS_INPUT == disposition ? clarification : null);
    }

    private static String failureCategoryFor(String toolId) {
        return switch (toolId) {
            case SegmentToolIds.METADATA_DISCOVER -> SkillCodes.METADATA_DISCOVERY_FAILURE;
            case SegmentToolIds.INTENT_RESOLVE -> SkillCodes.RESOLUTION_FAILURE;
            case SegmentToolIds.DSL_VALIDATE -> SkillCodes.VALIDATION_FAILURE;
            case SegmentToolIds.PLAN_BUILD -> SkillCodes.PLANNING_FAILURE;
            case SegmentToolIds.PREVIEW_SAFE -> SkillCodes.PREVIEW_FAILURE;
            case SegmentToolIds.EXPLANATION_GROUNDED -> SkillCodes.EXPLANATION_FAILURE;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ results and audit

    private SegmentAgentResult terminal(
            Run run,
            String agentRunId,
            String conversationId,
            Disposition disposition,
            String code,
            Clarification clarification) {
        if (run.machine.isTerminal()) {
            // Already terminal: return the bounded result without a further transition.
            return publish(run, agentRunId, conversationId, disposition, code,
                    Disposition.NEEDS_INPUT == disposition ? clarification : null,
                    run.machine.trace(), run.invoker.trace(), run.snapshotHash(), run.intentHash,
                    null, null, null, null, null, null);
        }
        run.machine.transition(stateFor(disposition), code, null);
        run.invoker.markTerminal();
        return publish(run, agentRunId, conversationId, disposition, code,
                Disposition.NEEDS_INPUT == disposition ? clarification : null, run.machine.trace(),
                run.invoker.trace(), run.snapshotHash(), run.intentHash, null, null, null, null, null, null);
    }

    /** Publishes a run that terminated before any pipeline artifact or Tool call existed. */
    private SegmentAgentResult publishEarly(
            String agentRunId, String conversationId, ContentHash utteranceFingerprint,
            Disposition disposition, String code) {
        return publish(null, agentRunId, conversationId, disposition, code, null, List.of(), List.of(), null, null,
                null, null, null, null, null, null, utteranceFingerprint);
    }

    private SegmentAgentResult result(
            Run run,
            String agentRunId,
            String conversationId,
            Disposition disposition,
            String code,
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            PreviewResponse preview) {
        return publish(run, agentRunId, conversationId, disposition, code, null, run.machine.trace(),
                run.invoker.trace(), run.snapshotHash(), run.intentHash, document, validation, plan, preview,
                run.previewCard, run.explanation);
    }

    private SegmentAgentResult publish(
            Run run,
            String agentRunId,
            String conversationId,
            Disposition disposition,
            String code,
            Clarification clarification,
            List<AgentTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            ContentHash metadataSnapshotHash,
            ContentHash intentHash,
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            PreviewResponse preview,
            SegmentPreviewCard card,
            GroundedExplanation explanation) {
        return publish(run, agentRunId, conversationId, disposition, code, clarification, stateTrace, toolTrace,
                metadataSnapshotHash, intentHash, document, validation, plan, preview, card, explanation,
                run == null ? null : run.utteranceFingerprint);
    }

    private SegmentAgentResult publish(
            Run run,
            String agentRunId,
            String conversationId,
            Disposition disposition,
            String code,
            Clarification clarification,
            List<AgentTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            ContentHash metadataSnapshotHash,
            ContentHash intentHash,
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            PreviewResponse preview,
            SegmentPreviewCard card,
            GroundedExplanation explanation,
            ContentHash utteranceFingerprint) {
        return publish(run, agentRunId, conversationId, disposition, code, clarification, stateTrace, toolTrace,
                metadataSnapshotHash, intentHash, document, validation, plan, preview, card, explanation,
                utteranceFingerprint, true);
    }

    private SegmentAgentResult publish(
            Run run,
            String agentRunId,
            String conversationId,
            Disposition disposition,
            String code,
            Clarification clarification,
            List<AgentTransition> stateTrace,
            List<SegmentToolCallRecord> toolTrace,
            ContentHash metadataSnapshotHash,
            ContentHash intentHash,
            SegmentDsl document,
            SegmentValidationResult validation,
            SemanticQueryPlan plan,
            PreviewResponse preview,
            SegmentPreviewCard card,
            GroundedExplanation explanation,
            ContentHash utteranceFingerprint,
            boolean audit) {
        // The conversation identity is retained only inside the audit projection; the result is not re-identified.
        if (conversationId == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A run requires a conversation identity");
        }
        SegmentAgentResult candidate = SegmentAgentResult.publish(
                environment.skill().skillId(), environment.skill().skillVersion(),
                environment.skill().canonicalHash(), disposition, stateFor(disposition), code, clarification,
                metadataSnapshotHash, intentHash,
                document == null ? null : document.editableDslHash(),
                validation == null ? null : validation.canonicalAstHash(),
                plan == null ? null : plan.planHash(),
                preview == null ? null : preview.contentHash(),
                stateTrace, toolTrace, card, explanation);
        return audit ? record(run, agentRunId, conversationId, utteranceFingerprint, candidate) : candidate;
    }

    /**
     * Records exactly one audit projection for one terminal result, or fails the run closed when the sink
     * refuses. The fail-closed result is returned unrecorded, so a broken sink can never recurse.
     */
    private SegmentAgentResult record(
            Run run,
            String agentRunId,
            String conversationId,
            ContentHash utteranceFingerprint,
            SegmentAgentResult candidate) {
        SegmentAuditSink sink = environment.auditSink();
        if (sink == null) {
            return candidate;
        }
        try {
            sink.record(audit(run, agentRunId, conversationId, utteranceFingerprint, candidate));
            return candidate;
        } catch (RuntimeException failure) {
            return auditFailure(run, agentRunId, conversationId, utteranceFingerprint, candidate);
        }
    }

    /**
     * Fails closed when a present audit sink refuses: the run returns {@code INVALID} with no artifact and the
     * state trace ends at the last in-flight state rather than claiming a success that was never audited. The
     * fail-closed result is deliberately published without a further audit attempt, so a broken sink can never
     * trigger a recursive record.
     */
    private SegmentAgentResult auditFailure(
            Run run,
            String agentRunId,
            String conversationId,
            ContentHash utteranceFingerprint,
            SegmentAgentResult candidate) {
        String code = failureCode(SkillCodes.AUDIT_FAILURE, SegmentAgentErrorCodes.AUDIT_UNAVAILABLE);
        List<AgentTransition> trace = new ArrayList<>(candidate.stateTrace());
        SegmentAgentState from = trace.isEmpty() ? null : trace.get(trace.size() - 1).fromState();
        if (!trace.isEmpty()) {
            // Drop the un-audited terminal transition, then record the fail-closed terminal transition instead.
            trace.remove(trace.size() - 1);
        }
        trace.add(new AgentTransition(from, SegmentAgentState.INVALID, code, null, trace.size()));
        return publish(run, agentRunId, conversationId, Disposition.INVALID, code, null, trace,
                candidate.toolTrace(), candidate.metadataSnapshotHash(), candidate.intentHash(),
                null, null, null, null, null, null, utteranceFingerprint, false);
    }

    private SegmentAuditRecord audit(
            Run run, String agentRunId, String conversationId, ContentHash utteranceFingerprint,
            SegmentAgentResult candidate) {
        Long duration = null;
        if (environment.timings() != null) {
            duration = environment.timings().durationNanos(agentRunId);
        }
        List<String> stateCodes = new ArrayList<>();
        for (AgentTransition transition : candidate.stateTrace()) {
            stateCodes.add(transition.evidenceCode());
        }
        List<SegmentAuditToolCode> toolCodes = new ArrayList<>();
        for (SegmentToolCallRecord call : candidate.toolTrace()) {
            toolCodes.add(new SegmentAuditToolCode(call.toolId(), call.code(), call.inputFingerprint()));
        }
        return new SegmentAuditRecord(
                agentRunId, conversationId, environment.skill().skillId(), environment.skill().skillVersion(),
                environment.skill().canonicalHash(), candidate.disposition(), candidate.terminalState().name(),
                candidate.failureCode(), stateCodes, toolCodes,
                run == null ? null : run.snapshotHash(), candidate.intentHash(),
                candidate.dslEditableHash(), candidate.canonicalAstHash(), candidate.planHash(),
                candidate.previewHash(), utteranceFingerprint, candidate.resultHash(), duration);
    }

    // ------------------------------------------------------------------ helpers

    private static SegmentAgentState stateFor(Disposition disposition) {
        return switch (disposition) {
            case RESOLVED -> SegmentAgentState.READY_FOR_PREVIEW;
            case NEEDS_INPUT -> SegmentAgentState.NEEDS_INPUT;
            case UNSUPPORTED -> SegmentAgentState.UNSUPPORTED;
            case DENIED -> SegmentAgentState.DENIED;
            case INVALID -> SegmentAgentState.INVALID;
        };
    }

    private String failureCode(String category, String fallback) {
        String declared = environment.skill().failureCode(category);
        return declared == null ? fallback : declared;
    }

    private static SegmentAgentRequest validateEnvelope(AgentEnvelope<SegmentAgentRequest> envelope) {
        if (envelope == null || envelope.payload() == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An Agent run requires an envelope and payload");
        }
        if (!ContractVersions.isCurrent(envelope.schemaVersion())) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An Agent envelope requires the current schema version");
        }
        if (envelope.agentRunId() == null || !envelope.agentRunId().matches(ContractPatterns.REFERENCE_ID)
                || envelope.conversationId() == null
                || !envelope.conversationId().matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "An Agent envelope requires bounded correlation references");
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

    /** The Skill failure categories this adapter binds. */
    private static final class SkillCodes {

        private static final String METADATA_DISCOVERY_FAILURE = "metadataDiscoveryFailure";
        private static final String PROVIDER_FAILURE = "providerFailure";
        private static final String MODEL_OUTPUT_FAILURE = "modelOutputFailure";
        private static final String RESOLUTION_FAILURE = "resolutionFailure";
        private static final String COMPILATION_FAILURE = "compilationFailure";
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
        private final SegmentAgentStateMachine machine;
        private final SegmentToolInvoker invoker;
        private final MetadataQueryService metadataQueryService = environment.metadataQueryService();
        private final IntentResolver intentResolver;
        private final List<ClarificationSelectionReceipt> continuationReceipts;
        private final ContentHash utteranceFingerprint;

        private SegmentIntent intent;
        private ContentHash intentHash;
        private ResolutionResult resolution;
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
                SegmentAgentRequest request,
                ContentHash utteranceFingerprint) {
            this.metadataSnapshot = metadataSnapshot;
            this.context = context;
            this.continuationReceipts = request.continuation();
            this.utteranceFingerprint = utteranceFingerprint;
            this.machine = new SegmentAgentStateMachine(environment.skill().limits().maxSteps());
            this.invoker = new SegmentToolInvoker(environment.catalog(), StandardSegmentToolHandlers.all(), this);
            this.intentResolver = new IntentResolver(
                    metadataSnapshot, environment.resolutionPolicy(), environment.registeredDefaults(),
                    environment.referenceDirectory());
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
         * The host-owned Existing Audience Reference directory of this run, or null when none was injected.
         *
         * <p>It is the same host fact the run's resolver was built with, so a read-only reference Tool handler
         * and the Intent resolution stage always answer from one identical directory revision.
         */
        @Override
        public com.company.campaign.segment.reference.AudienceReferenceDirectoryContext referenceDirectory() {
            return environment.referenceDirectory();
        }

        @Override
        public SegmentIntent intent() {
            return intent;
        }

        @Override
        public IntentResolver intentResolver() {
            return intentResolver;
        }

        @Override
        public ResolutionPolicy resolutionPolicy() {
            return environment.resolutionPolicy();
        }

        @Override
        public RegisteredDefaultSource registeredDefaults() {
            return environment.registeredDefaults();
        }

        @Override
        public List<ClarificationSelectionReceipt> continuationReceipts() {
            return continuationReceipts;
        }

        @Override
        public SegmentValidator validator() {
            return validator;
        }

        @Override
        public SegmentValidationPolicy validationPolicy() {
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

        @Override
        public ResolutionResult resolution() {
            return resolution;
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
    }
}