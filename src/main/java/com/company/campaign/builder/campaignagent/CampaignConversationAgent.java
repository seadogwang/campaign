package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.agent.SegmentAgentErrorCodes;
import com.company.campaign.builder.assembly.CampaignAssemblyWorkspace;
import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignComponentView;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.contracts.Disposition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The top-level Campaign Agent: one bounded turn of one Campaign conversation.
 *
 * <p>The turn pipeline is fixed and host-owned:
 * <pre>
 * user turn
 *   -&gt; bounded Campaign routing model request            ({@link CampaignRoutingRequest})
 *   -&gt; strict typed routing decision guard               ({@link CampaignRoutingDecisionDecoder})
 *   -&gt; host-owned closed Skill router                    ({@link CampaignSkillRouter})
 *   -&gt; selected Skill runs its admitted Tools            (accepted T27 boundaries)
 *   -&gt; host accepts typed Skill artifacts into T27       ({@link CampaignAssemblyWorkspace})
 *   -&gt; bounded conversation projection                   ({@link CampaignConversationProjection})
 *   -&gt; explicit FINALIZE_CAMPAIGN turn
 *   -&gt; exactly one CampaignProposalPatch from the workspace
 * </pre>
 *
 * <p>The routing model may select a Skill and request a clarification. Its output is never authoritative: it
 * is decoded strictly, then re-judged by the host against the session, and it cannot express a Proposal
 * operation, a capability payload, a Tool result, a Tool identifier, SQL, a URL, a Provider call, a lifecycle
 * action or saved state. Apply, Publish and Runtime are outside this class entirely, and the only handoff it
 * produces is the optional host-emitted {@link CampaignProposalPatch}, which a later explicit Plan action may
 * consume.
 *
 * <p>The class reads no random source, no locale default, no thread-local and no global state. It is stateless;
 * all session state lives in the {@link CampaignAgentSession} the caller owns. The one trusted turn time it grounds
 * accepted schedule evidence against is reached only through {@link CampaignAgentEnvironment#trustedTime()}.
 */
public final class CampaignConversationAgent {

    static final String REQUIREMENT_CLARIFICATION = "CAMPAIGN_REQUIREMENT_CLARIFICATION";
    static final String CHANNEL_UNSUPPORTED_QUESTION = "CHANNEL_UNSUPPORTED";

    /** The one server-owned goal clarification question code, stable and closed. */
    static final String GOAL_CLARIFICATION_QUESTION = "CAMPAIGN_GOAL_CLARIFICATION";

    /** The generic English host copy that follows an audience result. */
    static final String AUDIENCE_FOLLOW_UP_EN =
            "Audience ready. Add message, promotion, journey, schedule or governance requirements, "
                    + "or ask to finish.";

    /** The generic English host copy repeated after an accepted requirement turn. */
    static final String BUILD_COPY_EN =
            "I accepted the requirements you described. Add another requirement or ask to finish.";

    /** The generic English host copy of a goal clarification.
     *
     * <p>This is the ultimate generic fallback used only when neither the model wording boundary nor
     * a component-aware base is available. Component clarifications carry their specific missing-fact
     * semantics through the structured question codes passed to the wording boundary. */
    static final String CLARIFY_COPY_EN = "Supply the missing detail so this can be completed.";

    /** The generic English host copy of an audience/Segment clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. It names no scenario fact,
     * question or catalog value and is used only when the wording boundary is unavailable. */
    static final String AUDIENCE_CLARIFY_COPY =
            "This audience condition is missing a required detail. Supply the missing fact so a rule can be built.";

    /** The generic English host copy of a message/communication clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String MESSAGE_CLARIFY_COPY =
            "The message or delivery channel is missing a required input. Supply it to continue.";

    /** The generic English host copy of a promotion/offer clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String PROMOTION_CLARIFY_COPY =
            "The promotion or offer is missing a required input. Supply it to continue.";

    /** The generic English host copy of a schedule clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String SCHEDULE_CLARIFY_COPY =
            "The schedule or timing is missing a required input. Supply it to continue.";

    /** The generic English host copy of a journey/branch clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String JOURNEY_CLARIFY_COPY =
            "The journey or branching is missing a required input. Supply it to continue.";

    // ------------------------------------------------------------------ component decision and resource clarification codes

    /** The closed question code prefix for a component usage-decision clarification (yes/no for one capability).
     *
     * <p>The full question code includes the capability for disambiguation, e.g.
     * {@code COMPONENT_DECISION_EVENT} or {@code COMPONENT_DECISION_MESSAGE}. Two
     * capabilities that map to the same component (EVENT and FLOW both → JOURNEY) each
     * produce a distinct question code, so the answer turn can correctly identify which
     * capability the user is answering.
     */
    static final String COMPONENT_DECISION_QUESTION_PREFIX = "COMPONENT_DECISION_";

    /**
     * The capability-specific question code for a COMPONENT_DECISION clarification.
     *
     * <p>Example: {@code COMPONENT_DECISION_EVENT}, {@code COMPONENT_DECISION_MESSAGE}.
     * The generic prefix alone ({@code COMPONENT_DECISION}) is never used as a question
     * code — every COMPONENT_DECISION carries exactly one capability.
     */
    static String componentDecisionQuestionCode(CampaignCapability capability) {
        return COMPONENT_DECISION_QUESTION_PREFIX + capability.code();
    }

    /** The closed question code for a resource selection clarification (one resource of one type). */
    static final String RESOURCE_SELECTION_QUESTION = "RESOURCE_SELECTION";

    /** The closed question code for a resource-blocked clarification (no selectable resource exists). */
    static final String RESOURCE_BLOCKED_QUESTION = "RESOURCE_BLOCKED";

    /** The generic English host copy of a component-decision clarification.
     *
     * <p>It names no capability, resource, scenario or catalog value; the specific missing-decision
     * semantics are carried by the structured question codes passed to the wording boundary. */
    static final String COMPONENT_DECISION_COPY_EN =
            "Do you need this component for your Campaign? Choose Yes or No.";

    /** The generic Chinese host copy of a component-decision clarification. */
    static final String COMPONENT_DECISION_COPY_ZH =
            "您的营销活动是否需要该组件？请选择是或否。";

    /** The generic English host copy of a resource-selection clarification. */
    static final String RESOURCE_SELECTION_COPY_EN =
            "Select the resource to use for this component.";

    /** The generic Chinese host copy of a resource-selection clarification. */
    static final String RESOURCE_SELECTION_COPY_ZH =
            "请选择该组件要使用的资源。";

    /** The generic English host copy of a resource-blocked clarification.
     *
     * <p>It reports a genuine system configuration gap: no selectable resource exists in the
     * catalog for this capability type. The host copy is used directly; the model wording
     * boundary is not consulted because it can fabricate missing resources from training priors. */
    static final String RESOURCE_BLOCKED_COPY_EN =
            "No available resource of this type is configured. An administrator must register "
                    + "at least one selectable resource before this component can proceed.";

    /** The generic Chinese host copy of a resource-blocked clarification. */
    static final String RESOURCE_BLOCKED_COPY_ZH =
            "未配置此类可用资源。管理员必须先注册至少一个可选资源，该组件才能继续。";

    /**
     * The generic English fallback copy of a Governance clarification.
     *
     * <p>A declared {@code governance.limit} is a frozen positive-limit contract: the accepted pack refuses a
     * non-positive maximum recipient or reward-grant limit, so an answer such as "no limit" or "the default" can
     * never be turned into a Governance artifact. The sentence therefore states the contract rather than
     * repeating a neutral question that would imply the answer had been accepted. How that is worded in the
     * user's own language belongs to the model-owned wording boundary; this generic sentence carries no scenario
     * fact, question or catalog value, and is used only when that boundary is unavailable.
     */
    static final String GOVERNANCE_LIMIT_COPY_EN =
            "A concrete limit is required: a positive maximum recipient count, a positive maximum reward-grant "
                    + "count, a reward face value exact to two minor units and a currency code. There is no "
                    + "unlimited and no defaulted variant.";

    /**
     * The generic English fallback copy of an unsupported-channel clarification: the catalog, not the copy, decides
     * which channels exist, and the sentence names none of them.
     */
    static final String CHANNEL_UNSUPPORTED_COPY_EN =
            "The current resource catalog does not support that delivery channel. Choose a channel it does "
                    + "support, or leave the communication component out.";

    /**
     * The generic English fallback copy when the communication-channel configuration has zero selectable
     * resources: the channel the user stated is preserved, but validation is blocked until an administrator
     * configures one or more selectable channels, and the user is never asked to restate a channel they
     * already specified. This copy is host-owned and deterministic; the model wording boundary is deliberately
     * not consulted for this outcome because it can fabricate missing content from training priors.
     */
    static final String COMMUNICATION_CONFIG_UNAVAILABLE_COPY_EN =
            "Communication channel configuration is unavailable — no selectable channels are configured. "
                    + "Your stated channel has been preserved, but the communication component cannot proceed "
                    + "until an administrator configures one or more selectable channels.";

    /**
     * The generic English fallback copy of an audience rephrase clarification: the strict rule grammar, not the copy,
     * decides what can be evaluated, and the sentence states no condition of its own.
     */
    static final String AUDIENCE_REPHRASE_COPY_EN =
            "I cannot yet turn that audience condition into a strict rule. Restate its time range in explicit "
                    + "days or weeks and keep the other conditions.";

    /** The generic English host copy of an out-of-scope turn: capability-bound and truthful. */
    static final String OUT_OF_SCOPE_COPY_EN =
            "This builder creates and edits Campaign configurations. "
                    + "It cannot execute the requested operation, which is outside its scope "
                    + "as an authoring tool.";

    /** The generic English host copy of an accepted finalize. */
    static final String FINALIZED_COPY_EN =
            "The Proposal is ready. Create the Plan when you are ready.";

    /** The generic English host copy when accepted requirements are not yet finalizable. */
    static final String BLOCKED_COPY_EN =
            "Some requirements are unavailable or invalid. Revise them before finishing.";

    // ------------------------------------------------------------------ Chinese fallback copies

    /** The generic Chinese host copy that follows an audience result. */
    static final String AUDIENCE_FOLLOW_UP_ZH =
            "受众已就绪。请补充消息、优惠、旅程、排期或治理要求，或请求完成。";

    /** The generic Chinese host copy repeated after an accepted requirement turn. */
    static final String BUILD_COPY_ZH =
            "已接受您描述的要求。请补充其他要求或请求完成。";

    /** The generic Chinese host copy of a goal clarification.
     *
     * <p>This is the ultimate generic fallback used only when neither the model wording boundary nor
     * a component-aware base is available. */
    static final String CLARIFY_COPY_ZH = "请提供缺失的具体信息以便继续。";

    /** The generic Chinese host copy of an audience/Segment clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. It names no scenario fact,
     * question or catalog value and is used only when the wording boundary is unavailable. */
    static final String AUDIENCE_CLARIFY_COPY_ZH =
            "该受众条件缺少必要细节。请补充缺失信息，以便生成可执行规则。";

    /** The generic Chinese host copy of a message/communication clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String MESSAGE_CLARIFY_COPY_ZH =
            "消息或触达渠道缺少必要信息。请补充后继续。";

    /** The generic Chinese host copy of a promotion/offer clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String PROMOTION_CLARIFY_COPY_ZH =
            "优惠或奖励缺少必要信息。请补充后继续。";

    /** The generic Chinese host copy of a schedule clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String SCHEDULE_CLARIFY_COPY_ZH =
            "排期或时间安排缺少必要信息。请补充后继续。";

    /** The generic Chinese host copy of a journey/branch clarification.
     *
     * <p>This sentence is the component-aware base; the specific missing-fact semantics are carried
     * by the structured question codes passed to the wording boundary. */
    static final String JOURNEY_CLARIFY_COPY_ZH =
            "流程或分支方案缺少必要信息。请补充后继续。";

    /** The generic English host copy when the objective is already confirmed. */
    static final String OBJECTIVE_CONFIRMED_COPY_EN =
            "Your goal is understood. Supply any requirement or ask to begin.";

    /** The generic Chinese host copy when the objective is already confirmed. */
    static final String OBJECTIVE_CONFIRMED_COPY_ZH =
            "已了解您的目标。请补充任何要求或请求开始。";

    /**
     * The generic Chinese fallback copy of a Governance clarification.
     */
    static final String GOVERNANCE_LIMIT_COPY_ZH =
            "需要明确的上限：一个正数最大收件人数、一个正数最大奖励发放数、精确到两位小数的奖励面额以及一个货币代码。不存"
                    + "在无限和无默认值的情况。";

    /**
     * The generic Chinese fallback copy of an unsupported-channel clarification.
     */
    static final String CHANNEL_UNSUPPORTED_COPY_ZH =
            "当前资源目录不支持该触达渠道。请选择一个支持的渠道，或去掉触达组件。";

    /**
     * The generic Chinese fallback copy when the communication-channel configuration has zero selectable
     * resources: the channel the user stated is preserved, but validation is blocked until an administrator
     * configures one or more selectable channels.
     */
    static final String COMMUNICATION_CONFIG_UNAVAILABLE_COPY_ZH =
            "通信渠道配置不可用——未配置任何可选渠道。您的渠道选择已保留，但在管理员配置一个或多个可选渠道之前，通信组件无法继续。";

    /**
     * The generic Chinese fallback copy of an audience rephrase clarification.
     */
    static final String AUDIENCE_REPHRASE_COPY_ZH =
            "暂时无法将该受众条件转为严格规则。请用明确的天数或周数重述时间范围，并保留其他条件。";

    /** The generic Chinese host copy of an out-of-scope turn: capability-bound and truthful. */
    static final String OUT_OF_SCOPE_COPY_ZH =
            "本构建器用于创建和编辑营销活动配置。您请求的操作超出其作为创作工具的范围，无法执行。";

    /** The generic Chinese host copy of an accepted finalize. */
    static final String FINALIZED_COPY_ZH =
            "方案已就绪。准备好后可创建计划。";

    /** The generic Chinese host copy when accepted requirements are not yet finalizable. */
    static final String BLOCKED_COPY_ZH =
            "部分要求不可用或无效。请在完成前进行修改。";

    /**
     * The generic English host copy of a MISSING_METADATA_DEFINITION gap.
     *
     * <p>This sentence identifies a system metadata/configuration gap: the resolution could not locate a
     * registered metadata definition for the requested behavior concept. It never asks the user to restate
     * already-complete logical rules and never fabricates an unrelated channel/event reason.
     */
    static final String MISSING_METADATA_DEFINITION_COPY_EN =
            "The system's metadata definitions do not include the requested behaviour concept. "
                    + "An administrator must register a valid definition for this concept before a rule can be built.";

    /**
     * The generic Chinese host copy of a MISSING_METADATA_DEFINITION gap.
     */
    static final String MISSING_METADATA_DEFINITION_COPY_ZH =
            "系统元数据定义中未包含所请求的行为概念。"
                    + "管理员必须先为此概念注册有效定义，才能继续构建规则。";

    /** The generic English host copy of an internal system failure. */
    static final String SYSTEM_FAILURE_COPY_EN =
            "An internal error occurred while processing your request.";

    /** The generic Chinese host copy of an internal system failure. */
    static final String SYSTEM_FAILURE_COPY_ZH =
            "处理您的请求时发生内部错误。";

    // ------------------------------------------------------------------ locale-aware copy selection

    /**
     * Returns the locale-appropriate fallback copy for the given English and Chinese strings.
     *
     * <p>When the user message contains CJK characters (Unicode block CJK Unified Ideographs, range
     * U+4E00–U+9FFF), the Chinese copy is returned. Otherwise the English copy is returned. This is
     * a general locale-detection mechanism that uses no scenario-specific keywords, industry lists, or
     * hardcoded phrase catalogues.
     */
    private static String localeCopy(String english, String chinese, String userMessage) {
        if (userMessage != null) {
            for (int i = 0; i < userMessage.length(); i++) {
                char c = userMessage.charAt(i);
                if (c >= '一' && c <= '鿿') {
                    return chinese;
                }
            }
        }
        return english;
    }

    // Locale-aware helpers that choose between en/zh fallback copies.

    private String audienceFollowUp(String message) {
        return localeCopy(AUDIENCE_FOLLOW_UP_EN, AUDIENCE_FOLLOW_UP_ZH, message);
    }

    private String buildCopy(String message) {
        return localeCopy(BUILD_COPY_EN, BUILD_COPY_ZH, message);
    }

    private String clarifyCopy(String message) {
        return localeCopy(CLARIFY_COPY_EN, CLARIFY_COPY_ZH, message);
    }

    private String governanceLimitCopy(String message) {
        return localeCopy(GOVERNANCE_LIMIT_COPY_EN, GOVERNANCE_LIMIT_COPY_ZH, message);
    }

    private String channelUnsupportedCopy(String message) {
        return localeCopy(CHANNEL_UNSUPPORTED_COPY_EN, CHANNEL_UNSUPPORTED_COPY_ZH, message);
    }

    private String communicationConfigUnavailableCopy(String message) {
        return localeCopy(COMMUNICATION_CONFIG_UNAVAILABLE_COPY_EN, COMMUNICATION_CONFIG_UNAVAILABLE_COPY_ZH, message);
    }

    private String audienceRephraseCopy(String message) {
        return localeCopy(AUDIENCE_REPHRASE_COPY_EN, AUDIENCE_REPHRASE_COPY_ZH, message);
    }

    private String outOfScopeCopy(String message) {
        return localeCopy(OUT_OF_SCOPE_COPY_EN, OUT_OF_SCOPE_COPY_ZH, message);
    }

    private String objectiveConfirmedCopy(String message) {
        return localeCopy(OBJECTIVE_CONFIRMED_COPY_EN, OBJECTIVE_CONFIRMED_COPY_ZH, message);
    }

    private String finalizedCopy(String message) {
        return localeCopy(FINALIZED_COPY_EN, FINALIZED_COPY_ZH, message);
    }

    private String blockedCopy(String message) {
        return localeCopy(BLOCKED_COPY_EN, BLOCKED_COPY_ZH, message);
    }

    // ------------------------------------------------------------------ backward-compatible aliases

    /** @deprecated Use {@link #AUDIENCE_FOLLOW_UP_EN} or {@link #audienceFollowUp(String)}. */
    @Deprecated
    static final String AUDIENCE_FOLLOW_UP = AUDIENCE_FOLLOW_UP_EN;

    /** @deprecated Use {@link #BUILD_COPY_EN} or {@link #buildCopy(String)}. */
    @Deprecated
    static final String BUILD_COPY = BUILD_COPY_EN;

    /** @deprecated Use {@link #CLARIFY_COPY_EN} or {@link #clarifyCopy(String)}. */
    @Deprecated
    static final String CLARIFY_COPY = CLARIFY_COPY_EN;

    /** @deprecated Use {@link #GOVERNANCE_LIMIT_COPY_EN} or {@link #governanceLimitCopy(String)}. */
    @Deprecated
    static final String GOVERNANCE_LIMIT_COPY = GOVERNANCE_LIMIT_COPY_EN;

    /** @deprecated Use {@link #CHANNEL_UNSUPPORTED_COPY_EN} or {@link #channelUnsupportedCopy(String)}. */
    @Deprecated
    static final String CHANNEL_UNSUPPORTED_COPY = CHANNEL_UNSUPPORTED_COPY_EN;

    /** @deprecated Use {@link #AUDIENCE_REPHRASE_COPY_EN} or {@link #audienceRephraseCopy(String)}. */
    @Deprecated
    static final String AUDIENCE_REPHRASE_COPY = AUDIENCE_REPHRASE_COPY_EN;

    /** @deprecated Use {@link #OUT_OF_SCOPE_COPY_EN} or {@link #outOfScopeCopy(String)}. */
    @Deprecated
    static final String OUT_OF_SCOPE_COPY = OUT_OF_SCOPE_COPY_EN;

    /** @deprecated Use {@link #FINALIZED_COPY_EN} or {@link #finalizedCopy(String)}. */
    @Deprecated
    static final String FINALIZED_COPY = FINALIZED_COPY_EN;

    /** @deprecated Use {@link #BLOCKED_COPY_EN} or {@link #blockedCopy(String)}. */
    @Deprecated
    static final String BLOCKED_COPY = BLOCKED_COPY_EN;

    /** @deprecated Use {@link #MISSING_METADATA_DEFINITION_COPY_EN}. */
    @Deprecated
    static final String MISSING_METADATA_DEFINITION_COPY = MISSING_METADATA_DEFINITION_COPY_EN;

    private final CampaignAgentEnvironment environment;
    private final CampaignRoutingDecisionDecoder decoder = new CampaignRoutingDecisionDecoder();

    public CampaignConversationAgent(CampaignAgentEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Runs one bounded user turn against one session.
     *
     * <p>Every failure — a stale revision, a rejected model output, an unavailable model, a failed Skill and a
     * refused artifact — is mapped to one stable machine code and returns the unchanged projection. Nothing
     * raw escapes, and no failed turn changes either the session revision or the workspace snapshot.
     */
    public CampaignAgentTurnResult handle(CampaignAgentSession session, CampaignAgentTurnRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        CampaignRequirementBrief acceptedBrief = session.requirementBrief();
        try {
            return advance(session, request);
        } catch (CampaignAgentException failure) {
            session.setRequirementBrief(acceptedBrief);
            return refused(session, failure.code());
        } catch (RuntimeException failure) {
            // No unexpected exception text, type, cause or model value is ever allowed to escape this boundary.
            session.setRequirementBrief(acceptedBrief);
            return refused(session, CampaignAgentErrorCodes.SKILL_FAILED);
        }
    }

    /** The bounded projection of a session at its current revision, without running a turn. */
    public CampaignConversationProjection inspect(CampaignAgentSession session) {
        Objects.requireNonNull(session, "session");
        return project(session, CampaignConversationProjection.ACCEPTED);
    }

    // ------------------------------------------------------------------ the turn

    private CampaignAgentTurnResult advance(CampaignAgentSession session, CampaignAgentTurnRequest request) {
        if (session.phase().isTerminal()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.SESSION_TERMINAL,
                    "A terminal session cannot accept another turn.");
        }
        if (session.revision() != request.expectedRevision()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.STALE_REVISION,
                    "The expected session revision is not the current one.");
        }
        if (session.turns().size() + 2L > CampaignAgentLimits.MAX_TURNS) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.TURN_LIMIT_EXCEEDED,
                    "The conversation exceeds its accepted-turn bound.");
        }
        CampaignClarification pendingClarification = session.pendingClarification();
        if (environment.requirementExtractor() != null
                && pendingClarification != null
                && REQUIREMENT_CLARIFICATION.equals(pendingClarification.code())) {
            if (!request.answers().isEmpty()) {
                requireAnswersMatch(session, request.answers());
            }
            return collectRequirements(session, request, null);
        }
        if (pendingClarification == null && !request.answers().isEmpty()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                    "No clarification is pending for this conversation.");
        }
        CampaignTurnPosition position = session.position(request.answers());
        if (pendingClarification != null && !request.answers().isEmpty()) {
            requireAnswersMatch(session, request.answers());
        }
        CampaignRoutingDecision decision;
        List<String> pendingSkills = pendingResumeSkills(session);
        if (position == CampaignTurnPosition.ANSWER && !pendingSkills.isEmpty()) {
            refreshRequirementsFromSkillAnswer(session, request, pendingSkills);
            List<String> resumed = new ArrayList<>();
            for (String skillId : pendingSkills) {
                if (requiredByBrief(session.requirementBrief(), skillId)) {
                    resumed.add(skillId);
                }
            }
            if (resumed.isEmpty()) {
                CampaignRoutingDecision corrected = new CampaignRoutingDecision(
                        CampaignRouteKind.CONTINUE_CAMPAIGN, List.of(), List.of(), null, List.of());
                return build(session, request, corrected);
            }
            // A validated typed answer to a Component clarification deterministically resumes EVERY Skill the
            // host recorded as pending. The routing model is never asked to rediscover or re-choose a Skill it
            // already selected, so resuming cannot fail on a second probabilistic routing decision, and no
            // client field or model output can name the resumed agents.
            decision = resumePendingSkills(session, resumed);
            requireHostAccepts(session, decision, position);
            return build(session, request, decision);
        } else {
            decision = decide(session, request, position);
        }
        if (environment.requirementExtractor() != null
                && (decision.routeKind() == CampaignRouteKind.CAMPAIGN_REQUEST
                || decision.routeKind() == CampaignRouteKind.AUDIENCE_REQUEST
                || (decision.routeKind() == CampaignRouteKind.CONTINUE_CAMPAIGN
                && !CampaignRequirementBrief.empty().equals(session.requirementBrief())))) {
            return collectRequirements(session, request, decision);
        }
        return switch (decision.routeKind()) {
            case NEEDS_CLARIFICATION -> clarify(session, request, decision);
            case OUT_OF_SCOPE -> outOfScope(session, request, decision);
            case FINALIZE_CAMPAIGN -> finalizeTurn(session, request, decision);
            default -> build(session, request, decision);
        };
    }

    /**
     * Extracts every Campaign fact supplied alongside one typed Skill answer before that Skill resumes.
     *
     * <p>A user commonly answers the pending Tool question and supplies message, branch or cadence details in
     * the same sentence. Those facts belong to the Campaign brief even though the current typed answer targets
     * one Skill, so discarding them would force duplicate questions and make collection order-dependent.
     */
    private void refreshRequirementsFromSkillAnswer(
            CampaignAgentSession session, CampaignAgentTurnRequest request, List<String> pendingSkills) {
        CampaignRequirementExtractorPort extractor = environment.requirementExtractor();
        CampaignRequirementBrief accepted = session.requirementBrief();
        CampaignMessageInputs.Channel selectedChannel = selectedChannel(
                request.answers(), CampaignAgentErrorCodes.CHANNEL_UNSUPPORTED,
                environment.businessResourceCatalog());
        if (selectedChannel != null) {
            accepted = accepted.withChannels(List.of(selectedChannel));
            session.setRequirementBrief(accepted);
        }
        // A type-less non-event conversation is still collecting requirements, so a fact supplied alongside a
        // typed Skill answer belongs to the brief regardless of whether the Campaign type is known yet.
        if (extractor == null || (!request.answers().isEmpty() && request.answers().stream()
                .allMatch(answer -> answer.optionId() != null && answer.freeText() == null))) {
            return;
        }
        Optional<CampaignRequirementDelta> extracted = extractor.extractDelta(
                requirementUtterance(request), accepted, accepted.nextQuestion());
        if (extracted.isEmpty()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.REQUIREMENT_EXTRACTION_REJECTED,
                    "The Campaign requirement extraction was rejected.");
        }
        CampaignRequirementDelta delta = extracted.get();
        delta = verifiedChannelDelta(delta, request, environment.businessResourceCatalog(), accepted).delta();
        CampaignClarification pending = session.pendingClarification();
        if (pending != null && pendingSkills.contains(CampaignSkillIds.SEGMENT_RULE_BASED)) {
            // The current text is the answer to the Segment resolver's own question. It may also carry edits
            // to other Campaign facts, but it is not a replacement audience definition: the accepted audience
            // remains the stable requirement whose resolution is being resumed.
            delta = delta.withoutAudience();
        }
        CampaignRequirementBrief merged = bindTriggerEvent(
                accepted.merge(delta), request, environment.businessResourceCatalog());
        session.setRequirementBrief(merged);
        synchronizeComponentManifestsFromBrief(
                session, merged, boundedEvidence(requirementUtterance(request)),
                environment.businessResourceCatalog());
        removeInactiveSkills(session, merged);
    }

    private CampaignAgentTurnResult collectRequirements(
            CampaignAgentSession session,
            CampaignAgentTurnRequest request,
            CampaignRoutingDecision routedDecision) {
        CampaignRequirementBrief accepted = session.requirementBrief();
        CampaignType acceptedType = accepted.type();
        // Only the explicit registered EVENT type may be set by routing: a non-event Campaign's type is derived
        // by the host from the accepted schedule mode, so a legacy routed AD_HOC/LIFECYCLE is never
        // authoritative before cadence, and the router is never asked to choose between them.
        CampaignType routedType = routedDecision == null
                ? acceptedType
                : (routedDecision.campaignType() == CampaignType.EVENT ? CampaignType.EVENT : null);
        if (acceptedType != null && routedType != null && acceptedType != routedType) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.CAMPAIGN_TYPE_CONFLICT,
                    "A Campaign type cannot change after it is accepted.");
        }
        CampaignRequirementBrief typed = acceptedType == null && routedType != null
                ? accepted.withType(routedType)
                : accepted;
        CampaignRoutingDecision decision = routedDecision == null
                ? new CampaignRoutingDecision(
                        CampaignRouteKind.CAMPAIGN_REQUEST, routedType, List.of(), List.of(), null, List.of(), null)
                : routedDecision;
        CampaignRequirementCode pending = typed.nextQuestion();
        Optional<CampaignRequirementDelta> extracted = environment.requirementExtractor().extractDelta(
                requirementUtterance(request), typed, pending);
        if (extracted.isEmpty()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.REQUIREMENT_EXTRACTION_REJECTED,
                    "The Campaign requirement extraction was rejected.");
        }
        CampaignBusinessResourceCatalog resourceCatalog = environment.businessResourceCatalog();
        ChannelVerification channelVerification = verifiedChannelDelta(
                extracted.get(), request, resourceCatalog, typed);
        CampaignRequirementDelta delta = channelVerification.delta();
        // Recover EVENT Campaign type from the host business resource catalog when both the routing
        // model and the accepted requirement brief have no type. The catalog — never a hard-coded word
        // or model assertion — is the sole authority: only exactly one selectable registered trigger
        // event whose code or alias is named by the current user utterance promotes the brief.
        // Multiple matches, zero matches or non-selectable-only matches preserve the existing
        // clarification path.
        if (typed.type() == null && acceptedType == null && routedType == null) {
            List<CampaignBusinessResource> named = resourceCatalog.named(
                    CampaignBusinessResourceType.TRIGGER_EVENT, requirementUtterance(request));
            if (named.size() == 1 && named.getFirst().selectable()) {
                typed = typed.withType(CampaignType.EVENT);
            }
        }
        CampaignMessageInputs.Channel selectedChannel = selectedChannel(
                request.answers(), CampaignRequirementCode.CHANNEL_REQUIRED.code(), resourceCatalog);
        if (selectedChannel == null) {
            selectedChannel = selectedChannel(
                    request.answers(), CHANNEL_UNSUPPORTED_QUESTION, resourceCatalog);
        }
        if (selectedChannel != null) {
            delta = delta.withSelectedChannel(selectedChannel);
        }
        CampaignBusinessObjective validatedObjective = validateObjectiveEvidence(delta, request, session);
        CampaignRequirementBrief merged = bindTriggerEvent(typed.merge(delta), request, resourceCatalog);
        if (validatedObjective != null) {
            merged = merged.withConfirmedObjective(validatedObjective);
        }
        session.setRequirementBrief(merged);
        synchronizeComponentManifestsFromBrief(
                session, merged, boundedEvidence(requirementUtterance(request)), resourceCatalog);
        removeInactiveSkills(session, merged);
        session.setSegmentContinuation(null);
        session.setSegmentFreeTextRecovery(null);
        session.setFollowUpPrompt(null);
        session.clearProposal();
        if (selectedChannel != null) {
            observeRequirementValidation(
                    session, CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.MISSING, null);
        }
        if (channelVerification.unsupported() && selectedChannel == null) {
            observeRequirementValidation(
                    session, CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.NEEDS_INPUT,
                    CampaignAgentErrorCodes.CHANNEL_UNSUPPORTED);
            List<CampaignAnswerOption> options =
                    resourceCatalog.options(CampaignBusinessResourceType.COMMUNICATION_CHANNEL);
            String prompt = outcomeCopy(
                    session, request, CampaignAgentErrorCodes.CHANNEL_UNSUPPORTED,
                    channelUnsupportedCopy(request.message()));
            session.setPendingClarification(new CampaignClarification(
                    null, CampaignAgentErrorCodes.CHANNEL_UNSUPPORTED, prompt,
                    List.of(CHANNEL_UNSUPPORTED_QUESTION), options, true));
            session.setAssistantCopy(prompt);
            session.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);
            commit(session, request, decision, prompt);
            return accepted(session, decision.routeKind());
        }
        if (channelVerification.configUnavailable() && selectedChannel == null) {
            // No channel can be validated because the resource catalog has zero selectable entries.
            // The stated channels stay in the brief (intent preserved), but the communication
            // component is blocked, the user is never asked to restate a channel, and the brief
            // cannot be finalized until configuration is available. The host-owned copy is used
            // directly: the model wording boundary is deliberately not consulted for this outcome
            // because it can fabricate missing content, audience or other requirement gaps from
            // training priors rather than reporting the genuine system configuration defect.
            observeRequirementValidation(
                    session, CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.INVALID,
                    CampaignAgentErrorCodes.COMMUNICATION_CONFIG_UNAVAILABLE);
            String prompt = communicationConfigUnavailableCopy(request.message());
            session.setPendingClarification(null);
            session.setAssistantCopy(prompt);
            session.setPhase(CampaignAgentPhase.BUILDING);
            commit(session, request, decision, prompt);
            return accepted(session, decision.routeKind());
        }
        List<String> validationSkills = validationSkills(session, typed, merged, delta);
        if (!validationSkills.isEmpty()) {
            CampaignRoutingDecision validation = new CampaignRoutingDecision(
                    CampaignRouteKind.CONTINUE_CAMPAIGN, validationSkills, List.of(), null, List.of());
            return build(session, request, validation);
        }
        if (merged.completeForAssembly()) {
            // A complete brief is necessary but not sufficient: the Campaign is only ready when the workspace
            // itself holds no invalid Skill, no missing dependency and no outstanding component TODO.
            boolean finalizable = readyToFinalize(session, session.workspace());
            String copy = finalizable
                    ? outcomeCopy(session, request, "CAMPAIGN_REQUIREMENTS_COMPLETE",
                            buildCopy(request.message()))
                    : outcomeCopy(session, request, "CAMPAIGN_REQUIREMENTS_INCOMPLETE",
                            blockedCopy(request.message()));
            session.setPendingClarification(null);
            session.setAssistantCopy(copy);
            session.setPhase(finalizable
                    ? CampaignAgentPhase.READY_TO_FINALIZE
                    : CampaignAgentPhase.BUILDING);
            commit(session, request, decision, copy);
            return accepted(session, decision.routeKind());
        }

        CampaignRequirementCode next = merged.nextQuestion();
        List<CampaignAnswerOption> options = resourceOptions(next, resourceCatalog);
        String prompt = environment.requirementExtractor()
                .clarificationQuestion(request.message(), merged, next, options)
                .orElseThrow(() -> CampaignAgentException.of(
                        CampaignAgentErrorCodes.REQUIREMENT_EXTRACTION_REJECTED,
                        "The Campaign requirement clarification was rejected."));
        session.setPendingClarification(new CampaignClarification(
                null, REQUIREMENT_CLARIFICATION, prompt, List.of(next.code()),
                options, true));
        session.setAssistantCopy(prompt);
        session.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);
        commit(session, request, decision, prompt);
        return accepted(session, decision.routeKind());
    }

    private static ChannelVerification verifiedChannelDelta(
            CampaignRequirementDelta delta,
            CampaignAgentTurnRequest request,
            CampaignBusinessResourceCatalog catalog,
            CampaignRequirementBrief acceptedBrief) {
        if (delta == null) {
            return new ChannelVerification(delta, null);
        }
        // When the catalog has zero selectable resources for this type, the host has no data to
        // validate or reject any channel the user named. Preserve every explicitly stated channel
        // in the delta (stated intent is preserved) but fail closed: the communication component
        // is blocked, the brief is not finalizable, and the user is never asked to restate a
        // channel they already specified. Only mark config unavailable when a channel fact is
        // actually present in this turn or the accepted brief, so an unrelated Segment, Offer
        // or Journey component can still produce its TODO and result in the same turn.
        List<CampaignBusinessResource> selectable = catalog.selectable(
                CampaignBusinessResourceType.COMMUNICATION_CHANNEL);
        if (selectable.isEmpty()) {
            boolean channelPresent = !delta.channels().isEmpty()
                    || (acceptedBrief != null && !acceptedBrief.channels().isEmpty());
            return new ChannelVerification(delta,
                    channelPresent ? VerificationState.CONFIG_UNAVAILABLE : VerificationState.SUPPORTED);
        }
        String evidence = requirementUtterance(request);
        List<CampaignBusinessResource> named = catalog.named(
                CampaignBusinessResourceType.COMMUNICATION_CHANNEL, evidence);
        boolean unsupported = named.stream().anyMatch(resource -> !resource.selectable());
        VerificationState state = unsupported ? VerificationState.UNSUPPORTED : VerificationState.SUPPORTED;
        if (delta.channels().isEmpty()) {
            return new ChannelVerification(delta, state);
        }
        for (CampaignMessageInputs.Channel channel : delta.channels()) {
            CampaignBusinessResource resource = catalog.selectable(
                    CampaignBusinessResourceType.COMMUNICATION_CHANNEL, channel.name());
            if (resource == null || (!named.isEmpty() && !named.contains(resource))) {
                return new ChannelVerification(delta.withoutChannels(),
                        unsupported || resource == null
                                ? VerificationState.UNSUPPORTED
                                : VerificationState.SUPPORTED);
            }
        }
        return new ChannelVerification(delta, state);
    }

    private enum VerificationState {
        SUPPORTED,
        UNSUPPORTED,
        CONFIG_UNAVAILABLE;
    }

    private record ChannelVerification(CampaignRequirementDelta delta, VerificationState state) {

        ChannelVerification {
            if (delta == null) {
                state = null;
            }
        }

        boolean unsupported() {
            return state == VerificationState.UNSUPPORTED;
        }

        boolean configUnavailable() {
            return state == VerificationState.CONFIG_UNAVAILABLE;
        }
    }

    private static void observeRequirementValidation(
            CampaignAgentSession session,
            String skillId,
            CampaignComponentState state,
            String code) {
        List<CampaignAgentSession.Step> planned = CampaignAgentSession.withObservation(
                session.steps(), skillId, state, code);
        CampaignAssemblyWorkspace rebuilt = session.rebuild(planned);
        if (rebuilt == null) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.ARTIFACT_REFUSED,
                    "The T27 assembly workspace refused one requirement validation observation.");
        }
        session.adopt(planned, rebuilt);
    }

    private static CampaignRequirementBrief bindTriggerEvent(
            CampaignRequirementBrief brief,
            CampaignAgentTurnRequest request,
            CampaignBusinessResourceCatalog catalog) {
        if (brief.type() != CampaignType.EVENT) {
            return brief;
        }
        String selected = selectedResourceCode(
                request.answers(), CampaignRequirementCode.EVENT_REQUIRED.code(), catalog,
                CampaignBusinessResourceType.TRIGGER_EVENT);
        if (selected != null) {
            return brief.withTriggerEventCode(selected);
        }
        List<CampaignBusinessResource> named = catalog.named(
                CampaignBusinessResourceType.TRIGGER_EVENT, requirementUtterance(request));
        if (named.size() == 1 && named.getFirst().selectable()) {
            return brief.withTriggerEventCode(named.getFirst().code());
        }
        return brief;
    }

    private static CampaignMessageInputs.Channel selectedChannel(
            List<CampaignClarificationAnswer> answers,
            String questionCode,
            CampaignBusinessResourceCatalog catalog) {
        String selected = selectedResourceCode(
                answers, questionCode, catalog, CampaignBusinessResourceType.COMMUNICATION_CHANNEL);
        return CampaignMessageInputs.Channel.fromCode(selected);
    }

    private static String selectedResourceCode(
            List<CampaignClarificationAnswer> answers,
            String questionCode,
            CampaignBusinessResourceCatalog catalog,
            CampaignBusinessResourceType type) {
        for (CampaignClarificationAnswer answer : answers) {
            if (questionCode.equals(answer.code()) && answer.optionId() != null
                    && catalog.selectable(type, answer.optionId()) != null) {
                return answer.optionId();
            }
        }
        return null;
    }

    private static List<CampaignAnswerOption> resourceOptions(
            CampaignRequirementCode requirement,
            CampaignBusinessResourceCatalog catalog) {
        if (requirement == CampaignRequirementCode.CHANNEL_REQUIRED) {
            return catalog.options(CampaignBusinessResourceType.COMMUNICATION_CHANNEL);
        }
        if (requirement == CampaignRequirementCode.EVENT_REQUIRED) {
            return catalog.options(CampaignBusinessResourceType.TRIGGER_EVENT);
        }
        return List.of();
    }

    private static String requirementUtterance(CampaignAgentTurnRequest request) {
        StringBuilder value = new StringBuilder(request.message());
        for (CampaignClarificationAnswer answer : request.answers()) {
            if (answer.freeText() != null && !request.message().contains(answer.freeText())) {
                value.append('\n').append(answer.freeText());
            }
        }
        return value.toString();
    }

    /** Refuses an answer that does not address the pending typed clarification. */
    private void requireAnswersMatch(CampaignAgentSession session, List<CampaignClarificationAnswer> answers) {
        CampaignClarification pending = session.pendingClarification();
        if (pending == null) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                    "No clarification is pending for this conversation.");
        }
        CampaignClarificationAggregate aggregate = session.pendingComponentClarifications();
        boolean multiComponent = !aggregate.isEmpty() && !aggregate.single();
        for (CampaignClarificationAnswer answer : answers) {
            // Component-ownership validation: for multi-component pending clarifications,
            // every answer must name its component, and its code must belong to that
            // component's pending issue. A legacy unscoped answer is allowed only when
            // exactly one component issue is pending.
            if (multiComponent) {
                if (answer.component() == null) {
                    throw CampaignAgentException.of(
                            CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                            "A multi-component clarification requires every answer to name its owning component.");
                }
                CampaignComponentClarification componentRequest = aggregate.requestFor(
                        answer.component());
                if (componentRequest == null) {
                    throw CampaignAgentException.of(
                            CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                            "A clarification answer names a component that has no pending issue.");
                }
                if (answer.code() != null && !componentRequest.questionCodes().contains(answer.code())) {
                    throw CampaignAgentException.of(
                            CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                            "A clarification answer names a question not pending for its component.");
                }
                if (answer.optionId() != null && componentRequest.options().stream()
                        .noneMatch(option -> option.optionId().equals(answer.optionId()))) {
                    throw CampaignAgentException.of(
                            CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                            "A clarification answer names an option not offered by its component.");
                }
                if (answer.optionId() == null && answer.freeText() != null
                        && !componentRequest.freeTextAllowed()) {
                    throw CampaignAgentException.of(
                            CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                            "This component clarification does not accept free text.");
                }
                continue;
            }
            // Legacy single-question validation (preserved byte-compatible).
            if (answer.code() != null && !pending.questionCodes().contains(answer.code())) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A clarification answer names a question that is not pending.");
            }
            if (answer.optionId() != null && pending.options().stream()
                    .noneMatch(option -> option.optionId().equals(answer.optionId()))) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "A clarification answer names an option that was not offered.");
            }
            if (answer.optionId() == null && answer.freeText() != null && !pending.freeTextAllowed()) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                        "This clarification does not accept free text.");
            }
        }
    }

    /**
     * The exact set of Skills the answer turn must resume, derived from host state alone.
     *
     * <p>The session's own aggregate of pending component clarifications is authoritative: it retains the
     * originating component and Skill of every request the turn collected, so answering resumes every pending
     * component Skill instead of one {@code skillId}. A stored session from before the aggregate existed carries
     * only the projected clarification, so its single Skill identifier is used as the honest fallback rather
     * than dropping the pending question.
     */
    private static List<String> pendingResumeSkills(CampaignAgentSession session) {
        List<String> skills = session.pendingComponentSkills();
        if (!skills.isEmpty()) {
            return skills;
        }
        CampaignClarification pending = session.pendingClarification();
        return pending == null || pending.skillId() == null ? List.of() : List.of(pending.skillId());
    }

    /**
     * The host-owned decision that resumes every Skill whose clarification is pending.
     *
     * <p>It is built from the session's own persisted/replayed host state only: the Skill identifiers are the
     * ones the host recorded when it asked the questions, the route kind is a fixed host value, and no model
     * output, client field or workspace fact contributes to it. Each Skill then runs through the same router, the
     * same command and the same represented answers as any selected Skill, so ordering, bounds, revision
     * semantics and the Segment continuation rules are unchanged. Unrelated ready components are neither rerun
     * nor lost: they are not in this set, and their accepted steps stay in the session.
     */
    private static CampaignRoutingDecision resumePendingSkills(
            CampaignAgentSession session, List<String> pendingSkills) {
        List<String> selected = new ArrayList<>(pendingSkills);
        if (session.requirementBrief().completeForAssembly()) {
            for (String skillId : missingRequiredSkills(session, session.requirementBrief())) {
                if (!selected.contains(skillId)) {
                    selected.add(skillId);
                }
            }
        }
        selected.sort(Comparator.comparingInt(skillId -> CampaignSkillIds.ordered().indexOf(skillId)));
        CampaignRouteKind routeKind = session.requirementBrief().type() == null
                ? CampaignRouteKind.CAMPAIGN_REQUEST
                : CampaignRouteKind.CONTINUE_CAMPAIGN;
        return new CampaignRoutingDecision(
                routeKind, selected,
                List.of(), null, List.of());
    }

    // ------------------------------------------------------------------ bounded routing model boundary

    private CampaignRoutingDecision decide(
            CampaignAgentSession session, CampaignAgentTurnRequest request, CampaignTurnPosition position) {
        CampaignRoutingModelPort port = environment.routingModel();
        if (port == null) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_UNAVAILABLE,
                    "No routing model port is wired for this build.");
        }
        CampaignRoutingRequest routingRequest = new CampaignRoutingRequest(
                session.conversationId(), session.runId(), position,
                session.workspace().inspection().skills(), session.priorSummaries(), request.message());
        byte[] output;
        try {
            output = port.route(routingRequest);
        } catch (RuntimeException failure) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_UNAVAILABLE, "The routing model port failed.");
        }
        if (output != null && output.length > CampaignAgentLimits.MAX_MODEL_OUTPUT_BYTES) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_OUTPUT_OVERSIZED,
                    "The routing model output exceeded its declared bound.");
        }
        CampaignRoutingDecision decision;
        try {
            decision = decoder.decode(output);
        } catch (CampaignAgentException rejection) {
            decision = repair(session, routingRequest, port, rejection.code());
        }
        requireHostAccepts(session, decision, position);
        return decision;
    }

    /** At most one schema-only repair, and only when the host explicitly enabled it. */
    private CampaignRoutingDecision repair(
            CampaignAgentSession session,
            CampaignRoutingRequest routingRequest,
            CampaignRoutingModelPort port,
            String issueCode) {
        if (!environment.repairPolicy().permitsOneRepair()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_OUTPUT_REJECTED,
                    "The routing model output was refused by the strict decoder.");
        }
        byte[] repaired;
        try {
            repaired = port.repair(routingRequest, List.of(issueCode));
        } catch (RuntimeException failure) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_UNAVAILABLE, "The routing model repair call failed.");
        }
        try {
            return decoder.decode(repaired);
        } catch (CampaignAgentException second) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_OUTPUT_REJECTED,
                    "The repaired routing model output was refused by the strict decoder.");
        }
    }

    /** The host re-judges a well-shaped decision against the session it can see and the model cannot. */
    private void requireHostAccepts(
            CampaignAgentSession session,
            CampaignRoutingDecision decision,
            CampaignTurnPosition position) {
        CampaignClarification pending = session.pendingClarification();
        if (pending != null && position != CampaignTurnPosition.ANSWER
                && decision.routeKind() != CampaignRouteKind.FINALIZE_CAMPAIGN) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                    "The pending clarification must be answered before the conversation can continue.");
        }
        List<String> pendingSkills = pendingResumeSkills(session);
        if (position == CampaignTurnPosition.ANSWER && !pendingSkills.isEmpty()
                && (!decision.selectsSkills() || !decision.skillIds().containsAll(pendingSkills))) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.ROUTE_REFUSED,
                    "A clarification answer must resume every Skill that requested it.");
        }
        if (decision.routeKind() != CampaignRouteKind.FINALIZE_CAMPAIGN) {
            return;
        }
        if (session.pendingClarification() != null) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.FINALIZE_NOT_READY,
                    "A pending clarification must be answered before the Campaign can be finalized.");
        }
        var inspection = session.workspace().inspection();
        if (inspection.components().isEmpty()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.FINALIZE_NOT_READY,
                    "No accepted component exists to finalize.");
        }
        if (inspection.skills().stream()
                .anyMatch(skill -> skill.state() == CampaignComponentState.INVALID)) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.FINALIZE_NOT_READY,
                    "An invalid Skill state must be revised before the Campaign can be finalized.");
        }
        if (!inspection.missingDependencyCodes().isEmpty()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.FINALIZE_NOT_READY,
                    "The assembly reports a missing dependency and cannot be finalized.");
        }
        if (environment.requirementExtractor() != null
                && !missingRequiredSkills(session, session.requirementBrief()).isEmpty()) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.FINALIZE_NOT_READY,
                    "Every required Campaign Skill must be validated before finalization.");
        }
    }

    // ------------------------------------------------------------------ objective confirmation

    /**
     * Validates the objective evidence against the current user message and confirms it.
     *
     * <p>Host-validated deterministic provenance: the bounded evidence quote must exist in the current
     * user message after conservative normalization. No semantic similarity or model boolean is used.
     * A failed validation silently discards the evidence without mutating session state.
     * A validated objective replaces any previously confirmed one.
     */
    private void confirmObjective(
            CampaignAgentSession session, CampaignAgentTurnRequest request, String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return;
        }
        if (!CampaignBusinessObjective.provenanceValid(evidence, request.message())) {
            return;
        }
        CampaignBusinessObjective objective = new CampaignBusinessObjective(
                evidence, session.revision(), session.nextTurnId());
        session.setRequirementBrief(
                session.requirementBrief().withConfirmedObjective(objective));
    }

    /**
     * Validates the objective evidence from a requirement delta against the current user message
     * and returns a host-validated {@link CampaignBusinessObjective}, or null.
     *
     * <p>If the delta carries proposed objective evidence, host-validates provenance against the
     * current utterance. A failed validation returns null — the delta is never partially mutated and
     * the accepted objective is preserved. The returned objective is bound to the real session
     * revision and turn identifier, never hard-coded values.
     */
    private CampaignBusinessObjective validateObjectiveEvidence(
            CampaignRequirementDelta delta, CampaignAgentTurnRequest request, CampaignAgentSession session) {
        String proposed = delta.objectiveEvidence();
        if (proposed == null || proposed.isBlank()) {
            return null;
        }
        if (!CampaignBusinessObjective.provenanceValid(proposed, request.message())) {
            return null;
        }
        return new CampaignBusinessObjective(proposed, session.revision(), session.nextTurnId());
    }

    // ------------------------------------------------------------------ route dispositions

    private CampaignAgentTurnResult clarify(
            CampaignAgentSession session, CampaignAgentTurnRequest request, CampaignRoutingDecision decision) {
        if (decision.objectiveEvidence() != null) {
            confirmObjective(session, request, decision.objectiveEvidence());
        }
        CampaignBusinessObjective objective = session.requirementBrief().confirmedObjective();
        // Only suppress a generic goal re-question when a confirmed objective exists.
        // Non-goal clarification codes (e.g. CAMPAIGN_TYPE_REQUIRED) and their questions are preserved.
        boolean isOnlyGoalQuestion = decision.questionCodes().size() == 1
                && GOAL_CLARIFICATION_QUESTION.equals(decision.questionCodes().get(0));
        if (objective != null && isOnlyGoalQuestion) {
            // When a requirement extractor is wired, route through real requirement collection
            // instead of creating an acknowledgment-only turn. A host-built safe corrected
            // routing decision carries no Skill, no invented type and forwards the validated
            // evidence so the extractor produces the next concrete missing-requirement
            // clarification ― audience, offer, channel, and so on ― not the goal code.
            if (environment.requirementExtractor() != null) {
                CampaignRoutingDecision corrected = new CampaignRoutingDecision(
                        CampaignRouteKind.CAMPAIGN_REQUEST, null, List.of(), List.of(), null, List.of(),
                        decision.objectiveEvidence());
                return collectRequirements(session, request, corrected);
            }
            // Legacy: no requirement extractor wired ― deterministically continue with
            // a bounded BUILDING acknowledgment without creating a loop.
            String prompt = outcomeCopy(session, request, "CAMPAIGN_OBJECTIVE_CONFIRMED",
                    objectiveConfirmedCopy(request.message()));
            session.setPendingClarification(null);
            session.setSegmentContinuation(null);
            session.setSegmentFreeTextRecovery(null);
            session.setFollowUpPrompt(null);
            session.setAssistantCopy(prompt);
            session.setPhase(CampaignAgentPhase.BUILDING);
            session.clearProposal();
            commit(session, request, decision, prompt);
            return accepted(session, decision.routeKind());
        }
        String prompt = decision.assistantCopy() == null
                ? outcomeCopy(session, request, "CAMPAIGN_GOAL_CLARIFICATION", clarifyCopy(request.message()))
                : decision.assistantCopy();
        CampaignClarification clarification = new CampaignClarification(
                null, "CAMPAIGN_GOAL_CLARIFICATION", prompt, decision.questionCodes(),
                decision.options(), true);
        session.setPendingClarification(clarification);
        session.setSegmentContinuation(null);
        session.setSegmentFreeTextRecovery(null);
        session.setFollowUpPrompt(null);
        session.setAssistantCopy(prompt);
        session.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);
        session.clearProposal();
        commit(session, request, decision, prompt);
        return accepted(session, decision.routeKind());
    }

    private CampaignAgentTurnResult outOfScope(
            CampaignAgentSession session, CampaignAgentTurnRequest request, CampaignRoutingDecision decision) {
        if (decision.objectiveEvidence() != null) {
            confirmObjective(session, request, decision.objectiveEvidence());
        }
        // The host owns this outcome: out-of-scope copy must be honest and capability-bound, stating
        // only what the builder cannot do without inventing missing requirements, suggesting future
        // actions the user did not request, or describing requirements the user already supplied as
        // absent. The host generic copy is used directly; the model wording boundary is not consulted
        // because it can invent requirement gaps from training priors rather than bounded facts.
        // The model's assistantCopy is deliberately ignored here: it can fabricate missing
        // requirements the user never stated and cannot be trusted for OUT_OF_SCOPE routing.
        String copy = outOfScopeCopy(request.message());
        session.setPendingClarification(null);
        session.setSegmentContinuation(null);
        session.setSegmentFreeTextRecovery(null);
        session.setFollowUpPrompt(null);
        session.setAssistantCopy(copy);
        session.setPhase(CampaignAgentPhase.AWAITING_GOAL);
        session.clearProposal();
        commit(session, request, decision, copy);
        return accepted(session, decision.routeKind());
    }

    private CampaignAgentTurnResult build(
            CampaignAgentSession session, CampaignAgentTurnRequest request, CampaignRoutingDecision decision) {
        if (decision.objectiveEvidence() != null) {
            confirmObjective(session, request, decision.objectiveEvidence());
        }
        List<String> resumeSkillIds = pendingResumeSkills(session);
        boolean requirementValidation = environment.requirementExtractor() != null
                && !CampaignRequirementBrief.empty().equals(session.requirementBrief());
        CampaignRequirementBrief brief = session.requirementBrief();
        // Process manifest-driven clarification answers before dispatching component agents.
        // These answers update the manifest state directly and do not require Skill execution.
        processManifestAnswers(session, request);

        CampaignSkillRouter router = new CampaignSkillRouter(
                session.conversationId(), session.runId(), environment, session.segmentContinuation(),
                session.segmentFreeTextRecovery());
        CampaignComponentAgent componentAgent = new CampaignComponentAgent(
                router, environment.requirementExtractor());

        // One independent task per component, in the server-owned canonical component order. A component the
        // turn selects no Skill of is still issued its own task and returns a deterministic no-op result, so
        // every component always reports exactly one independent TODO/result pair.
        List<CampaignComponentTodo> todos = componentTodos(session);
        List<CampaignComponentResult> results = new ArrayList<>();
        for (CampaignComponentCode component : CampaignComponentCode.values()) {
            results.add(componentAgent.run(componentTask(
                    session, request, brief, component, todos.get(component.ordinal()),
                    decision.skillIds(), resumeSkillIds, requirementValidation)));
        }

        // Every eligible component was dispatched before this point: one component's clarification no longer
        // prevents an unrelated component from completing in the same turn.
        List<CampaignSkillOutcome> outcomes = new ArrayList<>();
        List<CampaignComponentClarification> clarifications = new ArrayList<>();
        for (CampaignComponentResult result : results) {
            outcomes.addAll(result.outcomes());
            clarifications.addAll(result.clarifications());
        }

        // Manifest-driven clarifications: for each capability with an outstanding resource-binding
        // decision, produce a component-scoped clarification owned by exactly the component that
        // owns that capability. These clarifications are host-owned and deterministic; the model
        // wording boundary is not consulted because it can fabricate missing resources.
        CampaignBusinessResourceCatalog resourceCatalog = environment.businessResourceCatalog();
        List<CampaignComponentClarification> manifestClarifications =
                manifestDrivenClarifications(session, resourceCatalog);
        clarifications.addAll(manifestClarifications);
        CampaignSkillOutcome.NeedsInput pending = firstNeedsInput(outcomes);
        CampaignSkillOutcome.Terminal terminal = terminalOutcome(outcomes).orElse(null);

        List<CampaignAgentSession.Step> planned = session.steps();
        CampaignAudienceSummary audience = session.audience();
        CampaignJourneySummary journey = session.journeySummary();
        CampaignSegmentContinuation continuation = null;
        for (CampaignSkillOutcome outcome : outcomes) {
            switch (outcome) {
                case CampaignSkillOutcome.Ready ready -> {
                    planned = CampaignAgentSession.withArtifact(planned, ready.artifact());
                    if (ready.audience() != null) {
                        audience = ready.audience();
                    }
                    if (ready.capabilityContext() != null
                            && CampaignSkillIds.SEGMENT_RULE_BASED.equals(ready.skillId())) {
                        CampaignCompileEvidenceRegistry registry = environment.compileEvidenceRegistry();
                        T19PackIndex packIndex = environment.journeyPackIndex();
                        if (registry != null && packIndex != null) {
                            registry.stage(session.conversationId(), session.runId(),
                                    ready.capabilityContext(), packIndex);
                        }
                    }
                }
                case CampaignSkillOutcome.ReadyMany readyMany ->
                        planned = CampaignAgentSession.withArtifacts(planned, readyMany.artifacts());
                case CampaignSkillOutcome.ReadyJourney readyJourney -> {
                    // A Journey is staged as one atomic bundle, so the whole previous bundle is replaced at once.
                    planned = CampaignAgentSession.withJourneyArtifact(planned, readyJourney.artifact());
                    journey = readyJourney.summary();
                }
                case CampaignSkillOutcome.NeedsInput needsInput -> {
                    planned = CampaignAgentSession.withObservation(
                            planned, needsInput.skillId(), CampaignComponentState.NEEDS_INPUT, needsInput.code());
                    if (needsInput.continuation() != null) {
                        // Only a genuine Segment clarification has a deterministic resolution a later turn can
                        // replay; it is the one shape that stores continuation evidence with the session.
                        continuation = needsInput.continuation();
                    }
                }
                case CampaignSkillOutcome.Unavailable unavailable -> planned =
                        CampaignAgentSession.withObservation(
                                planned, unavailable.skillId(), CampaignComponentState.INVALID,
                                unavailable.code());
                case CampaignSkillOutcome.Terminal reached -> planned = CampaignAgentSession.withObservation(
                        planned, reached.skillId(), CampaignComponentState.INVALID, reached.code());
            }
        }

        // Every step is re-proved by the genuine T27 workspace before anything is adopted.
        CampaignAssemblyWorkspace rebuilt = session.rebuild(planned);
        if (rebuilt == null) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.ARTIFACT_REFUSED,
                    "The T27 assembly workspace refused one accepted Skill artifact.");
        }
        session.adopt(planned, rebuilt);

        // When a component-owned Segment clarification answer materially refines the audience
        // AND the Segment component validated it (returned Ready), update the requirement brief's
        // audience fact. The update is typed and provenance-aware: only the Segment owner may
        // refine the audience, and only after it has validated the answer through its genuine
        // resolution chain. The pre-turn pending aggregate is captured before the session's state
        // mutation, so the answer codes it authorises reflect the exact requests the user
        // answered — not a later-projected or mutated set.
        if (!resumeSkillIds.isEmpty() && resumeSkillIds.contains(CampaignSkillIds.SEGMENT_RULE_BASED)) {
            boolean segmentValidated = outcomes.stream().anyMatch(outcome ->
                    outcome instanceof CampaignSkillOutcome.Ready ready
                            && CampaignSkillIds.SEGMENT_RULE_BASED.equals(ready.artifact().skillId()));
            CampaignClarificationAggregate preTurnAggregate = session.pendingComponentClarifications();
            CampaignClarification pendingAtTurnStart = session.pendingClarification();
            String refinementText = audienceRefinementText(
                    request.answers(), preTurnAggregate, pendingAtTurnStart);
            boolean hasRefinement = refinementText != null && !refinementText.isBlank();
            if (segmentValidated && hasRefinement) {
                String refinedAudience = composeRefinedAudience(
                        session.requirementBrief().audience(), refinementText);
                session.setRequirementBrief(session.requirementBrief().withAudience(refinedAudience));
            }
        }

        // When a component-owned Communication clarification answer supplies the message body
        // AND the Message component validated it (returned Ready), update the requirement brief's
        // message fact from that exact provenance-bound answer. The update is typed and
        // provenance-aware: only the Communication owner may supply the message, and only after
        // the Message Skill has validated the answer through its genuine Tool chain. The brief is
        // never updated when the component did not validate the answer.
        if (!resumeSkillIds.isEmpty() && resumeSkillIds.contains(CampaignSkillIds.MESSAGE_TEMPLATE)) {
            boolean messageValidated = outcomes.stream().anyMatch(outcome ->
                    outcome instanceof CampaignSkillOutcome.Ready ready
                            && CampaignSkillIds.MESSAGE_TEMPLATE.equals(ready.artifact().skillId()));
            String messageFreeText = messageFreeTextAnswer(request.answers());
            boolean hasMessageAnswer = messageFreeText != null && !messageFreeText.isBlank();
            if (messageValidated && hasMessageAnswer) {
                session.setRequirementBrief(
                        session.requirementBrief().withMessage(messageFreeText));
            }
        }

        List<CampaignComponentView> components = rebuilt.inspection().components();
        if (components.stream().noneMatch(component -> CampaignSkillIds.JOURNEY_FLOW.equals(component.skillId()))) {
            // No Journey bundle is staged any more, so no Journey summary may be reported: a stale summary would
            // claim a graph the assembly no longer holds.
            journey = null;
        }
        boolean hasInvalidSkill = rebuilt.inspection().skills().stream()
                .anyMatch(skill -> skill.state() == CampaignComponentState.INVALID);
        boolean audienceOnly = components.size() == 1
                && CampaignSkillIds.SEGMENT_RULE_BASED.equals(components.get(0).skillId());
        CampaignAgentPhase phase;
        String followUp = null;
        CampaignClarification nextClarification = null;
        String requirementPrompt = null;
        // A pending component clarification survives a terminal from an unrelated component: one component's
        // recoverable NEEDS_INPUT is not invalidated by another component's non-recoverable outcome. Only when
        // no component asks a question does a terminal reach the whole turn. Already-ready artifacts are
        // preserved in the planned steps above; unrelated ready components are never rerun.
        if (terminal != null && pending == null) {
            phase = CampaignAgentPhase.TERMINAL_INVALID;
        } else if (terminal != null) {
            // At least one component still has a recoverable clarification. The turn stays AWAITING_CLARIFICATION
            // rather than becoming TERMINAL_INVALID, so the user can answer the outstanding question while the
            // already-ready components are preserved. A bounded retry budget per component is enforced by the
            // component agent itself (see CampaignComponentAgent / CampaignSkillRouter).
            phase = CampaignAgentPhase.AWAITING_CLARIFICATION;
        } else if (environment.requirementExtractor() != null
                && !CampaignRequirementBrief.empty().equals(session.requirementBrief())
                && !session.requirementBrief().completeForAssembly()
                && (pending == null || CampaignSkillIds.JOURNEY_FLOW.equals(pending.skillId()))) {
            CampaignRequirementCode next = session.requirementBrief().nextQuestion();
            List<CampaignAnswerOption> options = resourceOptions(next, environment.businessResourceCatalog());
            requirementPrompt = environment.requirementExtractor()
                    .clarificationQuestion(request.message(), session.requirementBrief(), next, options)
                    .orElseThrow(() -> CampaignAgentException.of(
                            CampaignAgentErrorCodes.REQUIREMENT_EXTRACTION_REJECTED,
                            "The Campaign requirement clarification was rejected."));
            nextClarification = new CampaignClarification(
                    null, REQUIREMENT_CLARIFICATION, requirementPrompt,
                    List.of(next.code()), options, true);
            phase = CampaignAgentPhase.AWAITING_CLARIFICATION;
        } else if (pending != null) {
            phase = CampaignAgentPhase.AWAITING_CLARIFICATION;
        } else if (components.isEmpty()) {
            phase = environment.requirementExtractor() != null && hasInvalidSkill
                    ? CampaignAgentPhase.BUILDING
                    : CampaignAgentPhase.AWAITING_GOAL;
        } else if (hasInvalidSkill) {
            phase = CampaignAgentPhase.BUILDING;
        } else if (audienceOnly) {
            phase = CampaignAgentPhase.AUDIENCE_READY;
            followUp = phaseCopy(session, request, phase, outcomes);
        } else if (readyToFinalize(session, rebuilt)) {
            phase = CampaignAgentPhase.READY_TO_FINALIZE;
        } else {
            phase = CampaignAgentPhase.BUILDING;
        }

        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(clarifications);
        // Component prompts produced by CampaignComponentClarification.from(...) are final.
        // The Main Agent must not model-rewrite, normalize or word component questions.
        // Multi-component top-level prompt is a deterministic concatenation of the exact
        // component prompts.
        CampaignClarification resolvedClarification = nextClarification != null
                ? nextClarification
                : !aggregate.isEmpty()
                        ? aggregate.project(
                                environment.requirementExtractor(), brief, continuation != null)
                        : null;
        List<CampaignComponentClarification> projectedRequests =
                nextClarification == null
                        ? componentIssues(aggregate, resolvedClarification)
                        : List.of();
        String copy = requirementPrompt != null
                ? requirementPrompt
                : decision.assistantCopy() != null
                        ? decision.assistantCopy()
                        : resolvedClarification != null
                                ? resolvedClarification.prompt()
                                : phaseCopy(session, request, phase, outcomes);
        session.setAudience(audience);
        session.setJourneySummary(journey);
        session.setFollowUpPrompt(followUp);
        session.setAssistantCopy(copy);
        session.setPendingClarifications(resolvedClarification, projectedRequests);
        // The continuation evidence lives exactly as long as its pending clarification: a resolved, terminal
        // or abandoned Segment Skill clears it, and only a still-open Segment question keeps it.
        session.setSegmentContinuation(continuation);
        // The free-text Segment recovery state lives exactly as long as its pending NeedsInput:
        // the router creates or updates it during the Segment run, and the session persists it
        // across turns so the budget counter is durable. A resolved, terminal, or abandoned
        // Segment clears it.
        session.setSegmentFreeTextRecovery(router.segmentFreeTextRecovery());
        session.setPhase(phase);
        session.clearProposal();
        commit(session, request, decision, copy);
        return accepted(session, decision.routeKind());
    }

    /**
     * Returns the component issues from the aggregate.
     *
     * <p>No model rewording is applied: component prompts produced by
     * {@link CampaignComponentClarification#from(CampaignComponentCode, CampaignSkillOutcome.NeedsInput)}
     * are final for this path. The Main Agent may only deterministically order them.
     *
     * <p>For a single-issue aggregate the projected {@link CampaignClarification#prompt()} equals the
     * component issue's prompt exactly. This method explicitly copies the resolved clarification's
     * prompt into the component issue to guarantee the invariant.
     *
     * <p>Component attribution — the originating component, the owning Skill, the stable question
     * codes and the answer options — is preserved exactly as it was returned. No component question
     * is dropped and no prompt detail is fabricated.
     */
    private static List<CampaignComponentClarification> componentIssues(
            CampaignClarificationAggregate aggregate,
            CampaignClarification resolvedClarification) {
        if (aggregate == null || aggregate.isEmpty()) {
            return List.of();
        }
        if (aggregate.single() && resolvedClarification != null) {
            CampaignComponentClarification original = aggregate.requests().get(0);
            return List.of(original.withPrompt(resolvedClarification.prompt()));
        }
        return aggregate.requests();
    }

    // ------------------------------------------------------------------ component orchestration

    /**
     * The host-derived TODO of every component, in the canonical component order.
     *
     * <p>It is the same pure derivation the projection uses, so the TODO handed to a component agent and the
     * TODO the user sees can never disagree.
     */
    private List<CampaignComponentTodo> componentTodos(CampaignAgentSession session) {
        CampaignTurnTime trustedTime = environment.trustedTime();
        return CampaignComponentTodo.from(
                session.requirementBrief(), session.workspace().inspection().skills(),
                trustedTime == null ? null : trustedTime.instant(),
                trustedTime == null ? null : trustedTime.zone());
    }

    /**
     * One independent, component-scoped task for one component of this turn.
     *
     * <p>The task carries only the commands of Skills this component owns, in the component's own declaration
     * order, so the component agent can never route outside its ownership. A component the turn selected no
     * Skill of gets an empty command list and therefore a deterministic completed/no-op result.
     *
     * <p>An already-complete component whose todo is {@code COMPLETE} is handed no commands unless this turn
     * is explicitly resuming its pending clarification Skills. A normal new message (not a resume answer)
     * must not cause an unrelated READY component to rerun, because a model-selected Skill does not imply
     * the component's requirement facts have changed and a re-run could displace an accepted artifact.
     */
    private static CampaignComponentTask componentTask(
            CampaignAgentSession session,
            CampaignAgentTurnRequest request,
            CampaignRequirementBrief brief,
            CampaignComponentCode component,
            CampaignComponentTodo todo,
            List<String> selectedSkillIds,
            List<String> resumeSkillIds,
            boolean requirementValidation) {
        // Filter: an already-complete component is handed no commands unless it has pending resume Skills
        // from its own clarifications. A normal new message must not cause an unrelated READY component
        // to rerun.
        boolean skipComplete = todo.complete()
                && component.skillIds().stream().noneMatch(resumeSkillIds::contains);
        // Filter answers so a component receives only answers owned by it. A legacy unscoped answer
        // (null component) is included for every component only when exactly one component issue is
        // pending; otherwise it belongs to no component and must be dropped. An answer that names a
        // different component is never included.
        List<CampaignClarificationAnswer> ownAnswers = componentAnswers(request.answers(), component);
        if (skipComplete) {
            return new CampaignComponentTask(
                    component, todo, brief, request.message(), ownAnswers, List.of());
        }
        List<CampaignSkillCommand> commands = new ArrayList<>();
        for (String skillId : component.skillIds()) {
            if (!selectedSkillIds.contains(skillId)) {
                continue;
            }
            commands.add(CampaignSkillCommand.forSkill(
                    skillId,
                    skillUtterance(session, request, skillId, resumeSkillIds, requirementValidation),
                    ownAnswers, minimalJourney(brief), brief.triggerEventCode()));
        }
        return new CampaignComponentTask(
                component, todo, brief, request.message(), ownAnswers, commands);
    }

    /**
     * Returns the answers owned by one component from a turn's answers.
     *
     * <p>A named-component answer belongs to exactly the named component. A legacy unscoped answer
     * (null component) is included only when the request has no multi-component scoped answers,
     * which by host validation means exactly one component is pending. An answer owned by a
     * different component is never included, so no component ever receives another component's
     * free text or option.
     */
    private static List<CampaignClarificationAnswer> componentAnswers(
            List<CampaignClarificationAnswer> answers, CampaignComponentCode component) {
        if (answers.isEmpty()) {
            return answers;
        }
        boolean anyScoped = answers.stream().anyMatch(a -> a.component() != null);
        List<CampaignClarificationAnswer> own = new ArrayList<>();
        for (CampaignClarificationAnswer answer : answers) {
            if (answer.component() == component) {
                own.add(answer);
            } else if (answer.component() == null && !anyScoped) {
                own.add(answer);
            }
        }
        return List.copyOf(own);
    }

    /**
     * The bounded utterance one Skill of a component receives.
     *
     * <p>It is host-owned evidence only: a resumed clarification Skill sees the exact document that binds it to
     * its own accepted requirement, any other Skill validated against the accepted brief sees the bounded
     * validation document, and every other command sees the latest user message. The routing model never
     * contributes to it.
     */
    private static String skillUtterance(
            CampaignAgentSession session,
            CampaignAgentTurnRequest request,
            String skillId,
            List<String> resumeSkillIds,
            boolean requirementValidation) {
        if (requirementValidation
                && CampaignSkillIds.SEGMENT_RULE_BASED.equals(skillId)
                && resumeSkillIds.contains(skillId)
                && session.segmentContinuation() == null) {
            return segmentClarificationDocument(session, request);
        }
        if (requirementValidation
                && CampaignSkillIds.MESSAGE_TEMPLATE.equals(skillId)
                && resumeSkillIds.contains(skillId)) {
            String document = messageClarificationDocument(session, request);
            if (document != null) {
                return document;
            }
        }
        if (requirementValidation && !resumeSkillIds.contains(skillId)) {
            return validationDocument(session, request, skillId);
        }
        return request.message();
    }

    /** The first genuine clarification of one turn's outcomes, in canonical component order, or null. */
    private static CampaignSkillOutcome.NeedsInput firstNeedsInput(List<CampaignSkillOutcome> outcomes) {
        for (CampaignSkillOutcome outcome : outcomes) {
            if (outcome instanceof CampaignSkillOutcome.NeedsInput needsInput) {
                return needsInput;
            }
        }
        return null;
    }

    private static List<String> validationSkills(
            CampaignAgentSession session,
            CampaignRequirementBrief accepted,
            CampaignRequirementBrief merged,
            CampaignRequirementDelta delta) {
        List<String> selected = new ArrayList<>();
        if (delta.audience() != null) {
            selected.add(CampaignSkillIds.SEGMENT_RULE_BASED);
        }
        // Audience definitions are executable component inputs on their own. Validate them immediately so a
        // missing SKU, field or data source is reported by the Segment component before the main Agent asks an
        // unrelated Campaign question. Other components still wait for a complete assembly brief because their
        // adapters may depend on cross-component references that do not exist yet.
        if (!merged.completeForAssembly()) {
            return List.copyOf(selected);
        }
        if (merged.communicationDecision() == CommunicationDecision.DEFINED
                && (delta.message() != null || !delta.channels().isEmpty())
                && (!merged.channels().isEmpty() || merged.message() != null)) {
            selected.add(CampaignSkillIds.MESSAGE_TEMPLATE);
        }
        if ((delta.offerDecision() != null || delta.offerDetail() != null)
                && merged.offerDecision() == OfferDecision.DEFINED && merged.offerDetail() != null) {
            selected.add(CampaignSkillIds.PROMOTION_REFERENCE);
        }
        if ((delta.branchDecision() != null || delta.branchRule() != null)
                && merged.branchDecision() != BranchDecision.UNKNOWN
                && (merged.branchDecision() == BranchDecision.NONE || merged.branchRule() != null)) {
            selected.add(CampaignSkillIds.JOURNEY_FLOW);
        }
        if (!java.util.Objects.equals(accepted.triggerEventCode(), merged.triggerEventCode())
                && merged.triggerEventCode() != null
                && !selected.contains(CampaignSkillIds.JOURNEY_FLOW)) {
            selected.add(CampaignSkillIds.JOURNEY_FLOW);
        }
        if (merged.type() != CampaignType.EVENT
                && merged.cadence() != null && merged.schedule().missing(merged.cadence()).isEmpty()
                && (delta.cadence() != null || !delta.schedule().isEmpty())) {
            // The Schedule Skill is staged only once the accepted schedule evidence is complete for its mode: a
            // missing time, timezone or window must keep the component outstanding and stage no schedule.
            selected.add(CampaignSkillIds.SCHEDULE_BASIC);
        }
        if (merged.completeForAssembly()) {
            for (String skillId : missingRequiredSkills(session, merged)) {
                if (!selected.contains(skillId)) {
                    selected.add(skillId);
                }
            }
        }
        return List.copyOf(selected);
    }

    private static List<String> missingRequiredSkills(
            CampaignAgentSession session, CampaignRequirementBrief brief) {
        if (brief == null || !brief.completeForAssembly()) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        if (brief.audienceApplicable()) {
            // A trigger-only EVENT has no entry audience of its own, so it needs no Segment component and no
            // Segment Skill must be validated before it can be finalized.
            required.add(CampaignSkillIds.SEGMENT_RULE_BASED);
        }
        if (brief.communicationDecision() == CommunicationDecision.DEFINED) {
            required.add(CampaignSkillIds.MESSAGE_TEMPLATE);
        }
        if (brief.offerDecision() == OfferDecision.DEFINED) {
            required.add(CampaignSkillIds.PROMOTION_REFERENCE);
        }
        required.add(CampaignSkillIds.JOURNEY_FLOW);
        if (brief.type() != CampaignType.EVENT) {
            required.add(CampaignSkillIds.SCHEDULE_BASIC);
        }
        if (brief.offerDecision() == OfferDecision.DEFINED) {
            required.add(CampaignSkillIds.GOVERNANCE_LIMIT);
        }
        List<String> missing = new ArrayList<>();
        for (String skillId : required) {
            boolean ready = session.workspace().inspection().skills().stream()
                    .anyMatch(state -> skillId.equals(state.skillId())
                            && state.state() == CampaignComponentState.READY);
            if (!ready) {
                missing.add(skillId);
            }
        }
        return List.copyOf(missing);
    }

    private static boolean requiredByBrief(CampaignRequirementBrief brief, String skillId) {
        if (CampaignSkillIds.MESSAGE_TEMPLATE.equals(skillId)) {
            return brief.communicationDecision() != CommunicationDecision.NONE;
        }
        if (CampaignSkillIds.PROMOTION_REFERENCE.equals(skillId)
                || CampaignSkillIds.GOVERNANCE_LIMIT.equals(skillId)) {
            return brief.offerDecision() != OfferDecision.NONE;
        }
        if (CampaignSkillIds.SCHEDULE_BASIC.equals(skillId)) {
            return brief.type() != CampaignType.EVENT;
        }
        return true;
    }

    private static boolean minimalJourney(CampaignRequirementBrief brief) {
        return brief.branchDecision() == BranchDecision.NONE
                && brief.communicationDecision() == CommunicationDecision.NONE
                && brief.offerDecision() == OfferDecision.NONE;
    }

    /** Removes components made inapplicable by an explicit corrected requirement decision. */
    private static void removeInactiveSkills(CampaignAgentSession session, CampaignRequirementBrief brief) {
        List<CampaignAgentSession.Step> planned = session.steps();
        boolean actionRemoved = false;
        if (brief.communicationDecision() == CommunicationDecision.NONE) {
            List<CampaignAgentSession.Step> withoutMessage =
                    CampaignAgentSession.withoutSkill(planned, CampaignSkillIds.MESSAGE_TEMPLATE);
            actionRemoved = !withoutMessage.equals(planned);
            planned = withoutMessage;
        }
        if (brief.offerDecision() == OfferDecision.NONE) {
            List<CampaignAgentSession.Step> withoutPromotion =
                    CampaignAgentSession.withoutSkill(planned, CampaignSkillIds.PROMOTION_REFERENCE);
            List<CampaignAgentSession.Step> withoutGovernance =
                    CampaignAgentSession.withoutSkill(withoutPromotion, CampaignSkillIds.GOVERNANCE_LIMIT);
            actionRemoved = actionRemoved || !withoutGovernance.equals(planned);
            planned = withoutGovernance;
        }
        if (actionRemoved) {
            planned = CampaignAgentSession.withoutSkill(planned, CampaignSkillIds.JOURNEY_FLOW);
            session.setJourneySummary(null);
        }
        if (planned.equals(session.steps())) {
            return;
        }
        CampaignAssemblyWorkspace rebuilt = session.rebuild(planned);
        if (rebuilt == null) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.ARTIFACT_REFUSED,
                    "The T27 assembly workspace refused a corrected component removal.");
        }
        session.adopt(planned, rebuilt);
    }

    /**
     * True only when the session may report READY_TO_FINALIZE.
     *
     * <p>The bar is the assembly plus, when a requirement extractor drives the conversation, the accepted brief:
     * at least one component, no invalid Skill, no workspace missing-dependency code, a complete brief, every
     * required Skill READY and every applicable {@link CampaignComponentTodo} COMPLETE. It is a pure function of
     * the accepted brief and the genuine workspace, so no routed type claim, stored state or model assertion can
     * make it true.
     */
    private boolean readyToFinalize(CampaignAgentSession session, CampaignAssemblyWorkspace workspace) {
        var inspection = workspace.inspection();
        if (inspection.components().isEmpty()) {
            return false;
        }
        if (inspection.skills().stream()
                .anyMatch(skill -> skill.state() == CampaignComponentState.INVALID)) {
            return false;
        }
        if (!inspection.missingDependencyCodes().isEmpty()) {
            return false;
        }
        CampaignRequirementBrief brief = session.requirementBrief();
        if (environment.requirementExtractor() == null
                || CampaignRequirementBrief.empty().equals(brief)) {
            // No requirement brief is engaged, so the assembly alone decides readiness.
            return true;
        }
        if (!brief.completeForAssembly()) {
            return false;
        }
        if (!missingRequiredSkills(session, brief).isEmpty()) {
            return false;
        }
        // REQUIRED resource-binding capabilities must have at least one bound selectable resource
        // from the host catalog. The catalog is the sole authority; a model-invented resource that
        // never passes the catalog check cannot satisfy this gate.
        if (!resourceBindingsComplete(session)) {
            return false;
        }
        CampaignTurnTime trustedTime = environment.trustedTime();
        return CampaignComponentTodo.from(
                        brief,
                        inspection.skills(),
                        trustedTime == null ? null : trustedTime.instant(),
                        trustedTime == null ? null : trustedTime.zone())
                .stream()
                .allMatch(todo -> todo.status() == CampaignTodoStatus.COMPLETE);
    }

    /**
     * Produces component-scoped clarifications for each manifest that has an outstanding
     * resource-binding or usage decision.
     *
     * <p>Each clarification is owned by exactly the component that owns the capability, so
     * a RESOURCE_SELECTION for EVENT belongs to the JOURNEY component, and a COMPONENT_DECISION
     * for MESSAGE belongs to COMMUNICATION. Every component only ever receives its own questions.
     *
     * <p>Three clarification kinds are produced:
     * <ul>
     *   <li>COMPONENT_DECISION — the model returned UNCERTAIN; the user is asked yes/no.</li>
     *   <li>RESOURCE_SELECTION — REQUIRED with at least one selectable catalog resource unbound;
     *       the user picks from the real catalog.</li>
     *   <li>RESOURCE_BLOCKED — REQUIRED but no selectable resource exists in the catalog;
     *       the user is told configuration is missing.</li>
     * </ul>
     *
     * <p>The model wording boundary is deliberately not consulted for these clarifications:
     * it can fabricate missing resources, invent plausible-but-wrong option labels, or claim
     * resource gaps do not exist. The host owns these outcomes deterministically.
     */
    private List<CampaignComponentClarification> manifestDrivenClarifications(
            CampaignAgentSession session, CampaignBusinessResourceCatalog catalog) {
        List<CampaignComponentClarification> result = new ArrayList<>();
        for (CampaignComponentManifest manifest : session.componentManifests()) {
            CampaignComponentCode component = CampaignComponentCode.fromCapability(manifest.capability());
            if (component == null) {
                continue;
            }
            // UNCERTAIN → COMPONENT_DECISION (yes/no for this capability)
            if (manifest.usageDecision() == UsageDecision.UNCERTAIN) {
                result.add(componentDecisionClarification(
                        component, manifest, session.requirementBrief()));
                continue;
            }
            // Only resource-binding capabilities need further checks.
            if (!manifest.capability().requiresResourceBinding()) {
                continue;
            }
            if (manifest.usageDecision() != UsageDecision.REQUIRED) {
                continue;
            }
            // Already bound — no clarification needed.
            if (manifest.resourceBindingComplete()) {
                continue;
            }
            // REQUIRED but no resources bound → check the catalog.
            CampaignBusinessResourceType resourceType = manifest.capability().resourceType();
            if (resourceType == null || catalog == null) {
                continue;
            }
            List<CampaignBusinessResource> selectable = catalog.selectable(resourceType);
            if (selectable.isEmpty()) {
                // No selectable resource exists → RESOURCE_BLOCKED.
                result.add(resourceBlockedClarification(component, manifest));
            } else {
                // One or more selectable resources → RESOURCE_SELECTION.
                result.add(resourceSelectionClarification(component, manifest, selectable));
            }
        }
        return List.copyOf(result);
    }

    /** A COMPONENT_DECISION clarification: yes/no for one capability. */
    private CampaignComponentClarification componentDecisionClarification(
            CampaignComponentCode component,
            CampaignComponentManifest manifest,
            CampaignRequirementBrief brief) {
        List<CampaignAnswerOption> options = List.of(
                new CampaignAnswerOption(UsageDecision.REQUIRED.code(), "Yes"),
                new CampaignAnswerOption(UsageDecision.NOT_REQUIRED.code(), "No"));
        String prompt = localeCopy(
                COMPONENT_DECISION_COPY_EN, COMPONENT_DECISION_COPY_ZH,
                brief.audience() != null ? brief.audience() : "");
        String questionCode = componentDecisionQuestionCode(manifest.capability());
        return new CampaignComponentClarification(
                component,
                componentSkillForManifest(component, manifest),
                CampaignAgentErrorCodes.COMPONENT_DECISION_REQUIRED,
                prompt,
                List.of(questionCode),
                options,
                false);
    }

    /** A RESOURCE_SELECTION clarification: the user picks one resource from the real catalog. */
    private CampaignComponentClarification resourceSelectionClarification(
            CampaignComponentCode component,
            CampaignComponentManifest manifest,
            List<CampaignBusinessResource> selectable) {
        List<CampaignAnswerOption> options = new ArrayList<>();
        for (CampaignBusinessResource resource : selectable) {
            options.add(new CampaignAnswerOption(resource.code(), resource.displayName()));
            if (options.size() == CampaignAgentLimits.MAX_OPTIONS) {
                break;
            }
        }
        String audience = manifest.evidence();
        String prompt = localeCopy(
                RESOURCE_SELECTION_COPY_EN, RESOURCE_SELECTION_COPY_ZH,
                audience != null ? audience : "");
        return new CampaignComponentClarification(
                component,
                componentSkillForManifest(component, manifest),
                CampaignAgentErrorCodes.RESOURCE_BINDING_INCOMPLETE,
                prompt,
                List.of(RESOURCE_SELECTION_QUESTION),
                List.copyOf(options),
                false);
    }

    /** A RESOURCE_BLOCKED clarification: no selectable resource exists for this capability type. */
    private CampaignComponentClarification resourceBlockedClarification(
            CampaignComponentCode component,
            CampaignComponentManifest manifest) {
        String audience = manifest.evidence();
        String prompt = localeCopy(
                RESOURCE_BLOCKED_COPY_EN, RESOURCE_BLOCKED_COPY_ZH,
                audience != null ? audience : "");
        return new CampaignComponentClarification(
                component,
                componentSkillForManifest(component, manifest),
                CampaignAgentErrorCodes.RESOURCE_TYPE_UNAVAILABLE,
                prompt,
                List.of(RESOURCE_BLOCKED_QUESTION),
                List.of(),
                true);
    }

    /**
     * The owning Skill identifier for a manifest-driven clarification of one component.
     *
     * <p>The Skill is the first one the component owns that is relevant to the manifest's
     * capability. For components with only one Skill this is unambiguous; for components
     * with several (like OFFER), we select the primary resource-facing Skill.
     */
    private static String componentSkillForManifest(
            CampaignComponentCode component, CampaignComponentManifest manifest) {
        List<String> skills = component.skillIds();
        if (skills.isEmpty()) {
            return null;
        }
        return switch (manifest.capability()) {
            case EVENT, FLOW -> CampaignSkillIds.JOURNEY_FLOW;
            case MESSAGE -> CampaignSkillIds.MESSAGE_TEMPLATE;
            case PROMOTION -> CampaignSkillIds.PROMOTION_REFERENCE;
            case SEGMENT -> CampaignSkillIds.SEGMENT_RULE_BASED;
            case SCHEDULE -> CampaignSkillIds.SCHEDULE_BASIC;
        };
    }

    /**
     * Processes user answers to manifest-driven component clarifications and updates the
     * session's manifests accordingly.
     *
     * <p>COMPONENT_DECISION answers update the manifest's usage decision to REQUIRED or
     * NOT_REQUIRED with DecisionSource.USER. RESOURCE_SELECTION answers validate the chosen
     * resource code against the catalog and bind it to the manifest. RESOURCE_BLOCKED
     * answers are free-text only and do not mutate the manifest.
     *
     * <p>Answers that do not target a manifest-driven question code are ignored. Resource
     * codes submitted by the client are re-validated against the catalog — the client is
     * never trusted.
     *
     * <p>After processing manifest answers, the brief is updated to reflect manifest-based
     * decisions for backward compatibility: NOT_REQUIRED decisions clear the corresponding
     * brief fields, and resource bindings are dual-written to the brief.
     */
    private void processManifestAnswers(
            CampaignAgentSession session, CampaignAgentTurnRequest request) {
        CampaignBusinessResourceCatalog catalog = environment.businessResourceCatalog();
        List<CampaignClarificationAnswer> answers = request.answers();
        if (answers.isEmpty()) {
            return;
        }
        List<CampaignComponentManifest> manifests = new ArrayList<>(session.componentManifests());
        boolean changed = false;
        for (CampaignClarificationAnswer answer : answers) {
            CampaignComponentManifest manifest = findManifestByQuestionCode(
                    manifests, answer.code(), answer.component());
            if (manifest == null) {
                continue;
            }
            if (answer.code() != null && answer.code().startsWith(COMPONENT_DECISION_QUESTION_PREFIX)
                    && answer.optionId() != null) {
                UsageDecision decision = UsageDecision.fromCode(answer.optionId());
                if (decision == null || decision == UsageDecision.UNCERTAIN) {
                    continue;
                }
                manifests = replaceManifest(manifests,
                        manifest.withDecision(decision, DecisionSource.USER));
                changed = true;
            } else if (RESOURCE_SELECTION_QUESTION.equals(answer.code()) && answer.optionId() != null) {
                if (!manifest.capability().requiresResourceBinding()) {
                    continue;
                }
                CampaignBusinessResourceType resourceType = manifest.capability().resourceType();
                if (resourceType == null || catalog == null) {
                    continue;
                }
                CampaignBusinessResource resource = catalog.selectable(resourceType, answer.optionId());
                if (resource == null) {
                    throw CampaignAgentException.of(
                            CampaignAgentErrorCodes.RESOURCE_CODE_INVALID,
                            "The selected resource code is not a selectable resource in the host catalog.");
                }
                manifests = replaceManifest(manifests,
                        manifest.withResourceCodes(List.of(resource.code())));
                changed = true;
            }
        }
        if (changed) {
            session.setComponentManifests(manifests);
            // Update the brief to reflect manifest decisions for backward compatibility
            // with existing removeInactiveSkills / Todo derivation which read only the brief.
            applyManifestDecisionsToBrief(session, manifests);
        }
    }

    /** Finds the manifest for a given question code by matching the capability in the code. */
    private static CampaignComponentManifest findManifestByQuestionCode(
            List<CampaignComponentManifest> manifests,
            String questionCode,
            CampaignComponentCode answerComponent) {
        if (questionCode == null) {
            return null;
        }
        // COMPONENT_DECISION_<CAPABILITY> — extract capability from the suffix.
        // The question code is capability-specific, so the match is unambiguous even
        // when EVENT and FLOW both map to JOURNEY.
        if (questionCode.startsWith(COMPONENT_DECISION_QUESTION_PREFIX)) {
            String capabilityCode = questionCode.substring(COMPONENT_DECISION_QUESTION_PREFIX.length());
            for (CampaignComponentManifest manifest : manifests) {
                if (manifest.capability().code().equals(capabilityCode)) {
                    return manifest;
                }
            }
            return null;
        }
        // RESOURCE_SELECTION and RESOURCE_BLOCKED are capability-agnostic.
        // Match by component: the answer names the component, and each component has
        // at most one resource-binding capability (EVENT→JOURNEY, MESSAGE→COMMUNICATION,
        // PROMOTION→OFFER), so the component match is unambiguous.
        if (RESOURCE_SELECTION_QUESTION.equals(questionCode)
                || RESOURCE_BLOCKED_QUESTION.equals(questionCode)) {
            for (CampaignComponentManifest manifest : manifests) {
                if (manifest.capability().requiresResourceBinding()
                        && manifest.usageDecision() == UsageDecision.REQUIRED
                        && !manifest.resourceBindingComplete()) {
                    if (answerComponent != null) {
                        CampaignComponentCode manifestComponent =
                                CampaignComponentCode.fromCapability(manifest.capability());
                        if (manifestComponent == answerComponent) {
                            return manifest;
                        }
                    } else {
                        // Legacy answer with no component: match the first qualifying manifest.
                        return manifest;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Updates the requirement brief to reflect manifest decisions for backward compatibility.
     *
     * <p>The brief is the authority for TODO derivation and Skill removal. When a manifest
     * records a NOT_REQUIRED decision, the corresponding brief field is set to NONE so
     * that {@link #removeInactiveSkills} and {@link CampaignComponentTodo#applicable}
     * operate correctly. When a manifest binds a resource, the brief is dual-written so
     * that existing validation paths see the selected resource.
     */
    private void applyManifestDecisionsToBrief(
            CampaignAgentSession session, List<CampaignComponentManifest> manifests) {
        CampaignRequirementBrief brief = session.requirementBrief();
        boolean briefChanged = false;
        for (CampaignComponentManifest manifest : manifests) {
            if (manifest.decisionSource() != DecisionSource.USER
                    && manifest.decisionSource() != DecisionSource.SYSTEM) {
                continue;
            }
            switch (manifest.usageDecision()) {
                case NOT_REQUIRED -> {
                    switch (manifest.capability()) {
                        case MESSAGE -> {
                            if (brief.communicationDecision() != CommunicationDecision.NONE) {
                                brief = new CampaignRequirementBrief(
                                        brief.type(), brief.audience(), brief.offerDecision(),
                                        brief.offerDetail(), CommunicationDecision.NONE,
                                        List.of(), null, brief.branchDecision(), brief.branchDetail(),
                                        brief.cadence(), brief.branchRule(), brief.schedule(),
                                        brief.triggerEventCode(), brief.confirmedObjective());
                                briefChanged = true;
                            }
                        }
                        case PROMOTION -> {
                            if (brief.offerDecision() != OfferDecision.NONE) {
                                brief = new CampaignRequirementBrief(
                                        brief.type(), brief.audience(), OfferDecision.NONE, null,
                                        brief.communicationDecision(), brief.channels(),
                                        brief.message(), brief.branchDecision(),
                                        brief.branchDetail(), brief.cadence(), brief.branchRule(),
                                        brief.schedule(), brief.triggerEventCode(),
                                        brief.confirmedObjective());
                                briefChanged = true;
                            }
                        }
                        case EVENT -> {
                            if (brief.triggerEventCode() != null) {
                                brief = brief.withTriggerEventCode(null);
                                briefChanged = true;
                            }
                        }
                        case SCHEDULE -> {
                            if (brief.cadence() != null) {
                                brief = new CampaignRequirementBrief(
                                        brief.type(), brief.audience(), brief.offerDecision(),
                                        brief.offerDetail(), brief.communicationDecision(),
                                        brief.channels(), brief.message(), brief.branchDecision(),
                                        brief.branchDetail(), null,
                                        brief.branchRule(), CampaignExecutionSchedule.empty(),
                                        brief.triggerEventCode(), brief.confirmedObjective());
                                briefChanged = true;
                            }
                        }
                    }
                }
                case REQUIRED -> {
                    if (!manifest.resourceBindingComplete()) {
                        break;
                    }
                    // Dual-write resource binding to the brief.
                    switch (manifest.capability()) {
                        case EVENT -> {
                            String bound = manifest.selectedResourceCodes().get(0);
                            if (!bound.equals(brief.triggerEventCode())) {
                                brief = brief.withTriggerEventCode(bound);
                                briefChanged = true;
                            }
                        }
                        case MESSAGE -> {
                            String bound = manifest.selectedResourceCodes().get(0);
                            CampaignMessageInputs.Channel channel =
                                    CampaignMessageInputs.Channel.fromCode(bound);
                            if (channel != null && !brief.channels().contains(channel)) {
                                brief = brief.withChannels(List.of(channel));
                                briefChanged = true;
                            }
                        }
                    }
                }
            }
        }
        if (briefChanged) {
            session.setRequirementBrief(brief);
            removeInactiveSkills(session, brief);
        }
    }

    private static CampaignComponentManifest findManifestForComponent(
            List<CampaignComponentManifest> manifests, CampaignComponentCode component) {
        for (CampaignComponentManifest manifest : manifests) {
            if (CampaignComponentCode.fromCapability(manifest.capability()) == component) {
                return manifest;
            }
        }
        return null;
    }

    private static List<CampaignComponentManifest> replaceManifest(
            List<CampaignComponentManifest> manifests, CampaignComponentManifest replacement) {
        List<CampaignComponentManifest> updated = new ArrayList<>(manifests.size());
        for (CampaignComponentManifest existing : manifests) {
            if (existing.capability() == replacement.capability()) {
                updated.add(replacement);
            } else {
                updated.add(existing);
            }
        }
        return List.copyOf(updated);
    }

    /**
     * Synchronizes component manifests from the merged requirement brief, deriving a decision
     * for every CampaignCapability from the brief's accumulated facts.
     *
     * <p>Each capability is evaluated deterministically:
     * <ul>
     *   <li>SEGMENT: NOT_REQUIRED when audience is not applicable; REQUIRED when audience is non-null;
     *       UNCERTAIN otherwise.</li>
     *   <li>EVENT: REQUIRED when type is EVENT or triggerEventCode is non-null; NOT_REQUIRED when
     *       type is non-null and not EVENT; UNCERTAIN otherwise.</li>
     *   <li>MESSAGE: REQUIRED when communication is DEFINED; NOT_REQUIRED when NONE; UNCERTAIN otherwise.</li>
     *   <li>PROMOTION: REQUIRED when offer is DEFINED; NOT_REQUIRED when NONE; UNCERTAIN otherwise.</li>
     *   <li>FLOW: REQUIRED when branch is REQUIRED; NOT_REQUIRED when NONE; UNCERTAIN otherwise.</li>
     *   <li>SCHEDULE: NOT_REQUIRED for EVENT Campaigns; REQUIRED when cadence is non-null; UNCERTAIN otherwise.</li>
     * </ul>
     *
     * <p>New decisions have source MODEL with the stable reason code MODEL_REQUIREMENT_EXTRACTION.
     * An existing USER decision is never overwritten. Resource bindings are derived from the host
     * catalog only: EVENT binds the brief's triggerEventCode if selectable, MESSAGE binds the brief's
     * channels that are selectable, and PROMOTION auto-binds only when REQUIRED and exactly one
     * selectable promotion exists.
     */
    private void synchronizeComponentManifestsFromBrief(
            CampaignAgentSession session,
            CampaignRequirementBrief mergedBrief,
            String modelEvidence,
            CampaignBusinessResourceCatalog catalog) {
        List<CampaignComponentManifest> newManifests = new ArrayList<>();
        for (CampaignCapability capability : CampaignCapability.values()) {
            CampaignComponentManifest existing = session.manifestFor(capability);
            if (existing != null && existing.decisionSource() == DecisionSource.USER) {
                continue;
            }
            UsageDecision decision = usageDecisionFromBrief(capability, mergedBrief);
            List<String> resourceCodes = resourceCodesFromCatalog(
                    capability, mergedBrief, decision, catalog);
            newManifests.add(new CampaignComponentManifest(
                    capability, decision, "MODEL_REQUIREMENT_EXTRACTION",
                    modelEvidence, resourceCodes, DecisionSource.MODEL));
        }
        if (!newManifests.isEmpty()) {
            session.mergeManifests(newManifests);
        }
    }

    /** Derives a usage decision for one capability from the merged brief facts. */
    private static UsageDecision usageDecisionFromBrief(
            CampaignCapability capability, CampaignRequirementBrief brief) {
        return switch (capability) {
            case SEGMENT -> {
                if (!brief.audienceApplicable()) {
                    yield UsageDecision.NOT_REQUIRED;
                }
                if (brief.audience() != null && !brief.audience().isEmpty()) {
                    yield UsageDecision.REQUIRED;
                }
                yield UsageDecision.UNCERTAIN;
            }
            case EVENT -> {
                if (brief.type() == CampaignType.EVENT || brief.triggerEventCode() != null) {
                    yield UsageDecision.REQUIRED;
                }
                if (brief.type() != null) {
                    yield UsageDecision.NOT_REQUIRED;
                }
                yield UsageDecision.UNCERTAIN;
            }
            case MESSAGE -> switch (brief.communicationDecision()) {
                case DEFINED -> UsageDecision.REQUIRED;
                case NONE -> UsageDecision.NOT_REQUIRED;
                default -> UsageDecision.UNCERTAIN;
            };
            case PROMOTION -> switch (brief.offerDecision()) {
                case DEFINED -> UsageDecision.REQUIRED;
                case NONE -> UsageDecision.NOT_REQUIRED;
                default -> UsageDecision.UNCERTAIN;
            };
            case FLOW -> switch (brief.branchDecision()) {
                case REQUIRED -> UsageDecision.REQUIRED;
                case NONE -> UsageDecision.NOT_REQUIRED;
                default -> UsageDecision.UNCERTAIN;
            };
            case SCHEDULE -> {
                if (brief.type() == CampaignType.EVENT) {
                    yield UsageDecision.NOT_REQUIRED;
                }
                if (brief.cadence() != null) {
                    yield UsageDecision.REQUIRED;
                }
                yield UsageDecision.UNCERTAIN;
            }
        };
    }

    /**
     * Derives resource codes from the host catalog for one capability at the given usage decision.
     *
     * <p>Only REQUIRED decisions for resource-binding capabilities produce codes. The codes are
     * validated against the catalog: EVENT binds the brief's triggerEventCode, MESSAGE binds the
     * brief's catalog-selectable channels, and PROMOTION auto-binds only when exactly one selectable
     * promotion exists. The offer detail is never used as a resource code.
     */
    private static List<String> resourceCodesFromCatalog(
            CampaignCapability capability,
            CampaignRequirementBrief brief,
            UsageDecision decision,
            CampaignBusinessResourceCatalog catalog) {
        if (decision != UsageDecision.REQUIRED || !capability.requiresResourceBinding() || catalog == null) {
            return List.of();
        }
        CampaignBusinessResourceType resourceType = capability.resourceType();
        if (resourceType == null) {
            return List.of();
        }
        return switch (capability) {
            case EVENT -> {
                String code = brief.triggerEventCode();
                if (code != null && catalog.selectable(resourceType, code) != null) {
                    yield List.of(code);
                }
                yield List.of();
            }
            case MESSAGE -> {
                List<String> bound = new ArrayList<>();
                for (CampaignMessageInputs.Channel channel : brief.channels()) {
                    if (catalog.selectable(resourceType, channel.name()) != null) {
                        bound.add(channel.name());
                    }
                }
                yield List.copyOf(bound);
            }
            case PROMOTION -> {
                List<CampaignBusinessResource> selectable = catalog.selectable(resourceType);
                if (selectable.size() == 1) {
                    yield List.of(selectable.getFirst().code());
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    /** Returns the bounded evidence text, truncated to the manifest evidence limit. */
    private static String boundedEvidence(String utterance) {
        if (utterance == null || utterance.isEmpty()) {
            return "";
        }
        return utterance.length() <= CampaignAgentLimits.MAX_EVIDENCE_LENGTH
                ? utterance
                : utterance.substring(0, CampaignAgentLimits.MAX_EVIDENCE_LENGTH);
    }

    /**
     * True when every REQUIRED resource-binding manifest has at least one bound selectable resource
     * verified against the host catalog.
     *
     * <p>The catalog is the sole authority: a model-invented or stale resource code that does not
     * exist or is not selectable cannot satisfy this gate. UNCERTAIN manifest decisions block READY
     * because an undecided capability must be resolved before the Campaign can be finalized. An empty
     * manifest list when the requirement extractor is enabled also blocks — the new path must not be
     * bypassed. When the requirement extractor is null, legacy compatibility is preserved.
     */
    private boolean resourceBindingsComplete(CampaignAgentSession session) {
        List<CampaignComponentManifest> manifests = session.componentManifests();
        if (environment.requirementExtractor() != null && manifests.isEmpty()) {
            return false;
        }
        for (CampaignComponentManifest manifest : manifests) {
            if (manifest.usageDecision() == UsageDecision.UNCERTAIN) {
                return false;
            }
        }
        CampaignBusinessResourceCatalog catalog = environment.businessResourceCatalog();
        if (catalog == null) {
            for (CampaignComponentManifest manifest : manifests) {
                if (manifest.usageDecision() == UsageDecision.REQUIRED
                        && manifest.capability().requiresResourceBinding()) {
                    return false;
                }
            }
            return true;
        }
        for (CampaignComponentManifest manifest : manifests) {
            if (manifest.usageDecision() != UsageDecision.REQUIRED) {
                continue;
            }
            if (!manifest.capability().requiresResourceBinding()) {
                continue;
            }
            if (manifest.selectedResourceCodes().isEmpty()) {
                return false;
            }
            CampaignBusinessResourceType resourceType = manifest.capability().resourceType();
            if (resourceType == null) {
                continue;
            }
            for (String code : manifest.selectedResourceCodes()) {
                if (catalog.selectable(resourceType, code) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String validationDocument(
            CampaignAgentSession session, CampaignAgentTurnRequest request, String skillId) {
        CampaignRequirementBrief brief = session.requirementBrief();
        if (CampaignSkillIds.SEGMENT_RULE_BASED.equals(skillId)) {
            return brief.audience() == null ? request.message() : brief.audience();
        }
        StringBuilder document = new StringBuilder();
        document.append("Accepted Campaign requirement facts:\n")
                .append("audience: ").append(value(brief.audience())).append('\n')
                .append("offerDecision: ").append(brief.offerDecision().name()).append('\n')
                .append("offerDetail: ").append(value(brief.offerDetail())).append('\n')
                .append("communicationDecision: ").append(brief.communicationDecision().name()).append('\n')
                .append("channels: ").append(brief.channels()).append('\n')
                .append("message: ").append(value(brief.message())).append('\n')
                .append("branchDecision: ").append(brief.branchDecision().name()).append('\n')
                .append("branchDetail: ").append(value(brief.branchDetail())).append('\n')
                .append("branchRule: ").append(brief.branchRule() == null ? "none" : brief.branchRule())
                .append('\n')
                .append("cadence: ").append(brief.cadence() == null ? "none" : brief.cadence().name())
                .append('\n')
                .append("scheduleTimeZone: ").append(value(brief.schedule().timeZone())).append('\n')
                .append("scheduleRunAt: ").append(value(brief.schedule().runAt())).append('\n')
                .append("scheduleLocalTime: ").append(value(brief.schedule().localTime())).append('\n')
                .append("scheduleStartDate: ").append(value(brief.schedule().startDate())).append('\n')
                .append("scheduleEndDate: ").append(value(brief.schedule().endDate())).append('\n')
                .append("User evidence:\n");
        for (CampaignConversationTurn turn : session.turns()) {
            if (CampaignConversationTurn.USER.equals(turn.role())) {
                appendBounded(document, turn.text());
            }
        }
        appendBounded(document, request.message());
        return document.toString();
    }

    private static String segmentClarificationDocument(
            CampaignAgentSession session, CampaignAgentTurnRequest request) {
        StringBuilder document = new StringBuilder();
        document.append("Accepted audience requirement:\n");
        appendBounded(document, session.requirementBrief().audience());
        document.append("Clarification supplied for that audience requirement:\n");
        appendBounded(document, requirementUtterance(request));
        return document.toString();
    }

    /**
     * The bounded message clarification document for a resumed Message Skill.
     *
     * <p>When the Communication component asked {@code MESSAGE_TEMPLATE_REQUIRED} and the user
     * supplied a free-text answer, this document explicitly presents the answer as the
     * user-supplied message content together with the accepted Communication facts (channel).
     * It is never an ambiguous bare sentence: the Message Skill receives it as an explicit
     * assignment rather than having to infer that the utterance is the requested message body.
     *
     * <p>Returns null when no provenance-bound message answer exists, so the raw utterance
     * path is preserved as a fallback. The document carries no scenario fact, question or
     * catalog value.
     */
    private static String messageClarificationDocument(
            CampaignAgentSession session, CampaignAgentTurnRequest request) {
        String messageText = messageFreeTextAnswer(request.answers());
        if (messageText == null) {
            return null;
        }
        CampaignRequirementBrief brief = session.requirementBrief();
        StringBuilder document = new StringBuilder();
        document.append("Accepted Communication requirement facts:\n");
        document.append("channels: ").append(brief.channels()).append('\n');
        if (brief.message() != null) {
            document.append("existing message: ").append(brief.message()).append('\n');
        }
        document.append("User-supplied message content:\n");
        appendBounded(document, messageText);
        return document.toString();
    }

    /**
     * The free text of the first answer bound to a pending Communication
     * {@code MESSAGE_TEMPLATE_REQUIRED} question, or null when none exists.
     *
     * <p>Binding is by exact component + skill + question-code provenance: only an answer
     * whose code is {@code MESSAGE_TEMPLATE_REQUIRED} and whose component is
     * {@code COMMUNICATION} is accepted. An answer bound to a different component or a
     * different question code is never returned. An answer with no free text, a blank free
     * text, or an option-only answer returns null.
     */
    static String messageFreeTextAnswer(List<CampaignClarificationAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            return null;
        }
        for (CampaignClarificationAnswer answer : answers) {
            if (!"MESSAGE_TEMPLATE_REQUIRED".equals(answer.code())) {
                continue;
            }
            if (answer.component() != null
                    && answer.component() != CampaignComponentCode.COMMUNICATION) {
                continue;
            }
            if (answer.freeText() == null || answer.freeText().isBlank()) {
                continue;
            }
            return answer.freeText();
        }
        return null;
    }

    private static void appendBounded(StringBuilder document, String text) {
        if (text == null || document.length() >= 8192) {
            return;
        }
        int remaining = 8192 - document.length();
        document.append(text, 0, Math.min(text.length(), remaining)).append('\n');
    }

    /**
     * Extracts the specific free text refinement from a Segment clarification answer, using exact
     * pending component/skill provenance rather than a question-code prefix.
     *
     * <p>When the pre-turn component aggregate carries Segment (component {@code SEGMENT}, skillId
     * {@code segment.rule-based}) requests, only answers bound to those exact pending question
     * codes may refine the audience. An answer bound to a Journey or other component's pending
     * question is never accepted, regardless of its code shape. {@code SELECT_WINDOW},
     * {@code MISSING_METADATA_DEFINITION} and other codes that do not start with {@code SEGMENT_}
     * work because the owning component and Skill — not a prefix — decide provenance.
     *
     * <p>When no aggregate exists but the single pending clarification's owning Skill is
     * {@code segment.rule-based}, a narrow legacy fallback accepts an answer whose code matches a
     * pending question code. The legacy path does not parse or split natural language: it returns
     * null for an answer with no code.
     *
     * <p>This method returns the free text of the first provenance-match, never the entire request
     * message. An answer with no free text, blank free text or an option-only answer does not
     * match. A null return means no valid refinement was found, so the accepted audience is never
     * inadvertently replaced.
     */
    static String audienceRefinementText(
            List<CampaignClarificationAnswer> answers,
            CampaignClarificationAggregate preTurnAggregate,
            CampaignClarification pendingClarification) {
        if (answers == null || answers.isEmpty()) {
            return null;
        }
        // Exact provenance: the pending aggregate owns the authoritative set of Segment question
        // codes. A code that belongs to a Journey, Offer or other non-Segment component request
        // is never in this set and therefore can never produce a refinement.
        java.util.Set<String> segmentCodes = segmentPendingQuestionCodes(preTurnAggregate);
        if (!segmentCodes.isEmpty()) {
            for (CampaignClarificationAnswer answer : answers) {
                if (answer.freeText() == null || answer.freeText().isBlank()) {
                    continue;
                }
                if (answer.code() != null && segmentCodes.contains(answer.code())) {
                    return answer.freeText();
                }
            }
            return null;
        }
        // Narrow legacy fallback: only when the single pending clarification's owning skill is
        // Segment. Does not parse or split natural language; an answer with no code is skipped.
        if (pendingClarification != null
                && CampaignSkillIds.SEGMENT_RULE_BASED.equals(pendingClarification.skillId())) {
            for (CampaignClarificationAnswer answer : answers) {
                if (answer.freeText() == null || answer.freeText().isBlank()) {
                    continue;
                }
                if (answer.code() != null
                        && pendingClarification.questionCodes().contains(answer.code())) {
                    return answer.freeText();
                }
            }
        }
        return null;
    }

    /**
     * The set of question codes belonging to pending Segment component requests in an aggregate.
     *
     * <p>A pending request qualifies when its component is {@code SEGMENT} and its skillId is
     * {@code segment.rule-based}. Every other component's codes — Journey, Offer, Communication,
     * Schedule — are excluded regardless of their code shape, so a Journey code like
     * {@code JOURNEY_INPUT_REQUIRED} that happens to contain {@code SEGMENT_} text is never
     * mis-attributed, and a genuine Segment code like {@code SELECT_WINDOW} that does not start
     * with {@code SEGMENT_} is correctly included.
     */
    static java.util.Set<String> segmentPendingQuestionCodes(
            CampaignClarificationAggregate aggregate) {
        if (aggregate == null || aggregate.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (CampaignComponentClarification request : aggregate.requests()) {
            if (request.segment()) {
                codes.addAll(request.questionCodes());
            }
        }
        return codes;
    }

    /**
     * Composes the refined audience text when a component-owned Segment clarification answer materially
     * refines the audience and the component validated that answer.
     *
     * <p>The refined audience uses the specific accepted component answer free text as the primary
     * source: the answer was validated through the genuine Segment resolution chain and is therefore
     * the authoritative statement of the audience requirement. The existing accepted audience from the
     * brief supplements the answer only when the answer is a partial refinement that does not restate
     * the whole audience — never the other way around, because prepending the accepted audience to the
     * answer can pull the entire original user request into the refined fact when the brief's audience
     * was set from a whole-utterance extraction.
     *
     * <p>The result is bounded to the accepted audience length; a null accepted audience uses only
     * the refinement.
     *
     * <p>This method is provenance-aware: it is called only after the Segment component returned Ready,
     * so a rejected or unresolved answer never reaches it. It carries no scenario fact, question or
     * catalog value.
     */
    private static String composeRefinedAudience(String acceptedAudience, String answerText) {
        // The validated answer text is authoritative. Prepend the accepted audience only when the answer
        // is a partial refinement that does not subsume the existing audience fact, and the audience
        // was not set from a whole-utterance extraction. A brief's audience that equals the user message
        // of a prior turn is treated as a whole-utterance capture and is not prepended.
        if (answerText == null || answerText.isEmpty()) {
            return acceptedAudience;
        }
        if (acceptedAudience == null || acceptedAudience.isEmpty()
                || answerText.contains(acceptedAudience)
                || acceptedAudience.contains(answerText)) {
            return answerText.length() <= CampaignRequirementDelta.MAX_AUDIENCE_LENGTH
                    ? answerText
                    : answerText.substring(0, CampaignRequirementDelta.MAX_AUDIENCE_LENGTH);
        }
        StringBuilder refined = new StringBuilder();
        refined.append(acceptedAudience);
        refined.append("; ");
        refined.append(answerText);
        if (refined.length() > CampaignRequirementDelta.MAX_AUDIENCE_LENGTH) {
            return refined.substring(0, CampaignRequirementDelta.MAX_AUDIENCE_LENGTH);
        }
        return refined.toString();
    }

    private static String value(String value) {
        return value == null ? "none" : value;
    }

    private CampaignAgentTurnResult finalizeTurn(
            CampaignAgentSession session, CampaignAgentTurnRequest request, CampaignRoutingDecision decision) {
        if (decision.objectiveEvidence() != null) {
            confirmObjective(session, request, decision.objectiveEvidence());
        }
        long workspaceRevision = session.workspace().revision();
        if (session.proposal() != null && session.proposalWorkspaceRevision() == workspaceRevision) {
            // A repeated finalization of the same revision replays the identical Proposal: no second Proposal
            // is created, no byte differs and no revision advances.
            return new CampaignAgentTurnResult(
                    true, null, project(session, CampaignRouteKind.FINALIZE_CAMPAIGN,
                            CampaignConversationProjection.ACCEPTED));
        }
        CampaignProposalPatch patch = session.workspace().patch();
        CampaignCompileEvidenceRegistry registry = environment.compileEvidenceRegistry();
        if (registry != null) {
            registry.bind(session.conversationId(), session.runId(), patch.proposalHash().value());
        }
        String copy = decision.assistantCopy() == null
                ? outcomeCopy(session, request, "CAMPAIGN_PROPOSAL_READY", finalizedCopy(request.message()))
                : decision.assistantCopy();
        session.setProposal(patch, workspaceRevision);
        session.setPhase(CampaignAgentPhase.PROPOSAL_READY);
        session.setAssistantCopy(copy);
        session.setPendingClarification(null);
        session.setSegmentContinuation(null);
        session.setSegmentFreeTextRecovery(null);
        session.setFollowUpPrompt(null);
        commit(session, request, decision, copy);
        return accepted(session, decision.routeKind());
    }

    // ------------------------------------------------------------------ commit and projection

    private void commit(
            CampaignAgentSession session,
            CampaignAgentTurnRequest request,
            CampaignRoutingDecision decision,
            String copy) {
        String at = request.at();
        session.appendTurn(new CampaignConversationTurn(
                session.nextTurnId(), CampaignConversationTurn.USER, session.turns().size(), request.message(), at));
        session.appendTurn(new CampaignConversationTurn(
                session.nextTurnId(), CampaignConversationTurn.ASSISTANT, session.turns().size(), copy, at));
        session.setLastRouteKind(decision.routeKind());
        session.advance();
    }

    private CampaignAgentTurnResult accepted(CampaignAgentSession session, CampaignRouteKind routeKind) {
        return CampaignAgentTurnResult.accepted(
                project(session, routeKind, CampaignConversationProjection.ACCEPTED));
    }

    private CampaignAgentTurnResult refused(CampaignAgentSession session, String code) {
        return CampaignAgentTurnResult.refused(
                code, project(session, session.lastRouteKind(), code));
    }

    private CampaignConversationProjection project(
            CampaignAgentSession session, String disposition) {
        return project(session, session.lastRouteKind(), disposition);
    }

    private CampaignConversationProjection project(
            CampaignAgentSession session, CampaignRouteKind routeKind, String disposition) {
        var inspection = session.workspace().inspection();
        CampaignProposalPatch proposal = session.proposal();
        CampaignTurnTime trustedTime = environment.trustedTime();
        return new CampaignConversationProjection(
                session.conversationId(), session.runId(), session.revision(), session.phase(), disposition,
                routeKind, session.turns(), inspection.skills(), inspection.components(),
                session.pendingClarification(),
                session.assistantCopy(), session.audience(), inspection.inspectionHash(),
                proposal, proposal == null ? null : proposal.proposalHash(), session.followUpPrompt(),
                session.journeySummary(),
                CampaignRequirementProgress.from(
                        session.requirementBrief(), inspection.skills(),
                        trustedTime == null ? null : trustedTime.instant(),
                        trustedTime == null ? null : trustedTime.zone()),
                session.pendingComponentClarifications().requests(),
                session.componentManifests());
    }

    /**
     * The user-visible copy of one host-derived outcome.
     *
     * <p>The host owns which outcome occurred; the model owns how it is worded, so an outcome sentence is
     * written in the user's own language through the requirement extractor's bounded wording boundary. The
     * generic fallback carries no scenario fact, question or catalog value, and is used only when that boundary
     * is unwired or unavailable, so a wording outage degrades the copy instead of failing an accepted turn.
     */
    private String outcomeCopy(
            CampaignAgentSession session,
            CampaignAgentTurnRequest request,
            String outcomeCode,
            String fallback) {
        CampaignRequirementExtractorPort extractor = environment.requirementExtractor();
        if (extractor == null) {
            return fallback;
        }
        return extractor.statusMessage(request.message(), session.requirementBrief(), outcomeCode)
                .orElse(fallback);
    }

    /** The copy of one host-selected phase: worded by the boundary, or the generic sentence when it is absent. */
    private String phaseCopy(
            CampaignAgentSession session,
            CampaignAgentTurnRequest request,
            CampaignAgentPhase phase,
            List<CampaignSkillOutcome> outcomes) {
        if (phase == CampaignAgentPhase.TERMINAL_INVALID) {
            // Terminal-invalid copy is always host-owned and deterministic; the model wording boundary is
            // deliberately not consulted for this outcome because it can fabricate ungrounded reasons
            // (e.g. claiming a channel is incompatible with an event when nothing in the component
            // outcome, resource catalog or database says so).
            return terminalCopy(outcomes, request.message());
        }
        return outcomeCopy(session, request, outcomeCode(phase, outcomes),
                hostCopy(phase, outcomes, request.message()));
    }

    /**
     * The stable host outcome code the wording boundary words.
     *
     * <p>It names an outcome and nothing else: it carries no question, no scenario fact and no catalog value.
     */
    private static String outcomeCode(CampaignAgentPhase phase, List<CampaignSkillOutcome> outcomes) {
        if (phase == CampaignAgentPhase.TERMINAL_INVALID) {
            return switch (terminalDisposition(outcomes)) {
                case DENIED -> "CAMPAIGN_TERMINAL_DENIED";
                case UNSUPPORTED -> "CAMPAIGN_TERMINAL_UNSUPPORTED";
                default -> "CAMPAIGN_TERMINAL_INVALID";
            };
        }
        return switch (phase) {
            case AUDIENCE_READY -> "CAMPAIGN_AUDIENCE_READY";
            case AWAITING_GOAL -> "CAMPAIGN_GOAL_AWAITING";
            case BUILDING -> "CAMPAIGN_REQUIREMENTS_INCOMPLETE";
            default -> "CAMPAIGN_REQUIREMENTS_COMPLETE";
        };
    }

    private String hostCopy(CampaignAgentPhase phase, List<CampaignSkillOutcome> outcomes,
            String userMessage) {
        if (phase == CampaignAgentPhase.AUDIENCE_READY) {
            return audienceFollowUp(userMessage);
        }
        if (phase == CampaignAgentPhase.TERMINAL_INVALID) {
            return terminalCopy(outcomes, userMessage);
        }
        if (phase == CampaignAgentPhase.AWAITING_GOAL) {
            return localeCopy(
                    "I could not turn that into a requirement yet. Describe the goal again.",
                    "暂时无法将该描述转为要求。请重新描述您的目标。", userMessage);
        }
        if (phase == CampaignAgentPhase.BUILDING) {
            return blockedCopy(userMessage);
        }
        return buildCopy(userMessage);
    }

    private String terminalCopy(List<CampaignSkillOutcome> outcomes, String userMessage) {
        if (terminalOutcome(outcomes).isEmpty()) {
            return localeCopy(BUILD_COPY_EN, BUILD_COPY_ZH, userMessage);
        }
        return switch (terminalDisposition(outcomes)) {
            case DENIED -> "That requirement is not permitted for your access.";
            case UNSUPPORTED -> "That requirement is not supported by this builder.";
            default -> localeCopy(SYSTEM_FAILURE_COPY_EN, SYSTEM_FAILURE_COPY_ZH, userMessage);
        };
    }

    private static Optional<CampaignSkillOutcome.Terminal> terminalOutcome(
            List<CampaignSkillOutcome> outcomes) {
        return outcomes.stream()
                .filter(CampaignSkillOutcome.Terminal.class::isInstance)
                .map(CampaignSkillOutcome.Terminal.class::cast)
                .findFirst();
    }

    private static Disposition terminalDisposition(List<CampaignSkillOutcome> outcomes) {
        return terminalOutcome(outcomes).map(CampaignSkillOutcome.Terminal::disposition)
                .orElse(Disposition.INVALID);
    }

    /**
     * True when the terminal outcome carries an internal model-output failure code.
     *
     * <p>The extractor must not word these codes: there is no usable model output, so wording
     * would hallucinate unrelated business requirements or blame the user for a host failure.
     */
    private static boolean isModelOutputFailure(List<CampaignSkillOutcome> outcomes) {
        return terminalOutcome(outcomes)
                .map(CampaignSkillOutcome.Terminal::code)
                .filter(code -> CampaignAgentErrorCodes.MODEL_UNAVAILABLE.equals(code)
                        || CampaignAgentErrorCodes.MODEL_OUTPUT_REJECTED.equals(code)
                        || CampaignAgentErrorCodes.MODEL_OUTPUT_OVERSIZED.equals(code)
                        || SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED.equals(code))
                .isPresent();
    }
}
