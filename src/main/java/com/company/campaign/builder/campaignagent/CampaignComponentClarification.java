package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;

import java.util.ArrayList;
import java.util.List;

/**
 * One component-scoped typed clarification request a component agent returned.
 *
 * <p>It is the host-owned aggregate element: it keeps the exact {@link CampaignComponentCode component} and the
 * exact owning Skill that asked, so a later projection and a later resume never have to guess which component a
 * question belongs to. The Skill identifier is always one the component owns, and the component/skill pair is
 * therefore a host fact rather than a model assertion.
 *
 * <p>It carries a bounded host-owned fallback prompt, the stable question codes and the bounded safe answer
 * options. It never carries a Tool argument, a capability payload, a template body, a member identifier, a saved
 * state fact or an exception message, so a question can never become a channel for restricted data. It is
 * immutable and travels with the session exactly as the pending clarification does.
 */
public record CampaignComponentClarification(
        CampaignComponentCode component,
        String skillId,
        String code,
        String prompt,
        List<String> questionCodes,
        List<CampaignAnswerOption> options,
        boolean freeTextAllowed
) {

    public CampaignComponentClarification {
        if (component == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.COMPONENT_SKILL_NOT_OWNED, "componentClarification",
                    "A component clarification requires its originating component.");
        }
        if (skillId == null || !component.skillIds().contains(skillId)) {
            // The originating Skill must be one the component owns: a question can never be attributed to a
            // component that does not own the Skill that asked it.
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.COMPONENT_SKILL_NOT_OWNED, "componentClarificationSkill",
                    "A component clarification may only name a Skill its own component owns.");
        }
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "componentClarificationCode",
                    "A component clarification requires one bounded machine code.");
        }
        prompt = prompt == null ? "Please clarify your Campaign requirement." : prompt;
        if (prompt.isEmpty() || prompt.length() > CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "componentClarificationPrompt",
                    "A component clarification prompt must be bounded.");
        }
        CampaignRoutingVocabulary.requireSafeCopy(prompt, "componentClarificationPrompt");
        questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
        if (questionCodes.isEmpty()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "componentClarificationQuestions",
                    "A component clarification must name at least one stable question code.");
        }
        for (String questionCode : questionCodes) {
            if (questionCode == null || !questionCode.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "componentClarificationQuestions",
                        "A component clarification question code must be a stable machine code.");
            }
        }
        options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
        if (options.size() > CampaignAgentLimits.MAX_OPTIONS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "componentClarificationOptions",
                    "A component clarification exceeds its option bound.");
        }
        // A question the boundary exposes with no bounded options is one arbitrary missing fact, so it must stay
        // answerable by free text; this mirrors the projected clarification contract exactly.
        if (options.isEmpty()) {
            freeTextAllowed = true;
        }
    }

    /**
     * The component-scoped request of one genuine {@code NeedsInput} outcome.
     *
     * <p>It is the one handoff from a Skill outcome to the aggregate: the component and the Skill are the host's
     * own facts, the question codes and options are the Skill's own, and the prompt is the bounded host fallback
     * for that question. The wording boundary may rephrase the projected copy later, but it can never change a
     * question code or an option here.
     */
    public static CampaignComponentClarification from(
            CampaignComponentCode component, CampaignSkillOutcome.NeedsInput needsInput) {
        return new CampaignComponentClarification(
                component, needsInput.skillId(), needsInput.code(), promptFor(needsInput),
                needsInput.questionCodes(), needsInput.options(), needsInput.freeTextAllowed());
    }

    /** The bounded host fallback prompt of one clarification question, selected by its own question code.
     *
     * <p>Every component-aware fallback is a bounded, locale-independent business-domain prompt; the
     * specific missing-fact semantics are carried by the structured question codes already passed to
     * the model wording boundary, never by raw-code annotation in user copy. When the wording boundary
     * is unavailable, the user sees the component-aware prompt rather than a generic placeholder. */
    static String promptFor(CampaignSkillOutcome.NeedsInput needsInput) {
        if (needsInput.questionCodes().contains("SEGMENT_AUDIENCE_REPHRASE_REQUIRED")) {
            return CampaignConversationAgent.AUDIENCE_REPHRASE_COPY;
        }
        if (needsInput.questionCodes().contains("CHANNEL_UNSUPPORTED")) {
            return CampaignConversationAgent.CHANNEL_UNSUPPORTED_COPY;
        }
        if (needsInput.questionCodes().contains("MISSING_METADATA_DEFINITION")) {
            return CampaignConversationAgent.MISSING_METADATA_DEFINITION_COPY;
        }
        if (CampaignAgentErrorCodes.GOVERNANCE_INPUT_REQUIRED.equals(needsInput.code())) {
            return CampaignConversationAgent.GOVERNANCE_LIMIT_COPY;
        }
        // Question-code-specific Segment fallbacks: each describes the component context
        // and the missing decision, never raw machine codes, SQL identifiers, tool details,
        // exception text or test-scenario facts.
        String segmentPrompt = segmentQuestionCodeFallback(needsInput);
        if (segmentPrompt != null) {
            return segmentPrompt;
        }
        // Every other question code gets a component-aware host fallback; the wording boundary
        // may later rephrase it using the structured question codes. No raw machine code ever
        // reaches user copy through this path.
        return componentFallback(needsInput.skillId());
    }

    /**
     * The question-code-specific Segment fallback for the first question code of one NeedsInput,
     * or null when no Segment-specific wording applies.
     *
     * <p>Each prompt states the component context (audience/Segment) and the missing decision,
     * without exposing raw machine codes, SQL identifiers, tool details, exception text or
     * test-scenario facts. The question code provides the semantic anchor; the wording is
     * deterministic host-owned text.
     */
    private static String segmentQuestionCodeFallback(CampaignSkillOutcome.NeedsInput needsInput) {
        if (needsInput.questionCodes().isEmpty()) {
            return null;
        }
        String firstCode = needsInput.questionCodes().get(0);
        return switch (firstCode) {
            case "SELECT_WINDOW" ->
                    "The audience time window is not yet selected. Specify the period in days, "
                            + "weeks or months so the rule's time range can be set.";
            case "SELECT_DEFINITION" ->
                    "The audience definition is not yet selected. Choose which condition or "
                            + "metric definition to apply so a specific rule can be built.";
            case "DISAMBIGUATE_DEFINITION" ->
                    "Several definitions match the audience condition. Select the one definition "
                            + "that should apply so the rule can be built without ambiguity.";
            case "MISSING_CURRENCY" ->
                    "A currency is required for this monetary audience condition. Specify the "
                            + "currency code so amounts can be compared correctly.";
            case "AMBIGUOUS_GROUPING" ->
                    "The grouping of this audience condition is ambiguous. Clarify how records "
                            + "should be grouped — for example, by member, by order or by event.";
            case "AMBIGUOUS_METRIC_MEANING" ->
                    "The meaning of this audience metric is ambiguous. Specify which calculation "
                            + "the metric should use so the rule's intent is clear.";
            case "AMBIGUOUS_NULL_INCLUSION" ->
                    "It is unclear whether missing values should be included or excluded from "
                            + "this audience condition. Specify the inclusion rule.";
            case "AMBIGUOUS_RELATION_SCOPE" ->
                    "The scope of this audience relation is ambiguous. Specify how the relation "
                            + "should be bounded so the rule targets the right records.";
            case "AMBIGUOUS_FILTER_SCOPE" ->
                    "The scope of this audience filter is ambiguous. Specify where the filter "
                            + "should be applied so the rule covers the intended records.";
            case "UNSUPPORTED_REQUIREMENT" ->
                    "This audience requirement cannot be turned into a rule with the available "
                            + "metadata. Restate the condition using supported concepts.";
            case "SELECT_AUDIENCE_REFERENCE" ->
                    "Several saved audiences match the request. Select the one audience "
                            + "reference that should be used so it can be resolved precisely.";
            case "SELECT_AUDIENCE_VERSION" ->
                    "The selected audience has several published versions. Choose one "
                            + "immutable version so the rule is pinned to a specific release.";
            case "AUDIENCE_REFERENCE_UNAVAILABLE" ->
                    "No saved audience could be found for this request. Supply the audience "
                            + "conditions directly so a new rule can be built.";
            default -> null;
        };
    }

    /**
     * The question-code-specific Segment fallback in Chinese for the first question code,
     * or null when no Chinese Segment-specific wording applies.
     *
     * <p>Each prompt states the component context and the missing decision, without
     * exposing raw machine codes. These are component-owned deterministic Chinese
     * wordings used only when the model wording boundary is unavailable and the
     * current user text is Chinese. They preserve the exact codes and options —
     * only the prompt is replaced.
     */
    private static String segmentQuestionCodeFallbackZh(String firstCode) {
        return switch (firstCode) {
            case "SELECT_WINDOW" ->
                    "受众时间窗口尚未选定。请指定天数、周数或月数的期限，以便设定规则的时间范围。";
            case "SELECT_DEFINITION" ->
                    "受众定义尚未选定。请选择要应用的条件或指标定义，以便构建具体规则。";
            case "DISAMBIGUATE_DEFINITION" ->
                    "多个定义与受众条件匹配。请选择应适用的那个定义，以便明确构建规则。";
            case "MISSING_CURRENCY" ->
                    "此货币型受众条件需要指定货币。请提供货币代码，以便正确比较金额。";
            case "AMBIGUOUS_GROUPING" ->
                    "此受众条件的分组方式不明确。请说明记录应如何分组——例如按成员、按订单或按事件。";
            case "AMBIGUOUS_METRIC_MEANING" ->
                    "此受众指标的含义不明确。请指定指标应使用哪种计算方式，以便明确规则的意图。";
            case "AMBIGUOUS_NULL_INCLUSION" ->
                    "对于此受众条件，缺失值应包含还是排除尚不明确。请指定包含规则。";
            case "AMBIGUOUS_RELATION_SCOPE" ->
                    "此受众关系的范围不明确。请指定关系应如何限定，以便规则定位正确的记录。";
            case "AMBIGUOUS_FILTER_SCOPE" ->
                    "此受众过滤器的范围不明确。请指定过滤器应应用于何处，"
                            + "以便规则覆盖预期的记录。";
            case "UNSUPPORTED_REQUIREMENT" ->
                    "此受众要求无法转化为可用元数据支持的规则。请使用支持的概念重新表述条件。";
            case "SELECT_AUDIENCE_REFERENCE" ->
                    "多个已保存的受众符合请求。请选择应使用的那个受众引用，以便精确定位。";
            case "SELECT_AUDIENCE_VERSION" ->
                    "所选受众有多个已发布版本。请选择一个不可变版本，"
                            + "以便规则固定到特定发布。";
            case "AUDIENCE_REFERENCE_UNAVAILABLE" ->
                    "未找到符合此请求的已保存受众。请直接提供受众条件，以便构建新规则。";
            default -> null;
        };
    }

    /**
     * Returns the component-owned Chinese deterministic fallback wording for this
     * clarification, or null when no Chinese fallback is registered for its first
     * question code or the component is not Segment.
     *
     * <p>This is called only from the wording boundary when the model is unavailable
     * and the user text is Chinese. It preserves the exact codes and options — only
     * the prompt is replaced. No Main Agent rewriting is involved.
     */
    public static String chineseComponentFallback(CampaignComponentClarification clarification) {
        if (clarification == null || clarification.questionCodes().isEmpty()) {
            return null;
        }
        String firstCode = clarification.questionCodes().get(0);
        if (clarification.segment()) {
            return segmentQuestionCodeFallbackZh(firstCode);
        }
        return null;
    }

    /**
     * Validates a model-reworded component-clarification prompt and returns the clarification with
     * the accepted wording, or the original fallback when validation fails.
     *
     * <p>Validation is deterministic and bounded: safe-copy check, length bound, and semantic
     * retention. A rejected wording silently falls back to the deterministic component prompt.
     * No model, cross-component call, or exception text is involved in the decision.
     *
     * <p>This method is public because it is a component-boundary validation: the wording boundary
     * in {@code StructuredCampaignRequirementExtractor} (a separate package) must delegate
     * validation to the component that owns the clarification. The component is the sole authority
     * on its own question codes, semantic keywords and cross-component rules, so the validation
     * cannot be replicated or simulated by the wording boundary.
     *
     * @param original the component clarification with the deterministic fallback prompt
     * @param worded   the model-produced wording to validate
     * @return the clarification with the accepted wording, or the original when rejected
     */
    public static CampaignComponentClarification validateAndAcceptWording(
            CampaignComponentClarification original, String worded) {
        if (worded == null || worded.isBlank()) {
            return original;
        }
        // Bounded length: must not exceed the assistant copy limit.
        if (worded.length() > CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH) {
            return original;
        }
        // Safe-copy: must not carry control characters, tool identifiers, physical identifiers
        // or host-only action tokens.
        try {
            CampaignRoutingVocabulary.requireSafeCopy(worded, "componentClarificationWording");
        } catch (CampaignAgentException rejected) {
            return original;
        }
        // Cross-component guard: the wording must not name another component's question code
        // or component identity. A Segment wording that references Journey, Offer, Communication,
        // Schedule or Governance question codes is rejected.
        if (containsCrossComponentContent(worded, original)) {
            return original;
        }
        // Semantic retention: the wording must retain the key domain concept of the
        // component's question. A generic rephrase that drops the specific missing-decision
        // semantics is rejected.
        if (!retainsQuestionSemantics(worded, original)) {
            return original;
        }
        // Options and codes are never mutated by wording — only the prompt changes.
        return original.withPrompt(worded);
    }

    /**
     * True when a model wording names a question code, component identity or concept that
     * belongs to a different component.
     *
     * <p>The wording must address only its own component's missing decision; it must not
     * reference Journey, Offer, Communication, Schedule or Governance concepts.
     */
    private static boolean containsCrossComponentContent(
            String worded, CampaignComponentClarification original) {
        String lower = worded.toLowerCase(java.util.Locale.ROOT);
        // Journey concepts must not appear in a non-Journey component's wording.
        if (!original.skillId().equals("journey.flow")
                && (lower.contains("journey") || lower.contains("branch")
                    || lower.contains("flow") || lower.contains("path"))) {
            // "branch" and "flow" as standalone business terms are too broad; accept them only
            // when paired with Journey-specific context.
            if (lower.contains("journey branch") || lower.contains("journey flow")
                    || lower.contains("branching flow")) {
                return true;
            }
        }
        // Offer/Promotion concepts must not appear in a non-Offer component's wording.
        if (!original.skillId().equals("promotion.reference")
                && (lower.contains("promotion") || lower.contains("reward")
                    || lower.contains("offer") || lower.contains("discount"))) {
            return true;
        }
        // Communication/Message concepts must not appear in a non-Communication component's wording.
        if (!original.skillId().equals("message.template")
                && (lower.contains("channel") || lower.contains("template")
                    || lower.contains("delivery"))) {
            return true;
        }
        // Schedule concepts must not appear in a non-Schedule component's wording.
        if (!original.skillId().equals("schedule.basic")
                && (lower.contains("schedule") || lower.contains("cadence")
                    || lower.contains("timezone") || lower.contains("run at"))) {
            return true;
        }
        // Governance concepts must not appear in a non-Governance component's wording.
        if (!original.skillId().equals("governance.limit")
                && (lower.contains("governance") || lower.contains("limit")
                    || lower.contains("budget") || lower.contains("ceiling"))) {
            // "limit" alone is too broad — only reject when paired with governance context.
            if (lower.contains("governance limit") || lower.contains("budget limit")
                    || lower.contains("reward limit") || lower.contains("ceiling limit")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the model-wording retains the specific missing-decision semantics of the
     * component's question codes.
     *
     * <p>Each known question code maps to a closed set of generic business-domain keywords.
     * The wording must contain at least one matching keyword for each of the component's
     * question codes. A wording that drops the question's semantic anchor — e.g. rewording
     * a window selection as a generic "supply missing detail" — is rejected. Keywords are
     * generic domain terms, never scenario facts, catalog values or test-corpus text.
     *
     * <p>When a question code has no registered keyword set, semantic retention is
     * not required for that code and the check passes.
     */
    static boolean retainsQuestionSemantics(String worded,
                                           CampaignComponentClarification original) {
        String lower = worded.toLowerCase(java.util.Locale.ROOT);
        for (String code : original.questionCodes()) {
            java.util.Set<String> keywords = semanticKeywords(code);
            if (keywords == null || keywords.isEmpty()) {
                continue;
            }
            boolean matched = false;
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    /**
     * The closed set of generic business-domain keywords for one question code.
     *
     * <p>Keywords describe the domain concept, never a scenario fact, catalog value or
     * test-corpus text. A code with no registered keywords returns null (semantic check
     * is skipped for that code).
     */
    private static java.util.Set<String> semanticKeywords(String questionCode) {
        return switch (questionCode) {
            case "SELECT_WINDOW" ->
                    java.util.Set.of("time", "period", "window", "range", "duration", "interval",
                            "days", "weeks", "months", "时间", "期限", "窗口", "范围", "天数", "周", "月");
            case "SELECT_DEFINITION", "DISAMBIGUATE_DEFINITION" ->
                    java.util.Set.of("definition", "criteria", "condition", "rule", "metric",
                            "select", "choose", "which", "定义", "条件", "规则", "指标", "选择");
            case "MISSING_METADATA_DEFINITION" ->
                    java.util.Set.of("metadata", "definition", "configuration", "registered",
                            "administrator");
            case "MISSING_CURRENCY" ->
                    java.util.Set.of("currency", "money", "amount", "monetary", "货币", "金额");
            case "AMBIGUOUS_GROUPING" ->
                    java.util.Set.of("group", "aggregate", "count", "sum", "member",
                            "order", "event", "分组", "聚合", "成员", "订单", "事件");
            case "AMBIGUOUS_METRIC_MEANING" ->
                    java.util.Set.of("metric", "measure", "calculate", "calculation", "指标", "计算");
            case "AMBIGUOUS_NULL_INCLUSION" ->
                    java.util.Set.of("null", "missing", "empty", "include", "exclude", "缺失", "包含", "排除");
            case "AMBIGUOUS_RELATION_SCOPE" ->
                    java.util.Set.of("relation", "scope", "related", "bounded", "关系", "范围", "限定");
            case "AMBIGUOUS_FILTER_SCOPE" ->
                    java.util.Set.of("filter", "scope", "applied", "condition", "过滤器", "范围", "条件");
            case "UNSUPPORTED_REQUIREMENT" ->
                    java.util.Set.of("supported", "cannot", "unavailable", "restate", "支持", "不可用");
            case "SELECT_AUDIENCE_REFERENCE" ->
                    java.util.Set.of("audience", "reference", "select", "saved", "受众", "引用", "选择");
            case "SELECT_AUDIENCE_VERSION" ->
                    java.util.Set.of("version", "audience", "select", "published",
                            "release", "版本", "受众", "选择", "发布");
            case "AUDIENCE_REFERENCE_UNAVAILABLE" ->
                    java.util.Set.of("audience", "unavailable", "found", "directly", "受众", "不可用", "找到");
            default -> null;
        };
    }

    /** The component-aware base fallback for one Skill, without question-code context.
     *
     * <p>Every Skill the closed component vocabulary owns is matched here, so a genuinely unknown
     * Skill identifier means the host cannot phrase a specific question for it. The fallback states
     * that inability rather than fabricating a plausible prompt. */
    private static String componentFallback(String skillId) {
        if (CampaignSkillIds.SEGMENT_RULE_BASED.equals(skillId)) {
            return CampaignConversationAgent.AUDIENCE_CLARIFY_COPY;
        }
        if (CampaignSkillIds.MESSAGE_TEMPLATE.equals(skillId)) {
            return CampaignConversationAgent.MESSAGE_CLARIFY_COPY;
        }
        if (CampaignSkillIds.PROMOTION_REFERENCE.equals(skillId)) {
            return CampaignConversationAgent.PROMOTION_CLARIFY_COPY;
        }
        if (CampaignSkillIds.SCHEDULE_BASIC.equals(skillId)) {
            return CampaignConversationAgent.SCHEDULE_CLARIFY_COPY;
        }
        if (CampaignSkillIds.JOURNEY_FLOW.equals(skillId)) {
            return CampaignConversationAgent.JOURNEY_CLARIFY_COPY;
        }
        if (CampaignSkillIds.GOVERNANCE_LIMIT.equals(skillId)) {
            return CampaignConversationAgent.GOVERNANCE_LIMIT_COPY;
        }
        // A genuinely unknown Skill carries no host-owned phrase. Every Skill the closed
        // component vocabulary owns is already matched above, so this path is defensive
        // only; the generic fallback is the honest inability to phrase a specific question
        // for an unresolvable Skill identifier.
        return CampaignConversationAgent.CLARIFY_COPY;
    }

    /**
     * Returns a copy of this clarification with the given prompt, preserving all other fields.
     *
     * <p>This is used to maintain the single-issue copy-parity invariant: when exactly one
     * component issue is projected, the component issue's prompt must equal the projected
     * {@link CampaignClarification#prompt()} exactly. Component prompts are otherwise final
     * and are never rewritten after {@link #from(CampaignComponentCode, CampaignSkillOutcome.NeedsInput)}.
     */
    public CampaignComponentClarification withPrompt(String newPrompt) {
        return new CampaignComponentClarification(
                component, skillId, code, newPrompt, questionCodes, options, freeTextAllowed);
    }

    /** True when this Skill identifier is the accepted rule-based Segment Skill. */
    public boolean segment() {
        return CampaignSkillIds.SEGMENT_RULE_BASED.equals(skillId);
    }

    /** A bounded diagnostic: the component, the skill and the counts, never the prompt or a label. */
    @Override
    public String toString() {
        return "CampaignComponentClarification[component=" + component
                + ", skillId=" + skillId
                + ", code=" + code
                + ", questions=" + questionCodes.size()
                + ", options=" + options.size() + ']';
    }
}