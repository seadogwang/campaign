package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic question-code-to-answer planner for the collaboration probe.
 *
 * <p>Given the host-owned {@link CampaignClarification#questionCodes()} from the current turn,
 * produces a bounded natural-language answer and optional typed {@link CampaignClarificationAnswer}
 * records. The planner branches on the exact stable code, never on prompt text and never on a
 * fixed sequence. Unknown codes cause an honest safe stop.
 *
 * <p>When the clarification carries multiple question codes, the planner answers all recognized
 * host requirement codes in one composed natural reply.
 *
 * <p>This class is package-private — it lives under {@code src/test} and is never visible to
 * production code.
 */
final class QuestionCodeAnswerPlanner {

    private QuestionCodeAnswerPlanner() {
    }

    /**
     * The bounded result of planning one answer turn.
     */
    record PlannedAnswer(
            /** The composed natural-language utterance for the turn. */
            String utterance,
            /** Typed clarification answers (e.g. for option-based channel selection). */
            List<CampaignClarificationAnswer> clAnswers,
            /** The question codes this answer explicitly addresses, in the order answered. */
            List<String> answeredQuestionCodes,
            /** Recognized host requirement codes that the profile lacks facts for; requires human stop. */
            List<String> missingFactCodes,
            /** Codes not recognized as a {@link CampaignRequirementCode}; component-owned or unknown. */
            List<String> unknownCodes,
            /** Whether the planner must stop rather than advance. */
            boolean shouldStop,
            /** Whether the exact same question-code set was seen in a previous turn. */
            boolean isRepeat) {

        public PlannedAnswer {
            utterance = utterance == null ? "" : utterance;
            clAnswers = clAnswers == null ? List.of() : List.copyOf(clAnswers);
            answeredQuestionCodes = answeredQuestionCodes == null
                    ? List.of() : List.copyOf(answeredQuestionCodes);
            missingFactCodes = missingFactCodes == null
                    ? List.of() : List.copyOf(missingFactCodes);
            unknownCodes = unknownCodes == null
                    ? List.of() : List.copyOf(unknownCodes);
        }

        /** A bounded diagnostic: the answer source, never the utterance text. */
        @Override
        public String toString() {
            return "PlannedAnswer[answeredCodes=" + answeredQuestionCodes
                    + ", missingFacts=" + missingFactCodes
                    + ", unknown=" + unknownCodes
                    + ", stop=" + shouldStop
                    + ", repeat=" + isRepeat + ']';
        }
    }

    /**
     * Plans the next turn's answer from the host clarification's question codes,
     * the scenario completion profile, and the set of previously-seen question-code sets.
     *
     * @param questionCodes   the current turn's clarification question codes, in order
     * @param options         the current turn's answer options, or empty
     * @param profile         the scenario completion profile
     * @param previousCodeSets previously-seen sorted-code-set keys (for repeat detection)
     * @return the planned answer
     */
    static PlannedAnswer plan(
            List<String> questionCodes,
            List<CampaignAnswerOption> options,
            CompletionProfile profile,
            Set<String> previousCodeSets) {

        if (questionCodes == null || questionCodes.isEmpty()) {
            return new PlannedAnswer("", List.of(), List.of(), List.of(), List.of(), true, false);
        }

        // Detect repeats: sort codes to a canonical key.
        String codeSetKey = sortedCodeSetKey(questionCodes);
        boolean isRepeat = previousCodeSets != null && previousCodeSets.contains(codeSetKey);

        List<String> unknownCodes = new ArrayList<>();
        List<String> missingFactCodes = new ArrayList<>();
        List<String> answeredCodes = new ArrayList<>();
        List<String> answerParts = new ArrayList<>();
        List<CampaignClarificationAnswer> clAnswers = new ArrayList<>();

        // For CHANNEL_REQUIRED: if the clarification has options, prefer the typed answer.
        CampaignAnswerOption channelOption = null;
        if (options != null && !options.isEmpty()) {
            for (CampaignAnswerOption opt : options) {
                String label = opt.label();
                if (label != null && (label.contains("SMS") || label.contains("短信")
                        || label.toUpperCase().contains("SMS"))) {
                    channelOption = opt;
                    break;
                }
            }
            // If no SMS option found, take the first option as the typed channel answer.
            if (channelOption == null && options.size() == 1) {
                channelOption = options.get(0);
            }
        }

        int clAnswersBefore = clAnswers.size();
        for (String code : questionCodes) {
            CampaignRequirementCode reqCode;
            try {
                reqCode = CampaignRequirementCode.valueOf(code);
            } catch (IllegalArgumentException e) {
                unknownCodes.add(code);
                continue;
            }

            String part = resolveCode(reqCode, profile, channelOption, clAnswers);
            if (part == null) {
                // The planner signals a missing fact — it cannot answer this code.
                missingFactCodes.add(code);
            } else {
                // Code is recognized and answered (via natural text, typed answer, or system-handled).
                answeredCodes.add(code);
                if (!part.isEmpty()) {
                    answerParts.add(part);
                }
            }
        }
        // Count new typed answers added during this resolution.
        boolean hasTypedAnswers = clAnswers.size() > clAnswersBefore;

        // Compose the utterance.
        String utterance;
        if (!unknownCodes.isEmpty() || !missingFactCodes.isEmpty()) {
            // Stop: unknown or missing-fact codes present.
            utterance = "";
        } else if (answerParts.isEmpty()) {
            // No natural-language parts — the answer is via typed answers or system-handled.
            utterance = "";
        } else {
            utterance = String.join("，", answerParts) + "。";
        }

        boolean noAnswerAtAll = answerParts.isEmpty() && !hasTypedAnswers;
        boolean shouldStop = !unknownCodes.isEmpty()
                || !missingFactCodes.isEmpty()
                || noAnswerAtAll
                || isRepeat;

        return new PlannedAnswer(
                utterance,
                clAnswers,
                answeredCodes,
                missingFactCodes,
                unknownCodes,
                shouldStop,
                isRepeat);
    }

    /**
     * Resolves a single {@link CampaignRequirementCode} to its answer part.
     *
     * @return the natural-language answer fragment, empty string for codes handled
     *         by the system, or null for codes the profile cannot answer
     */
    private static String resolveCode(
            CampaignRequirementCode code,
            CompletionProfile profile,
            CampaignAnswerOption channelOption,
            List<CampaignClarificationAnswer> clAnswers) {

        return switch (code) {
            case CAMPAIGN_TYPE_REQUIRED ->
                    "发起一个单次营销活动";

            case CAMPAIGN_TYPE_UNSUPPORTED ->
                // The system will route to OUT_OF_SCOPE; no answer from the driver.
                    "";

            case EVENT_REQUIRED -> {
                if (profile.registeredEvent() != null) {
                    yield "触发事件为：" + profile.registeredEvent();
                }
                // No registered event: the profile cannot answer this honestly.
                yield null;
            }

            case AUDIENCE_REQUIRED -> {
                if (profile.audience() != null) {
                    yield "目标受众为：" + profile.audience();
                }
                yield null;
            }

            case OFFER_DECISION_REQUIRED -> {
                if (profile.offerDecision() != null) {
                    yield profile.offerDecision();
                }
                yield null;
            }

            case OFFER_DETAIL_REQUIRED -> {
                if (profile.offerDetail() != null) {
                    yield "优惠详情：" + profile.offerDetail();
                }
                if (profile.offerDecision() != null) {
                    yield profile.offerDecision();
                }
                yield null;
            }

            case CHANNEL_REQUIRED -> {
                if (channelOption != null) {
                    // Typed answer: bind to the question code.
                    clAnswers.add(new CampaignClarificationAnswer(
                            code.code(), channelOption.optionId(), null));
                    yield "";
                }
                if (profile.channel() != null) {
                    yield "使用短信（" + profile.channel() + "）发送";
                }
                // Default: SMS in natural language.
                yield "使用短信（SMS）发送";
            }

            case MESSAGE_REQUIRED -> {
                if (profile.messageCopy() != null) {
                    yield "消息内容：" + profile.messageCopy();
                }
                yield null;
            }

            case BRANCH_DECISION_REQUIRED ->
                    profile.branchDecision();

            case BRANCH_DETAIL_REQUIRED -> {
                if (profile.optedIntoBranching()) {
                    yield profile.branchDecision();
                }
                yield null; // stop: profile did not opt into branching
            }

            case BRANCH_RULE_REQUIRED -> {
                if (profile.optedIntoBranching()) {
                    yield profile.branchDecision();
                }
                yield null; // stop: profile did not opt into branching
            }

            case CADENCE_REQUIRED ->
                    profile.cadence();

            case EXECUTION_TIMEZONE_REQUIRED -> {
                if (profile.timezone() != null) {
                    yield "时区：" + profile.timezone();
                }
                yield null;
            }

            case EXECUTION_TIME_REQUIRED -> {
                if (profile.executionTime() != null) {
                    yield "执行时间：" + profile.executionTime();
                }
                yield null;
            }

            case SCHEDULE_START_DATE_REQUIRED -> {
                if (profile.executionDate() != null) {
                    yield "开始日期：" + profile.executionDate();
                }
                yield null;
            }

            case SCHEDULE_END_DATE_REQUIRED -> {
                if (profile.executionDate() != null) {
                    yield "结束日期：" + profile.executionDate();
                }
                yield null;
            }
        };
    }

    /**
     * Builds a canonical sorted-code-set key for repeat detection.
     */
    static String sortedCodeSetKey(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(codes));
        Collections.sort(sorted);
        return String.join(",", sorted);
    }

    /**
     * Tests whether a code string is a recognized {@link CampaignRequirementCode}.
     */
    static boolean isRecognizedRequirementCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        try {
            CampaignRequirementCode.valueOf(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** All recognized host requirement codes. */
    static Set<String> allRecognizedCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (CampaignRequirementCode c : CampaignRequirementCode.values()) {
            codes.add(c.code());
        }
        return Set.copyOf(codes);
    }
}