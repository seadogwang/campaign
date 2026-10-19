package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignArtifactAdapter;
import com.company.campaign.builder.assembly.CampaignAssemblyErrorCodes;
import com.company.campaign.builder.assembly.CampaignAssemblyException;
import com.company.campaign.builder.assembly.CampaignJourneyArtifact;
import com.company.campaign.builder.assembly.CampaignSegmentAdapter;
import com.company.campaign.builder.assembly.CampaignSegmentStaging;
import com.company.campaign.builder.assembly.CampaignSkillArtifact;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.agent.SegmentAgentErrorCodes;
import com.company.campaign.builder.assembly.CampaignToolAdmission;
import com.company.campaign.builder.assembly.CampaignToolArtifact;
import com.company.campaign.builder.assembly.CampaignToolCall;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.assembly.CampaignToolIds;
import com.company.campaign.builder.assembly.CampaignToolInvoker;
import com.company.campaign.builder.assembly.CampaignToolRequest;
import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.assembly.StandardCampaignToolHandlers;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.message.MessageInlineTemplateResolver;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.journey.JourneyGraphDraft;
import com.company.campaign.builder.journey.JourneyValidationOutcome;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.Clarification;
import com.company.campaign.segment.resolution.ClarificationOption;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The server-owned closed Skill router of one Campaign conversation run.
 *
 * <p>The router owns exactly six typed runners — one per {@link CampaignSkillIds server-owned Skill} — in a
 * private map that no caller can read, extend or replace. There is no registration method, no reflection, no
 * dynamic lookup and no generic callback, so a Skill identifier that is not server-owned cannot be routed and
 * a runner cannot be swapped for one that would do something else.
 *
 * <p>Each runner drives an <em>accepted</em> boundary rather than reimplementing it:
 * <ul>
 *   <li>{@code segment.rule-based} re-runs the one accepted {@code SegmentAuthoringIntegration} through the
 *       T27 {@link CampaignSegmentAdapter}, so the T11/T16 Segment flow, its Skill resource, its envelope
 *       writer and the compiler stay authoritative;</li>
 *   <li>{@code message.template}, {@code promotion.reference}, {@code schedule.basic} and
 *       {@code governance.limit} execute their ordered Tool sequences through the T27
 *       {@link CampaignToolInvoker} over the exact catalog and the accepted Tool handlers, and stage only the
 *       final accepted result through the T27 {@link CampaignArtifactAdapter};</li>
 *   <li>{@code journey.flow} runs its four topology-aware Tools in rank order — node catalog, compose, validate
 *       and optional pure simulation — and stages only the one atomic bundle the final validation produced.</li>
 * </ul>
 *
 * <p>A runner receives only a {@link CampaignSkillCommand}: the latest utterance and the bounded clarification
 * answers. It never sees the routing output, another Skill's artifact or a mutable workspace.
 */
public final class CampaignSkillRouter {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    // The recovery budget is enforced by CampaignSegmentContinuation.MAX_RECOVERY_ATTEMPTS.

    private final String conversationId;
    private final String runId;
    private final CampaignAgentEnvironment environment;
    private final CampaignSegmentContinuation segmentContinuation;
    private CampaignSegmentFreeTextRecovery segmentFreeTextRecovery;
    private final Map<String, CampaignSkillRunner> runners;

    public CampaignSkillRouter(String conversationId, String runId, CampaignAgentEnvironment environment) {
        this(conversationId, runId, environment, null, null);
    }

    /**
     * Creates the router of one turn, with the session's pending Segment continuation evidence, if any.
     *
     * <p>The continuation is the host/session value the Segment runner replays; it is never routing-model
     * output and never a client field, so no model and no caller can name the resolution it resumes.
     */
    public CampaignSkillRouter(
            String conversationId,
            String runId,
            CampaignAgentEnvironment environment,
            CampaignSegmentContinuation segmentContinuation) {
        this(conversationId, runId, environment, segmentContinuation, null);
    }

    /**
     * Creates the router of one turn, with the session's pending Segment continuation and, when active,
     * the free-text recovery state for initial model-output rejection.
     *
     * <p>Both are host/session values carried from prior turns; they are never routing-model output
     * and never a client field.
     */
    public CampaignSkillRouter(
            String conversationId,
            String runId,
            CampaignAgentEnvironment environment,
            CampaignSegmentContinuation segmentContinuation,
            CampaignSegmentFreeTextRecovery segmentFreeTextRecovery) {
        if (conversationId == null || !IDENTIFIER.matcher(conversationId).matches()
                || runId == null || !IDENTIFIER.matcher(runId).matches()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "identity",
                    "A Skill router requires bounded conversation and run identity.");
        }
        this.conversationId = conversationId;
        this.runId = runId;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.segmentContinuation = segmentContinuation;
        this.segmentFreeTextRecovery = segmentFreeTextRecovery;
        this.runners = buildRunners();
    }

    /**
     * The current free-text Segment recovery state, or null when no such recovery is active.
     *
     * <p>It is set by {@code SegmentRunner} during a turn when the initial model output was rejected
     * and a free-text rephrase was requested. The caller reads it after the run to persist it in the
     * session for the next turn.
     */
    public CampaignSegmentFreeTextRecovery segmentFreeTextRecovery() {
        return segmentFreeTextRecovery;
    }

    /** Runs the one server-owned runner of the command's Skill. */
    public CampaignSkillOutcome run(CampaignSkillCommand command) {
        Objects.requireNonNull(command, "command");
        CampaignSkillRunner runner = runners.get(command.skillId());
        if (runner == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_UNKNOWN_SKILL, "skillId",
                    "No server-owned runner exists for that Skill.");
        }
        return runner.run(command);
    }

    /** Every Skill this router can run, in the server-owned canonical order. */
    public List<String> skills() {
        return CampaignSkillIds.ordered();
    }

    private Map<String, CampaignSkillRunner> buildRunners() {
        Map<String, CampaignSkillRunner> owned = new LinkedHashMap<>();
        owned.put(CampaignSkillIds.SEGMENT_RULE_BASED, new SegmentRunner());
        owned.put(CampaignSkillIds.MESSAGE_TEMPLATE, new MessageRunner());
        owned.put(CampaignSkillIds.PROMOTION_REFERENCE, new PromotionRunner());
        owned.put(CampaignSkillIds.JOURNEY_FLOW, new JourneyRunner());
        owned.put(CampaignSkillIds.SCHEDULE_BASIC, new ScheduleRunner());
        owned.put(CampaignSkillIds.GOVERNANCE_LIMIT, new GovernanceRunner());
        return Map.copyOf(owned);
    }

    // ------------------------------------------------------------------ segment.rule-based

    private final class SegmentRunner implements CampaignSkillRunner {

        @Override
        public CampaignSkillOutcome run(CampaignSkillCommand command) {
            SegmentAuthoringIntegration integration = environment.segmentIntegration();
            if (integration == null) {
                return new CampaignSkillOutcome.Unavailable(
                        CampaignSkillIds.SEGMENT_RULE_BASED, CampaignAgentErrorCodes.SKILL_UNAVAILABLE);
            }
            // A continuation is replayed only when this turn genuinely answers the pending question; otherwise
            // the turn is a fresh utterance and any stored evidence is not applied. The Segment Agent requires
            // the original utterance for a real replay, because the resolution it replays is derived from it.
            // A recovery continuation (recoveryAttempts > 0) is a free-text rephrase — the new utterance
            // is used instead of the stored one so the resolver runs fresh with the user's restatement.
            CampaignSegmentContinuation replay = replayOf(command);
            String replayedUtterance = replay == null || replay.recoveryAttempts() > 0
                    ? command.utterance()
                    : replay.utterance();
            CampaignSegmentSubmission submission =
                    environment.segmentSource(conversationId, runId, replayedUtterance, replay);
            if (submission == null) {
                return new CampaignSkillOutcome.Unavailable(
                        CampaignSkillIds.SEGMENT_RULE_BASED, CampaignAgentErrorCodes.SKILL_UNAVAILABLE);
            }
            if (!(submission instanceof CampaignSegmentSubmission.Source accepted)) {
                String code = submission instanceof CampaignSegmentSubmission.Unavailable unavailable
                        ? unavailable.code()
                        : CampaignAgentErrorCodes.SKILL_UNAVAILABLE;
                return new CampaignSkillOutcome.Unavailable(CampaignSkillIds.SEGMENT_RULE_BASED, code);
            }
            CampaignSegmentSource source = accepted.source();
            CampaignSegmentAdapter adapter = new CampaignSegmentAdapter(
                    integration, conversationId, runId, environment.toolCatalog());
            CampaignSegmentStaging staging = adapter.stage(source.source(), source.request());
            return switch (staging) {
                case CampaignSegmentStaging.Staged staged -> {
                    // A resolved Segment clears any pending free-text recovery.
                    segmentFreeTextRecovery = null;
                    yield new CampaignSkillOutcome.Ready(
                            CampaignSkillIds.SEGMENT_RULE_BASED,
                            CampaignToolIds.SEGMENT_ARTIFACT_REFERENCE,
                            staged.artifact(),
                            audienceOf(source),
                            source.request().capabilityContext());
                }
                case CampaignSegmentStaging.NotAcceptable notAcceptable ->
                        segmentTerminal(notAcceptable, replayedUtterance, replay);
                case CampaignSegmentStaging.Refused refused -> new CampaignSkillOutcome.Terminal(
                        CampaignSkillIds.SEGMENT_RULE_BASED, Disposition.INVALID, refused.code());
            };
        }

        /**
         * The exact answered continuation of this turn, or null for a fresh utterance.
         *
         * <p>A host-owned aggregate clarification may carry the answers of several component questions at
         * once, so this Skill selects the answers that address its own question — an answer with no code, or
         * with exactly this question's code — and ignores answers that are explicitly bound to another
         * question. It then validates that at most one answer actually selects an option of this exact
         * clarification before any Segment model or Preview call: a duplicated, free-text-only or unknown-option
         * answer fails closed, and the receipt can only be built from the resolution identity the session
         * already holds, so a caller can never name a resolution, a target or an option that was not genuinely
         * offered. A turn whose answers address only other component questions leaves the stored evidence
         * unapplied, exactly as a turn that names nothing does.
         *
         * <p>A recovery continuation (one whose {@code recoveryAttempts > 0}) carries no options and allows
         * free text: the user is rephrasing their audience statement, not selecting a definition candidate.
         * In that case the continuation is returned unreplayed so the resolver re-runs fresh with the new
         * utterance, while the recovery counter persists in the continuation for budget enforcement.
         */
        private CampaignSegmentContinuation replayOf(CampaignSkillCommand command) {
            CampaignSegmentContinuation pending = segmentContinuation;
            if (pending == null || command.answers().isEmpty()) {
                return null;
            }
            // A recovery continuation (free-text rephrase) is not replayed through the option-answer path.
            // The next resolution runs fresh with the new utterance; the continuation is returned as-is
            // so the recovery counter persists across turns for budget enforcement.
            if (pending.recoveryAttempts() > 0) {
                return pending;
            }
            List<CampaignClarificationAnswer> forThisQuestion = new ArrayList<>();
            for (CampaignClarificationAnswer answer : command.answers()) {
                if (answer.code() == null || pending.questionCode().equals(answer.code())) {
                    forThisQuestion.add(answer);
                }
            }
            if (forThisQuestion.isEmpty()) {
                // Every supplied answer is explicitly bound to another component's pending question, so this
                // Segment question is not answered by this turn and no stored evidence is applied.
                return null;
            }
            if (forThisQuestion.size() != 1) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A Segment clarification requires exactly one selected answer.");
            }
            CampaignClarificationAnswer answer = forThisQuestion.get(0);
            if (answer.optionId() == null) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "This clarification does not accept free text.");
            }
            return pending.answered(answer.optionId());
        }

        /**
         * The bounded audience card of an accepted resolved Segment source.
         *
         * <p>It is read from the source's own typed proof, which the accepted integration has already
         * re-proved to be the accepted result's card; a terminal source carries the preview hash of nothing, so
         * no card is reported for it.
         */
        private static CampaignAudienceSummary audienceOf(CampaignSegmentSource source) {
            if (source.source() instanceof SegmentAuthoringSource.ResolvedSource resolved) {
                SegmentPreviewCard card = resolved.proof().previewCard();
                return new CampaignAudienceSummary(
                        card.status(), card.matchedCount(), card.asOf(), card.timezone(),
                        card.dataWatermark(), card.previewHash());
            }
            return null;
        }

        /**
         * The bounded Campaign clarification of a genuine Segment {@code NEEDS_INPUT}.
         *
         * <p>The question codes and option identities are the genuine T10 ones. A continuation is exposed only
         * for a definition-selection question with a bounded, safe option set, because only such a question can
         * be answered by replaying the accepted resolver; every other question is projected honestly with no
         * options and no continuation rather than promising a selection the resolver cannot apply.
         *
         * <p>When a replay (retry after a prior clarification answer) encounters a still-invalid model output
         * with the explicitly recoverable {@code SEGMENT_AGENT_RESOLUTION_REJECTED} or
         * {@code SEGMENT_AGENT_MODEL_OUTPUT_REJECTED} code, the recovery is downgraded to NEEDS_INPUT
         * within the bounded recovery budget rather than surfacing as a Terminal that would prevent the
         * component from ever progressing. Every other terminal code — provider failures, system rejections,
         * contract violations — stays a genuine Terminal and is never silently downgraded.
         *
         * <p>The recovery budget is an explicit per-component counter persisted across turns, not inferred
         * from any unrelated list.
         */
        private CampaignSkillOutcome segmentTerminal(
                CampaignSegmentStaging.NotAcceptable terminal,
                String replayedUtterance,
                CampaignSegmentContinuation replay) {
            if (terminal.disposition() != Disposition.NEEDS_INPUT) {
                String boundedCode = boundedCode(terminal.code());
                // SEGMENT_AGENT_RESOLUTION_REJECTED and SEGMENT_AGENT_MODEL_OUTPUT_REJECTED are
                // explicitly recoverable after a prior clarification answer. Provider failures,
                // system rejections and contract violations remain genuine terminals — they are
                // never silently downgraded to NEEDS_INPUT.
                if (replay != null
                        && (SegmentAgentErrorCodes.RESOLUTION_REJECTED.equals(boundedCode)
                            || SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED.equals(boundedCode))
                        && replay.recoveryAttempts() < CampaignSegmentContinuation.MAX_RECOVERY_ATTEMPTS) {
                    // Option-selection recovery is independent; clear any stale free-text recovery.
                    segmentFreeTextRecovery = null;
                    return segmentRetryNeedsInput(terminal, boundedCode,
                            replayedUtterance, replay);
                }
                // Initial MODEL_OUTPUT_REJECTED (no replay continuation exists yet) is a bounded
                // semantic/schema rejection from the first real-model call. RESOLUTION_REJECTED is
                // also recoverable when a prior non-definition NEEDS_INPUT question stored a pending
                // free-text recovery at attempt zero. Every other terminal code remains a genuine terminal.
                if (replay == null
                        && (SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED.equals(boundedCode)
                            || (segmentFreeTextRecovery != null
                                && SegmentAgentErrorCodes.RESOLUTION_REJECTED.equals(boundedCode)))) {
                    CampaignSegmentFreeTextRecovery recovery = segmentFreeTextRecovery != null
                            ? segmentFreeTextRecovery.withIncrementedRecovery()
                            : CampaignSegmentFreeTextRecovery.initial(boundedCode);
                    if (recovery.exhausted()) {
                        // Budget exhausted: return a truthful terminal that carries the diagnostic
                        // history but does not blame the user or invent unsupported claims.
                        segmentFreeTextRecovery = null;
                        return new CampaignSkillOutcome.Terminal(
                                CampaignSkillIds.SEGMENT_RULE_BASED, Disposition.INVALID, boundedCode);
                    }
                    // Store the recovery state on the router so the caller can persist it in the session.
                    segmentFreeTextRecovery = recovery;
                    return new CampaignSkillOutcome.NeedsInput(
                            CampaignSkillIds.SEGMENT_RULE_BASED, boundedCode,
                            List.of(recovery.questionCode()),
                            List.of(), true);
                }
                segmentFreeTextRecovery = null;
                return new CampaignSkillOutcome.Terminal(
                        CampaignSkillIds.SEGMENT_RULE_BASED, terminal.disposition(),
                        boundedCode);
            }
            // The Segment moved past model-output rejection into a genuine clarification.
            segmentFreeTextRecovery = null;
            Clarification clarification = terminal.clarification();
            String questionCode = clarification == null || clarification.questionCode() == null
                    ? "SEGMENT_CLARIFICATION"
                    : clarification.questionCode().name();
            String code = boundedCode(terminal.code());
            if (clarification == null || clarification.questionCode() == null
                    || !clarification.questionCode().isDefinitionSelection()) {
                // When a non-definition question (e.g. SELECT_WINDOW) carries no bounded option set
                // and no ResolutionId, persist a host-owned pending recovery at attempt zero so that
                // RESOLUTION_REJECTED or MODEL_OUTPUT_REJECTED on a later turn can increment and
                // retry within budget rather than becoming an unrecoverable terminal.
                segmentFreeTextRecovery = CampaignSegmentFreeTextRecovery.pending(code, questionCode);
                return new CampaignSkillOutcome.NeedsInput(
                        CampaignSkillIds.SEGMENT_RULE_BASED, code, List.of(questionCode),
                        List.of(), false);
            }
            List<CampaignAnswerOption> options = segmentOptions(clarification);
            if (options == null) {
                // Too many candidates, empty set or a label that exceeded the safe copy bound:
                // surface the genuine NEEDS_INPUT with free text rather than fabricating a terminal
                // that would prevent the component from ever asking its question. No continuation is
                // possible without a bounded option set, so the next turn re-runs the resolver fresh.
                return new CampaignSkillOutcome.NeedsInput(
                        CampaignSkillIds.SEGMENT_RULE_BASED, code, List.of(questionCode),
                        List.of(), false);
            }
            CampaignSegmentContinuation next = CampaignSegmentContinuation.pending(
                    replayedUtterance,
                    replay == null ? List.of() : replay.receipts(),
                    clarification.resolutionId(), clarification.clauseId(), questionCode, options);
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.SEGMENT_RULE_BASED, code, List.of(questionCode), options, false, next);
        }

        /**
         * A recoverable retry NeedsInput when the Segment model produced a recoverable
         * resolution-rejected or model-output-rejected output after a prior clarification was
         * already answered.
         *
         * <p>The user is asked to rephrase. The continuation carries the recovery counter incremented
         * so the budget is persisted across turns. The question code is the continuation's own pending
         * question code, preserving the structured component/code/question-code anchoring; the
         * diagnostic code ({@code SEGMENT_AGENT_RESOLUTION_REJECTED} or
         * {@code SEGMENT_AGENT_MODEL_OUTPUT_REJECTED}) is carried as the outcome code
         * for traceability.
         *
         * <p>The prompt uses the host-owned deterministic rephrase copy rather than model copy,
         * because the model already failed to produce a valid interpretation once — this path must not
         * go through an unanchored terminal status-message model path.
         */
        private CampaignSkillOutcome segmentRetryNeedsInput(
                CampaignSegmentStaging.NotAcceptable terminal,
                String code,
                String replayedUtterance,
                CampaignSegmentContinuation replay) {
            CampaignSegmentContinuation nextReplay = replay.withIncrementedRecovery();
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.SEGMENT_RULE_BASED,
                    code,
                    List.of(replay.questionCode()),
                    List.of(),
                    true,
                    nextReplay);
        }

        /** The bounded safe answer options of one genuine Segment clarification, or null when it exceeds them. */
        private static List<CampaignAnswerOption> segmentOptions(Clarification clarification) {
            List<ClarificationOption> offered = clarification.options();
            if (offered == null || offered.isEmpty() || offered.size() > CampaignAgentLimits.MAX_OPTIONS) {
                return null;
            }
            List<CampaignAnswerOption> options = new ArrayList<>(offered.size());
            for (ClarificationOption option : offered) {
                String label = option.label();
                if (option.optionId() == null || label == null || label.isEmpty()
                        || label.length() > CampaignAgentLimits.MAX_OPTION_LABEL_LENGTH) {
                    return null;
                }
                try {
                    options.add(new CampaignAnswerOption(option.optionId(), label));
                } catch (RuntimeException unsafe) {
                    // A label the bounded Campaign surface refuses is never echoed or truncated; the whole
                    // clarification fails closed as an unavailable selection instead.
                    return null;
                }
            }
            options.sort((left, right) -> left.optionId().compareTo(right.optionId()));
            return List.copyOf(options);
        }
    }

    /**
     * One bounded machine code for a Skill outcome.
     *
     * <p>A genuine Segment failure code is already bounded; anything else is replaced by the Segment-source
     * code rather than being echoed into a conversation surface.
     */
    private static String boundedCode(String code) {
        return code != null && code.matches("^[A-Z][A-Z0-9_]{0,63}$")
                ? code
                : CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED;
    }

    // ------------------------------------------------------------------ message.template

    private final class MessageRunner implements CampaignSkillRunner {

        @Override
        public CampaignSkillOutcome run(CampaignSkillCommand command) {
            CampaignToolRunContext runContext = environment.toolRunContext();
            if (runContext == null) {
                return new CampaignSkillOutcome.Unavailable(
                        CampaignSkillIds.MESSAGE_TEMPLATE, CampaignAgentErrorCodes.SKILL_UNAVAILABLE);
            }
            CampaignMessageInputs inputs =
                    environment.messageInputs(command.utterance(), command.answers());
            if (inputs == null || !inputs.resolved()) {
                return messageClarification(CampaignAgentErrorCodes.MESSAGE_INPUT_REQUIRED);
            }
            CampaignBusinessResourceCatalog resourceCatalog = environment.businessResourceCatalog();
            if (inputs.isInline() && !registeredChannels(
                    inputs, userEvidence(command.utterance()), command.answers(), resourceCatalog)) {
                return channelUnsupportedClarification(resourceCatalog);
            }
            CampaignToolCatalog catalog = environment.toolCatalog();
            CampaignToolInvoker invoker = new CampaignToolInvoker(
                    catalog, CampaignSkillIds.MESSAGE_TEMPLATE, StandardCampaignToolHandlers.all(), runContext);
            CampaignToolAdmission admission;
            try {
                if (inputs.isInline()) {
                    List<CampaignSkillArtifact> artifacts = new ArrayList<>(inputs.inlineDrafts().size());
                    boolean multiple = inputs.inlineDrafts().size() > 1;
                    for (CampaignMessageInputs.InlineDraft draft : inputs.inlineDrafts()) {
                        CampaignToolInvoker inlineInvoker = new CampaignToolInvoker(
                                catalog, CampaignSkillIds.MESSAGE_TEMPLATE,
                                StandardCampaignToolHandlers.all(), runContext);
                        inlineInvoker.invoke(messageCall(
                                catalog, "call-1", new CampaignToolRequest.MessageDiscover(null, 64)));
                        admission = inlineInvoker.invoke(messageCall(
                                catalog, "call-2", new CampaignToolRequest.MessageInlinePreview(
                                        draft.channel(), draft.body())));
                        if (!admission.resolved()) {
                            return refuseFrom(CampaignSkillIds.MESSAGE_TEMPLATE, admission,
                                    "MESSAGE_INLINE_REQUIRED");
                        }
                        // Every inline draft — including exactly one — is staged under its channel-specific
                        // server-owned identity. That is the same component reference a Journey message node
                        // names, so a single inline channel cannot leave a Flow reference unresolved. Only a
                        // catalog template reference keeps the generic template identity.
                        ProposalIdentity target = ProposalIdentity.ofClientRef(
                                CampaignMessageInputs.componentRef(draft.channel()));
                        MessageTemplateVersion templateVersion =
                                MessageInlineTemplateResolver.resolve(
                                        runContext.messageCatalog(), draft.channel().name())
                                .orElse(null);
                        if (templateVersion == null) {
                            return new CampaignSkillOutcome.NeedsInput(
                                    CampaignSkillIds.MESSAGE_TEMPLATE,
                                    CampaignAgentErrorCodes.MESSAGE_INPUT_REQUIRED,
                                    List.of("MESSAGE_TEMPLATE_REQUIRED"),
                                    List.of(),
                                    true);
                        }
                        Optional<CampaignSkillArtifact> artifact =
                                new CampaignArtifactAdapter(conversationId, runId, catalog)
                                        .messageInline(admission, target, templateVersion);
                        if (artifact.isEmpty()) {
                            return new CampaignSkillOutcome.Terminal(
                                    CampaignSkillIds.MESSAGE_TEMPLATE, Disposition.INVALID,
                                    CampaignAgentErrorCodes.SKILL_FAILED);
                        }
                        artifacts.add(artifact.get());
                    }
                    return multiple
                            ? new CampaignSkillOutcome.ReadyMany(
                                    CampaignSkillIds.MESSAGE_TEMPLATE,
                                    CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE, artifacts)
                            : new CampaignSkillOutcome.Ready(
                                    CampaignSkillIds.MESSAGE_TEMPLATE,
                                    CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE, artifacts.get(0));
                }
                String reference = inputs.referenceCode();
                invoker.invoke(messageCall(catalog, "call-1", new CampaignToolRequest.MessageDiscover(null, 64)));
                invoker.invoke(messageCall(catalog, "call-2", new CampaignToolRequest.MessageResolve(reference)));
                invoker.invoke(messageCall(catalog, "call-3",
                        new CampaignToolRequest.MessageVariablesValidate(reference, inputs.variables())));
                admission = invoker.invoke(messageCall(catalog, "call-4",
                        new CampaignToolRequest.MessagePreviewSafe(reference, inputs.variables())));
            } catch (CampaignAssemblyException refused) {
                // A malformed host-supplied reference or variables is a request for input, not a fabricated
                // artifact; the bounded code is preserved and no argument value is echoed.
                return messageClarification(CampaignAgentErrorCodes.MESSAGE_INPUT_REQUIRED);
            }
            if (!admission.resolved()) {
                return refuseFrom(CampaignSkillIds.MESSAGE_TEMPLATE, admission,
                        "MESSAGE_TEMPLATE_UNAVAILABLE");
            }
            Optional<CampaignSkillArtifact> artifact =
                    new CampaignArtifactAdapter(conversationId, runId, catalog)
                            .message(admission, targetFor(CampaignSkillIds.MESSAGE_TEMPLATE));
            return artifact
                    .<CampaignSkillOutcome>map(staged -> new CampaignSkillOutcome.Ready(
                            CampaignSkillIds.MESSAGE_TEMPLATE, CampaignToolIds.MESSAGE_PREVIEW_SAFE, staged))
                    .orElseGet(() -> new CampaignSkillOutcome.Terminal(
                            CampaignSkillIds.MESSAGE_TEMPLATE, Disposition.INVALID,
                            CampaignAgentErrorCodes.SKILL_FAILED));
        }

        private CampaignSkillOutcome messageClarification(String code) {
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.MESSAGE_TEMPLATE, code, List.of("MESSAGE_TEMPLATE_REQUIRED"),
                    List.of(), true);
        }

        private boolean registeredChannels(
                CampaignMessageInputs inputs,
                String evidence,
                List<CampaignClarificationAnswer> answers,
                CampaignBusinessResourceCatalog catalog) {
            List<CampaignBusinessResource> named = catalog.named(
                    CampaignBusinessResourceType.COMMUNICATION_CHANNEL, evidence);
            for (CampaignMessageInputs.InlineDraft draft : inputs.inlineDrafts()) {
                String code = draft.channel().name();
                CampaignBusinessResource resource = catalog.selectable(
                        CampaignBusinessResourceType.COMMUNICATION_CHANNEL, code);
                boolean selected = answers.stream().anyMatch(answer ->
                        (CampaignAgentErrorCodes.CHANNEL_UNSUPPORTED.equals(answer.code())
                                || CampaignConversationAgent.CHANNEL_UNSUPPORTED_QUESTION.equals(answer.code()))
                                && code.equals(answer.optionId()));
                if (resource == null || (!selected && !named.isEmpty() && !named.contains(resource))) {
                    return false;
                }
            }
            return true;
        }

        private CampaignSkillOutcome channelUnsupportedClarification(
                CampaignBusinessResourceCatalog catalog) {
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.MESSAGE_TEMPLATE, CampaignAgentErrorCodes.CHANNEL_UNSUPPORTED,
                    List.of("CHANNEL_UNSUPPORTED"),
                    catalog.options(CampaignBusinessResourceType.COMMUNICATION_CHANNEL), true);
        }
    }

    // ------------------------------------------------------------------ promotion.reference

    private final class PromotionRunner implements CampaignSkillRunner {

        @Override
        public CampaignSkillOutcome run(CampaignSkillCommand command) {
            CampaignToolRunContext runContext = environment.toolRunContext();
            if (runContext == null) {
                return new CampaignSkillOutcome.Unavailable(
                        CampaignSkillIds.PROMOTION_REFERENCE, CampaignAgentErrorCodes.SKILL_UNAVAILABLE);
            }
            CampaignPromotionInputs inputs = selectedPromotion(command.answers(), runContext);
            if (inputs == null) {
                inputs = environment.promotionInputs(command.utterance(), command.answers());
            }
            if (inputs == null || !inputs.resolved()) {
                return promotionClarification(
                        CampaignAgentErrorCodes.PROMOTION_INPUT_REQUIRED, runContext);
            }
            CampaignToolCatalog catalog = environment.toolCatalog();
            CampaignToolInvoker invoker = new CampaignToolInvoker(
                    catalog, CampaignSkillIds.PROMOTION_REFERENCE, StandardCampaignToolHandlers.all(), runContext);
            CampaignToolAdmission admission;
            try {
                String reference = inputs.referenceCode();
                int quantity = inputs.quantity();
                invoker.invoke(rewardCall(catalog, "call-1", new CampaignToolRequest.RewardDiscover(null, 64)));
                invoker.invoke(rewardCall(catalog, "call-2", new CampaignToolRequest.RewardResolve(reference)));
                invoker.invoke(rewardCall(catalog, "call-3",
                        new CampaignToolRequest.RewardExposureCalculate(
                                reference, quantity, inputs.governanceCeiling())));
                admission = invoker.invoke(rewardCall(catalog, "call-4",
                        new CampaignToolRequest.RewardPreviewSafe(
                                reference, quantity, inputs.governanceCeiling())));
            } catch (CampaignAssemblyException refused) {
                return promotionClarification(
                        CampaignAgentErrorCodes.PROMOTION_INPUT_REQUIRED, runContext);
            }
            if (!admission.resolved()) {
                return refuseFrom(CampaignSkillIds.PROMOTION_REFERENCE, admission,
                        "PROMOTION_INPUT_UNAVAILABLE");
            }
            Optional<CampaignSkillArtifact> artifact =
                    new CampaignArtifactAdapter(conversationId, runId, catalog)
                            .promotion(admission, targetFor(CampaignSkillIds.PROMOTION_REFERENCE));
            return artifact
                    .<CampaignSkillOutcome>map(staged -> new CampaignSkillOutcome.Ready(
                            CampaignSkillIds.PROMOTION_REFERENCE, CampaignToolIds.REWARD_PREVIEW_SAFE, staged))
                    .orElseGet(() -> new CampaignSkillOutcome.Terminal(
                            CampaignSkillIds.PROMOTION_REFERENCE, Disposition.INVALID,
                            CampaignAgentErrorCodes.SKILL_FAILED));
        }

        private CampaignPromotionInputs selectedPromotion(
                List<CampaignClarificationAnswer> answers, CampaignToolRunContext runContext) {
            for (CampaignClarificationAnswer answer : answers) {
                if (!"PROMOTION_INPUT_REQUIRED".equals(answer.code()) || answer.optionId() == null) {
                    continue;
                }
                for (RewardVersion reward : runContext.rewardCatalog().versions()) {
                    if (reward.enabled() && reward.runtimeAvailable()
                            && reward.referenceCode().equals(answer.optionId())) {
                        return new CampaignPromotionInputs(
                                reward.referenceCode(), 1, reward.faceValue());
                    }
                }
            }
            return null;
        }

        private CampaignSkillOutcome promotionClarification(
                String code, CampaignToolRunContext runContext) {
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.PROMOTION_REFERENCE, code, List.of("PROMOTION_INPUT_REQUIRED"),
                    promotionOptions(runContext), true);
        }

        private List<CampaignAnswerOption> promotionOptions(CampaignToolRunContext runContext) {
            List<CampaignAnswerOption> options = new ArrayList<>();
            for (RewardVersion reward : runContext.rewardCatalog().versions()) {
                if (!reward.enabled() || !reward.runtimeAvailable()) {
                    continue;
                }
                String label = reward.referenceCode() + " — " + reward.kind() + " "
                        + reward.faceValue().toPlainString() + ' ' + reward.currency();
                options.add(new CampaignAnswerOption(reward.referenceCode(), label));
                if (options.size() == CampaignAgentLimits.MAX_OPTIONS) {
                    break;
                }
            }
            return List.copyOf(options);
        }
    }

    /** Returns only the original user-evidence section of an internal validation document. */
    private static String userEvidence(String utterance) {
        if (utterance == null) {
            return "";
        }
        String marker = "User evidence:\n";
        int markerIndex = utterance.indexOf(marker);
        return markerIndex < 0 ? utterance : utterance.substring(markerIndex + marker.length());
    }

    // ------------------------------------------------------------------ schedule.basic

    private final class ScheduleRunner implements CampaignSkillRunner {

        @Override
        public CampaignSkillOutcome run(CampaignSkillCommand command) {
            CampaignToolRunContext runContext = environment.toolRunContext();
            if (runContext == null) {
                return new CampaignSkillOutcome.Unavailable(
                        CampaignSkillIds.SCHEDULE_BASIC, CampaignAgentErrorCodes.SKILL_UNAVAILABLE);
            }
            CampaignScheduleInputs inputs =
                    environment.scheduleInputs(command.utterance(), command.answers());
            if (inputs == null || !inputs.resolved()) {
                return scheduleClarification(CampaignAgentErrorCodes.SCHEDULE_INPUT_REQUIRED);
            }
            CampaignToolCatalog catalog = environment.toolCatalog();
            CampaignToolInvoker invoker = new CampaignToolInvoker(
                    catalog, CampaignSkillIds.SCHEDULE_BASIC, StandardCampaignToolHandlers.all(), runContext);
            String componentId = targetFor(CampaignSkillIds.SCHEDULE_BASIC).value();
            CampaignToolAdmission admission;
            try {
                invoker.invoke(toolCall(catalog, "call-1", new CampaignToolRequest.ScheduleNormalize(
                        componentId, inputs.declaration())));
                admission = invoker.invoke(toolCall(catalog, "call-2", new CampaignToolRequest.ScheduleValidate(
                        componentId, inputs.declaration())));
            } catch (CampaignAssemblyException refused) {
                // A malformed host-supplied declaration is a request for input, not a fabricated artifact; the
                // bounded code is preserved and no declaration value is echoed.
                return scheduleClarification(CampaignAgentErrorCodes.SCHEDULE_INPUT_REQUIRED);
            }
            if (!admission.resolved()) {
                return refuseFrom(CampaignSkillIds.SCHEDULE_BASIC, admission,
                        "SCHEDULE_INPUT_REQUIRED");
            }
            Optional<CampaignSkillArtifact> artifact =
                    new CampaignArtifactAdapter(conversationId, runId, catalog)
                            .schedule(admission, targetFor(CampaignSkillIds.SCHEDULE_BASIC));
            return artifact
                    .<CampaignSkillOutcome>map(staged -> new CampaignSkillOutcome.Ready(
                            CampaignSkillIds.SCHEDULE_BASIC, CampaignToolIds.SCHEDULE_VALIDATE, staged))
                    .orElseGet(() -> new CampaignSkillOutcome.Terminal(
                            CampaignSkillIds.SCHEDULE_BASIC, Disposition.INVALID,
                            CampaignAgentErrorCodes.SKILL_FAILED));
        }

        private CampaignSkillOutcome scheduleClarification(String code) {
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.SCHEDULE_BASIC, code, List.of("SCHEDULE_INPUT_REQUIRED"),
                    List.of(), true);
        }
    }

    // ------------------------------------------------------------------ governance.limit

    private final class GovernanceRunner implements CampaignSkillRunner {

        @Override
        public CampaignSkillOutcome run(CampaignSkillCommand command) {
            CampaignToolRunContext runContext = environment.toolRunContext();
            if (runContext == null) {
                return new CampaignSkillOutcome.Unavailable(
                        CampaignSkillIds.GOVERNANCE_LIMIT, CampaignAgentErrorCodes.SKILL_UNAVAILABLE);
            }
            CampaignGovernanceInputs inputs =
                    environment.governanceInputs(command.utterance(), command.answers());
            if (inputs == null || !inputs.resolved()) {
                return governanceClarification(CampaignAgentErrorCodes.GOVERNANCE_INPUT_REQUIRED);
            }
            CampaignToolCatalog catalog = environment.toolCatalog();
            CampaignToolInvoker invoker = new CampaignToolInvoker(
                    catalog, CampaignSkillIds.GOVERNANCE_LIMIT, StandardCampaignToolHandlers.all(), runContext);
            String componentId = targetFor(CampaignSkillIds.GOVERNANCE_LIMIT).value();
            CampaignToolAdmission admission;
            try {
                invoker.invoke(toolCall(catalog, "call-1", new CampaignToolRequest.GovernanceExposureCalculate(
                        componentId, inputs.declaration())));
                admission = invoker.invoke(toolCall(catalog, "call-2", new CampaignToolRequest.GovernanceValidate(
                        componentId, inputs.declaration())));
            } catch (CampaignAssemblyException refused) {
                return governanceClarification(CampaignAgentErrorCodes.GOVERNANCE_INPUT_REQUIRED);
            }
            if (!admission.resolved()) {
                return refuseFrom(CampaignSkillIds.GOVERNANCE_LIMIT, admission,
                        "GOVERNANCE_INPUT_REQUIRED");
            }
            Optional<CampaignSkillArtifact> artifact =
                    new CampaignArtifactAdapter(conversationId, runId, catalog)
                            .governance(admission, targetFor(CampaignSkillIds.GOVERNANCE_LIMIT));
            return artifact
                    .<CampaignSkillOutcome>map(staged -> new CampaignSkillOutcome.Ready(
                            CampaignSkillIds.GOVERNANCE_LIMIT, CampaignToolIds.GOVERNANCE_VALIDATE, staged))
                    .orElseGet(() -> new CampaignSkillOutcome.Terminal(
                            CampaignSkillIds.GOVERNANCE_LIMIT, Disposition.INVALID,
                            CampaignAgentErrorCodes.SKILL_FAILED));
        }

        private CampaignSkillOutcome governanceClarification(String code) {
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.GOVERNANCE_LIMIT, code, List.of("GOVERNANCE_INPUT_REQUIRED"),
                    List.of(), true);
        }
    }

    // ------------------------------------------------------------------ journey.flow

    /**
     * The topology-aware Journey runner.
     *
     * <p>It asks a typed clarification whenever the host has not resolved a complete bounded node/topology
     * declaration, then invokes the four Journey Tools in their declared rank order. The node catalog and the
     * composition are non-stageable, the simulation is optional pure preview evidence, and only the final
     * validation bundle may be staged — atomically, through the workspace's own bundle transaction.
     */
    private final class JourneyRunner implements CampaignSkillRunner {

        @Override
        public CampaignSkillOutcome run(CampaignSkillCommand command) {
            CampaignToolRunContext runContext = environment.toolRunContext();
            if (runContext == null || runContext.journeyPackIndex() == null) {
                return new CampaignSkillOutcome.Unavailable(
                        CampaignSkillIds.JOURNEY_FLOW, CampaignAgentErrorCodes.SKILL_UNAVAILABLE);
            }
            CampaignJourneyInputs inputs = command instanceof CampaignSkillCommand.JourneyFlow journey
                    && journey.minimal()
                    ? CampaignJourneyInputs.minimal()
                    : environment.journeyInputs(command.utterance(), command.answers());
            if (inputs != null && command instanceof CampaignSkillCommand.JourneyFlow journey
                    && journey.triggerEventCode() != null) {
                inputs = inputs.withTriggerEvent(journey.triggerEventCode());
            }
            if (inputs == null || !inputs.resolved()) {
                return journeyClarification(CampaignAgentErrorCodes.JOURNEY_INPUT_REQUIRED);
            }
            CampaignToolCatalog catalog = environment.toolCatalog();
            CampaignToolInvoker invoker = new CampaignToolInvoker(
                    catalog, CampaignSkillIds.JOURNEY_FLOW, StandardCampaignToolHandlers.all(), runContext);
            JourneyGraphDraft draft = inputs.draft();
            CampaignToolAdmission admission;
            try {
                invoker.invoke(toolCall(catalog, "call-1",
                        CampaignToolRequest.JourneyNodeCatalog.all()));
                invoker.invoke(toolCall(catalog, "call-2", new CampaignToolRequest.JourneyCompose(draft)));
                admission = invoker.invoke(toolCall(catalog, "call-3",
                        new CampaignToolRequest.JourneyValidate(draft)));
                if (inputs.hasSimulation()) {
                    // Pure preview evidence: the simulation is projected only when the host supplied an explicit
                    // bounded virtual-clock input, and it is never what the bundle is staged from.
                    CampaignToolAdmission preview = invoker.invoke(toolCall(catalog, "call-4",
                            new CampaignToolRequest.JourneySimulate(draft, inputs.simulation())));
                    if (!preview.resolved()) {
                        return refuseFrom(
                                CampaignSkillIds.JOURNEY_FLOW, preview, "JOURNEY_INPUT_REQUIRED");
                    }
                }
            } catch (CampaignAssemblyException refused) {
                return journeyClarification(CampaignAgentErrorCodes.JOURNEY_INPUT_REQUIRED);
            }
            if (!admission.resolved()) {
                return refuseFrom(CampaignSkillIds.JOURNEY_FLOW, admission, "JOURNEY_INPUT_REQUIRED");
            }
            CampaignArtifactAdapter adapter =
                    new CampaignArtifactAdapter(conversationId, runId, catalog);
            Optional<CampaignJourneyArtifact> artifact = adapter.journey(admission);
            if (artifact.isEmpty()) {
                return new CampaignSkillOutcome.Terminal(
                        CampaignSkillIds.JOURNEY_FLOW, Disposition.INVALID,
                        CampaignAgentErrorCodes.SKILL_FAILED);
            }
            CampaignJourneySummary summary = summaryOf(admission, artifact.get(), catalog);
            if (summary == null) {
                return new CampaignSkillOutcome.Terminal(
                        CampaignSkillIds.JOURNEY_FLOW, Disposition.INVALID,
                        CampaignAgentErrorCodes.SKILL_FAILED);
            }
            return new CampaignSkillOutcome.ReadyJourney(
                    CampaignSkillIds.JOURNEY_FLOW, CampaignToolIds.JOURNEY_VALIDATE,
                    artifact.get(), summary);
        }

        private CampaignSkillOutcome journeyClarification(String code) {
            return new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.JOURNEY_FLOW, code, List.of("JOURNEY_INPUT_REQUIRED"),
                    List.of(), true);
        }
    }

    /**
     * The bounded non-secret Journey summary of one admitted validation result.
     *
     * <p>It is derived from the host-private typed artifact of the exact admitted call, so the summary a
     * conversation shows is always the summary of the bundle that will be staged.
     */
    private static CampaignJourneySummary summaryOf(
            CampaignToolAdmission admission, CampaignJourneyArtifact artifact, CampaignToolCatalog catalog) {
        CampaignToolArtifact raw = admission.claim(admission.callId(), admission.toolId(), catalog.catalogHash());
        if (!(raw instanceof CampaignToolArtifact.JourneyValidation validation)) {
            return null;
        }
        JourneyValidationOutcome outcome = validation.outcome();
        return new CampaignJourneySummary(
                outcome.nodeCount(),
                outcome.edgeCount(),
                outcome.entryNodeRef(),
                outcome.terminalNodeRefs().size(),
                outcome.topologyHash(),
                artifact.artifactHash());
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * The Tool refusals whose inputs the host could supply differently on a later turn.
     *
     * <p>An unknown or disabled catalog reference, a variable set the exact version does not accept, an
     * exposure outside its declared bound and a Schedule or Governance declaration the accepted pack refuses
     * are all "ask the user again" outcomes, because the runner lacks a fact it may not invent. Everything else
     * — a denial, an incoherent envelope or a structural refusal — is preserved as a bounded terminal instead.
     * A final artifact is never fabricated from a partial result.
     */
    private static final java.util.Set<String> INPUT_DEPENDENT_REFUSALS = java.util.Set.of(
            CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE,
            CampaignAssemblyErrorCodes.TOOL_VARIABLES_INVALID,
            CampaignAssemblyErrorCodes.TOOL_EXPOSURE_INVALID,
            CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
            CampaignAssemblyErrorCodes.TOOL_DECLARATION_INVALID,
            CampaignAssemblyErrorCodes.TOOL_GRAPH_INVALID);

    /**
     * The bounded outcome of a refused Tool call.
     *
     * <p>A refusal whose inputs the host could supply differently becomes a typed clarification; a genuine
     * denial or contract violation is preserved as a terminal. A final artifact is never fabricated from a
     * partial result.
     */
    private static CampaignSkillOutcome refuseFrom(
            String skillId, CampaignToolAdmission admission, String questionCode) {
        Disposition disposition = admission.disposition();
        String code = admission.failure() == null
                ? CampaignAgentErrorCodes.SKILL_FAILED
                : admission.failure().code();
        if (disposition == Disposition.NEEDS_INPUT
                || disposition == Disposition.UNSUPPORTED
                || INPUT_DEPENDENT_REFUSALS.contains(code)) {
            return new CampaignSkillOutcome.NeedsInput(
                    skillId, code, List.of(questionCode), List.of(), true);
        }
        return new CampaignSkillOutcome.Terminal(skillId, disposition, code);
    }

    private ProposalIdentity targetFor(String skillId) {
        return environment.targetFor(skillId);
    }

    private static CampaignToolCall messageCall(
            CampaignToolCatalog catalog, String callId, CampaignToolRequest request) {
        return toolCall(catalog, callId, request);
    }

    private static CampaignToolCall rewardCall(
            CampaignToolCatalog catalog, String callId, CampaignToolRequest request) {
        return toolCall(catalog, callId, request);
    }

    private static CampaignToolCall toolCall(
            CampaignToolCatalog catalog, String callId, CampaignToolRequest request) {
        var descriptor = catalog.descriptor(request.toolId());
        String toolVersion = descriptor == null ? "1.0" : descriptor.toolVersion();
        return new CampaignToolCall(
                ContractVersions.CURRENT, callId, request.toolId(), toolVersion, request);
    }

    /**
     * Resolves one enabled, runtime-available template version for the given canonical channel and returns
     * a {@code NeedsInput} clarification when none matches.
     *
     * <p>This is the production decision extracted from {@link MessageRunner}: when a channel has no matching
     * enabled, runtime-available template, the runner asks for input rather than fabricating an artifact or
     * throwing. The returned {@link Optional} is empty when a template was resolved — the caller continues
     * with artifact creation — and present with a bounded {@link CampaignSkillOutcome.NeedsInput} when the
     * resolution is empty, carrying the {@code MESSAGE_TEMPLATE} skill id and bounded error/question codes.
     *
     * <p>Package-private so the outcome test in the same package can verify this exact decision without
     * duplicating production logic.
     */
    static Optional<CampaignSkillOutcome> resolveInlineTemplateOrClarify(
            MessageTemplateCatalog catalog, String channel) {
        Optional<MessageTemplateVersion> templateVersion =
                MessageInlineTemplateResolver.resolve(catalog, channel);
        if (templateVersion.isEmpty()) {
            return Optional.of(new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.MESSAGE_TEMPLATE,
                    CampaignAgentErrorCodes.MESSAGE_INPUT_REQUIRED,
                    List.of("MESSAGE_TEMPLATE_REQUIRED"),
                    List.of(),
                    true));
        }
        return Optional.empty();
    }
}
