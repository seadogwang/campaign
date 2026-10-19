package com.company.campaign.builder.campaignagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic unit tests for the question-code-to-answer planner.
 *
 * <p>These tests prove the planner produces stable answers for every recognized
 * {@link CampaignRequirementCode} and correctly handles multiple codes, unknown codes,
 * branch refusal, and future schedule composition. No live model call is made.
 */
@DisplayName("Question-Code Answer Planner")
class QuestionCodeAnswerPlannerTest {

    private static final CompletionProfile FULL_PROFILE = new CompletionProfile(
            "已完成首单购买的新会员",
            "提供满500减80的专属折扣",
            "满500减80，限运动鞋品类",
            "SMS",
            "亲爱的会员，感谢您的支持！",
            "不设置分支流程",
            false,  // not opted into branching
            "单次执行",
            "Asia/Shanghai",
            "2025-07-01T10:00:00Z",
            "2025-07-01",
            null  // no registered event
    );

    private static final CompletionProfile BRANCH_PROFILE = new CompletionProfile(
            "高价值会员",
            "提供满1000减150",
            "满1000减150",
            "Email",
            "VIP专属优惠",
            "按会员等级分3组发送不同消息",
            true,  // opted into branching
            "单次执行",
            "Asia/Shanghai",
            "2025-08-01T10:00:00Z",
            "2025-08-01",
            "Purchase completed"
    );

    private static final CompletionProfile MINIMAL_PROFILE = CompletionProfile.minimal();

    private static final Set<String> EMPTY_PREVIOUS = Set.of();

    @Nested
    @DisplayName("Single-code answers")
    class SingleCodeAnswers {

        @Test
        @DisplayName("CAMPAIGN_TYPE_REQUIRED → one-time campaign in natural language")
        void campaignTypeRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CAMPAIGN_TYPE_REQUIRED"), List.of(), MINIMAL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("单次营销活动");
            assertThat(answer.answeredQuestionCodes()).containsExactly("CAMPAIGN_TYPE_REQUIRED");
            assertThat(answer.unknownCodes()).isEmpty();
            assertThat(answer.missingFactCodes()).isEmpty();
        }

        @Test
        @DisplayName("AUDIENCE_REQUIRED → profile audience")
        void audienceRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("AUDIENCE_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("已完成首单购买的新会员");
            assertThat(answer.answeredQuestionCodes()).containsExactly("AUDIENCE_REQUIRED");
        }

        @Test
        @DisplayName("AUDIENCE_REQUIRED without profile audience → stops with missing fact")
        void audienceRequiredMissing() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("AUDIENCE_REQUIRED"), List.of(), MINIMAL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
            assertThat(answer.missingFactCodes()).containsExactly("AUDIENCE_REQUIRED");
            assertThat(answer.answeredQuestionCodes()).isEmpty();
        }

        @Test
        @DisplayName("OFFER_DECISION_REQUIRED → profile offer decision")
        void offerDecisionRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("OFFER_DECISION_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("满500减80");
            assertThat(answer.answeredQuestionCodes()).containsExactly("OFFER_DECISION_REQUIRED");
        }

        @Test
        @DisplayName("OFFER_DETAIL_REQUIRED → profile offer detail")
        void offerDetailRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("OFFER_DETAIL_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("满500减80，限运动鞋品类");
            assertThat(answer.answeredQuestionCodes()).containsExactly("OFFER_DETAIL_REQUIRED");
        }

        @Test
        @DisplayName("CHANNEL_REQUIRED with SMS option → typed answer, should not stop")
        void channelRequiredWithOption() {
            List<CampaignAnswerOption> options = List.of(
                    new CampaignAnswerOption("channel.sms", "短信（SMS）"));

            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED"), options, FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            // When a typed option is used and it's the only code, utterance is empty
            // and the answer is conveyed via clAnswers.
            assertThat(answer.clAnswers()).hasSize(1);
            assertThat(answer.clAnswers().get(0).optionId()).isEqualTo("channel.sms");
            assertThat(answer.clAnswers().get(0).code()).isEqualTo("CHANNEL_REQUIRED");
            // CHANNEL_REQUIRED is still recorded as answered.
            assertThat(answer.answeredQuestionCodes()).containsExactly("CHANNEL_REQUIRED");
        }

        @Test
        @DisplayName("CHANNEL_REQUIRED without option → natural language SMS")
        void channelRequiredNoOption() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("短信");
            assertThat(answer.utterance()).contains("SMS");
            assertThat(answer.answeredQuestionCodes()).containsExactly("CHANNEL_REQUIRED");
        }

        @Test
        @DisplayName("MESSAGE_REQUIRED → profile message copy")
        void messageRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("MESSAGE_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("亲爱的会员");
            assertThat(answer.answeredQuestionCodes()).containsExactly("MESSAGE_REQUIRED");
        }

        @Test
        @DisplayName("BRANCH_DECISION_REQUIRED → explicit no branch")
        void branchDecisionRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("BRANCH_DECISION_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("不设置分支流程");
            assertThat(answer.answeredQuestionCodes()).containsExactly("BRANCH_DECISION_REQUIRED");
        }

        @Test
        @DisplayName("CADENCE_REQUIRED → explicit one-time execution")
        void cadenceRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CADENCE_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("单次执行");
            assertThat(answer.answeredQuestionCodes()).containsExactly("CADENCE_REQUIRED");
        }

        @Test
        @DisplayName("EXECUTION_TIMEZONE_REQUIRED → profile timezone")
        void timezoneRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("EXECUTION_TIMEZONE_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("Asia/Shanghai");
            assertThat(answer.answeredQuestionCodes()).containsExactly("EXECUTION_TIMEZONE_REQUIRED");
        }

        @Test
        @DisplayName("EXECUTION_TIME_REQUIRED → profile execution time")
        void executionTimeRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("EXECUTION_TIME_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("2025-07-01T10:00:00Z");
            assertThat(answer.answeredQuestionCodes()).containsExactly("EXECUTION_TIME_REQUIRED");
        }

        @Test
        @DisplayName("SCHEDULE_START_DATE_REQUIRED → profile execution date")
        void scheduleStartDateRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("SCHEDULE_START_DATE_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("2025-07-01");
            assertThat(answer.answeredQuestionCodes()).containsExactly("SCHEDULE_START_DATE_REQUIRED");
        }

        @Test
        @DisplayName("SCHEDULE_END_DATE_REQUIRED → profile execution date")
        void scheduleEndDateRequired() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("SCHEDULE_END_DATE_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("2025-07-01");
            assertThat(answer.answeredQuestionCodes()).containsExactly("SCHEDULE_END_DATE_REQUIRED");
        }

        @Test
        @DisplayName("EVENT_REQUIRED with registered event → answers")
        void eventRequiredWithEvent() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("EVENT_REQUIRED"), List.of(), BRANCH_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("Purchase completed");
            assertThat(answer.answeredQuestionCodes()).containsExactly("EVENT_REQUIRED");
        }

        @Test
        @DisplayName("EVENT_REQUIRED without registered event → stops honestly")
        void eventRequiredStopsWithoutEvent() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("EVENT_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
            assertThat(answer.missingFactCodes()).containsExactly("EVENT_REQUIRED");
            assertThat(answer.answeredQuestionCodes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Multiple-code composition")
    class MultipleCodeComposition {

        @Test
        @DisplayName("OFFER + CHANNEL + MESSAGE → composed answer")
        void offerChannelMessage() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("OFFER_DECISION_REQUIRED", "CHANNEL_REQUIRED", "MESSAGE_REQUIRED"),
                    List.of(),
                    FULL_PROFILE,
                    EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("满500减80");
            assertThat(answer.utterance()).contains("短信");
            assertThat(answer.utterance()).contains("亲爱的会员");
            assertThat(answer.answeredQuestionCodes())
                    .containsExactly("OFFER_DECISION_REQUIRED", "CHANNEL_REQUIRED", "MESSAGE_REQUIRED");
        }

        @Test
        @DisplayName("CHANNEL + BRANCH + CADENCE → composed answer")
        void channelBranchCadence() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED", "BRANCH_DECISION_REQUIRED", "CADENCE_REQUIRED"),
                    List.of(),
                    FULL_PROFILE,
                    EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("短信");
            assertThat(answer.utterance()).contains("不设置分支流程");
            assertThat(answer.utterance()).contains("单次执行");
            assertThat(answer.answeredQuestionCodes())
                    .containsExactly("CHANNEL_REQUIRED", "BRANCH_DECISION_REQUIRED", "CADENCE_REQUIRED");
        }

        @Test
        @DisplayName("AUDIENCE + OFFER + CHANNEL + BRANCH + CADENCE + TIMEZONE + TIME")
        void fullRequirementSet() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("AUDIENCE_REQUIRED", "OFFER_DECISION_REQUIRED", "CHANNEL_REQUIRED",
                            "BRANCH_DECISION_REQUIRED", "CADENCE_REQUIRED",
                            "EXECUTION_TIMEZONE_REQUIRED", "EXECUTION_TIME_REQUIRED"),
                    List.of(),
                    FULL_PROFILE,
                    EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("已完成首单购买");
            assertThat(answer.utterance()).contains("满500减80");
            assertThat(answer.utterance()).contains("短信");
            assertThat(answer.utterance()).contains("不设置分支流程");
            assertThat(answer.utterance()).contains("单次执行");
            assertThat(answer.utterance()).contains("Asia/Shanghai");
            assertThat(answer.utterance()).contains("2025-07-01T10:00:00Z");
            assertThat(answer.answeredQuestionCodes()).hasSize(7);
        }

        @Test
        @DisplayName("CHANNEL first with typed option → option takes precedence, utterance covers rest")
        void channelFirstWithOptionAndOtherCodes() {
            List<CampaignAnswerOption> options = List.of(
                    new CampaignAnswerOption("channel.sms", "短信（SMS）"));

            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED", "MESSAGE_REQUIRED"),
                    options,
                    FULL_PROFILE,
                    EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.clAnswers()).hasSize(1);
            assertThat(answer.clAnswers().get(0).optionId()).isEqualTo("channel.sms");
            // Channel is answered via clAnswers, OFFER and MESSAGE via utterance.
            assertThat(answer.answeredQuestionCodes())
                    .contains("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED", "MESSAGE_REQUIRED");
        }
    }

    @Nested
    @DisplayName("Branch refusal")
    class BranchRefusal {

        @Test
        @DisplayName("BRANCH_DETAIL_REQUIRED without branching opted in → stops")
        void branchDetailRequiredStopsWhenNotOptedIn() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("BRANCH_DETAIL_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
            assertThat(answer.missingFactCodes()).containsExactly("BRANCH_DETAIL_REQUIRED");
        }

        @Test
        @DisplayName("BRANCH_RULE_REQUIRED without branching opted in → stops")
        void branchRuleRequiredStopsWhenNotOptedIn() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("BRANCH_RULE_REQUIRED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
            assertThat(answer.missingFactCodes()).containsExactly("BRANCH_RULE_REQUIRED");
        }

        @Test
        @DisplayName("BRANCH_DETAIL_REQUIRED with branching opted in → answers")
        void branchDetailRequiredWhenOptedIn() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("BRANCH_DETAIL_REQUIRED"), List.of(), BRANCH_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.utterance()).contains("按会员等级分3组");
            assertThat(answer.answeredQuestionCodes()).containsExactly("BRANCH_DETAIL_REQUIRED");
        }

        @Test
        @DisplayName("BRANCH_RULE_REQUIRED with branching opted in → answers")
        void branchRuleRequiredWhenOptedIn() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("BRANCH_RULE_REQUIRED"), List.of(), BRANCH_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isFalse();
            assertThat(answer.answeredQuestionCodes()).containsExactly("BRANCH_RULE_REQUIRED");
        }
    }

    @Nested
    @DisplayName("Repeat detection")
    class RepeatDetection {

        @Test
        @DisplayName("Identical question-code set → repeat detected")
        void repeatDetected() {
            Set<String> previous = new HashSet<>();

            // First call uses empty history — the current set is not yet recorded.
            QuestionCodeAnswerPlanner.PlannedAnswer first = QuestionCodeAnswerPlanner.plan(
                    List.of("OFFER_DECISION_REQUIRED", "CHANNEL_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(first.isRepeat()).isFalse();
            assertThat(first.shouldStop()).isFalse();

            // Record the key after the first successful non-repeat answer.
            previous.add(QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED")));

            // Second call with same codes (different order) → repeat.
            QuestionCodeAnswerPlanner.PlannedAnswer second = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(second.isRepeat()).isTrue();
            assertThat(second.shouldStop()).isTrue();
        }

        @Test
        @DisplayName("Different question-code sets → no repeat")
        void differentSetsNoRepeat() {
            Set<String> previous = new HashSet<>();

            // First call with empty history.
            QuestionCodeAnswerPlanner.PlannedAnswer first = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(first.isRepeat()).isFalse();

            // Record first turn's key.
            previous.add(QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED")));

            // Second call with different codes → not a repeat.
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("AUDIENCE_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(answer.isRepeat()).isFalse();
            assertThat(answer.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("Same codes in different order → still detected as repeat")
        void orderIndependentRepeat() {
            Set<String> previous = new HashSet<>();

            // First call with empty history.
            QuestionCodeAnswerPlanner.PlannedAnswer first = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(first.isRepeat()).isFalse();

            // Record the key after first successful answer.
            previous.add(QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED")));

            // Second call with reordered codes → still a repeat.
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("OFFER_DECISION_REQUIRED", "CHANNEL_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(answer.isRepeat()).isTrue();
        }

        @Test
        @DisplayName("Sequence: OFFER first, CHANNEL second (not repeat), reordered OFFER later → repeat")
        void sequenceOfferChannelOffer() {
            Set<String> previous = new HashSet<>();

            // Turn 1: OFFER_DECISION_REQUIRED with empty history → not repeat.
            QuestionCodeAnswerPlanner.PlannedAnswer turn1 = QuestionCodeAnswerPlanner.plan(
                    List.of("OFFER_DECISION_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(turn1.isRepeat()).isFalse();
            assertThat(turn1.shouldStop()).isFalse();

            // Record OFFER key after successful answer.
            previous.add(QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("OFFER_DECISION_REQUIRED")));

            // Turn 2: CHANNEL_REQUIRED, history has OFFER → not repeat (different key).
            QuestionCodeAnswerPlanner.PlannedAnswer turn2 = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(turn2.isRepeat()).isFalse();
            assertThat(turn2.shouldStop()).isFalse();

            // Record CHANNEL key after successful answer.
            previous.add(QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("CHANNEL_REQUIRED")));

            // Turn 3: OFFER_DECISION_REQUIRED again → repeat (OFFER key already in history).
            QuestionCodeAnswerPlanner.PlannedAnswer turn3 = QuestionCodeAnswerPlanner.plan(
                    List.of("OFFER_DECISION_REQUIRED"),
                    List.of(), FULL_PROFILE, previous);

            assertThat(turn3.isRepeat()).isTrue();
            assertThat(turn3.shouldStop()).isTrue();
        }
    }

    @Nested
    @DisplayName("Unknown and mixed codes")
    class UnknownAndMixedCodes {

        @Test
        @DisplayName("Unknown code → stops with attribution")
        void unknownCodeStops() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("UNKNOWN_COMPONENT_CODE"),
                    List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
            assertThat(answer.unknownCodes()).containsExactly("UNKNOWN_COMPONENT_CODE");
            assertThat(answer.answeredQuestionCodes()).isEmpty();
        }

        @Test
        @DisplayName("Mixed known + unknown → stops, unknown attributed")
        void mixedKnownUnknown() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CHANNEL_REQUIRED", "SEGMENT_UNKNOWN_CODE"),
                    List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
            assertThat(answer.unknownCodes()).containsExactly("SEGMENT_UNKNOWN_CODE");
            assertThat(answer.answeredQuestionCodes()).containsExactly("CHANNEL_REQUIRED");
        }

        @Test
        @DisplayName("Empty question codes → stop")
        void emptyCodes() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of(), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
        }

        @Test
        @DisplayName("Null question codes → stop")
        void nullCodes() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    null, List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            assertThat(answer.shouldStop()).isTrue();
        }
    }

    @Nested
    @DisplayName("Utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("sortedCodeSetKey normalizes order")
        void sortedCodeSetKeyNormalizes() {
            String key1 = QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED", "MESSAGE_REQUIRED"));
            String key2 = QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("MESSAGE_REQUIRED", "CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED"));

            assertThat(key1).isEqualTo(key2);
            assertThat(key1).isEqualTo("CHANNEL_REQUIRED,MESSAGE_REQUIRED,OFFER_DECISION_REQUIRED");
        }

        @Test
        @DisplayName("sortedCodeSetKey deduplicates")
        void sortedCodeSetKeyDeduplicates() {
            String key = QuestionCodeAnswerPlanner.sortedCodeSetKey(
                    List.of("CHANNEL_REQUIRED", "CHANNEL_REQUIRED", "OFFER_DECISION_REQUIRED"));

            assertThat(key).isEqualTo("CHANNEL_REQUIRED,OFFER_DECISION_REQUIRED");
        }

        @Test
        @DisplayName("isRecognizedRequirementCode for valid codes")
        void recognizedCodes() {
            assertThat(QuestionCodeAnswerPlanner.isRecognizedRequirementCode("AUDIENCE_REQUIRED")).isTrue();
            assertThat(QuestionCodeAnswerPlanner.isRecognizedRequirementCode("CHANNEL_REQUIRED")).isTrue();
            assertThat(QuestionCodeAnswerPlanner.isRecognizedRequirementCode("UNKNOWN_CODE")).isFalse();
            assertThat(QuestionCodeAnswerPlanner.isRecognizedRequirementCode("")).isFalse();
            assertThat(QuestionCodeAnswerPlanner.isRecognizedRequirementCode(null)).isFalse();
        }

        @Test
        @DisplayName("allRecognizedCodes returns all 16 requirement codes")
        void allRecognizedCodes() {
            Set<String> codes = QuestionCodeAnswerPlanner.allRecognizedCodes();
            assertThat(codes).hasSize(16);
            assertThat(codes).contains(
                    "CAMPAIGN_TYPE_REQUIRED", "CAMPAIGN_TYPE_UNSUPPORTED",
                    "EVENT_REQUIRED", "AUDIENCE_REQUIRED",
                    "OFFER_DECISION_REQUIRED", "OFFER_DETAIL_REQUIRED",
                    "CHANNEL_REQUIRED", "MESSAGE_REQUIRED",
                    "BRANCH_DECISION_REQUIRED", "BRANCH_DETAIL_REQUIRED", "BRANCH_RULE_REQUIRED",
                    "CADENCE_REQUIRED",
                    "EXECUTION_TIMEZONE_REQUIRED", "EXECUTION_TIME_REQUIRED",
                    "SCHEDULE_START_DATE_REQUIRED", "SCHEDULE_END_DATE_REQUIRED");
        }
    }

    @Nested
    @DisplayName("CAMPAIGN_TYPE_UNSUPPORTED handling")
    class CampaignTypeUnsupported {

        @Test
        @DisplayName("CAMPAIGN_TYPE_UNSUPPORTED → recognized, system handles, stops")
        void campaignTypeUnsupported() {
            QuestionCodeAnswerPlanner.PlannedAnswer answer = QuestionCodeAnswerPlanner.plan(
                    List.of("CAMPAIGN_TYPE_UNSUPPORTED"), List.of(), FULL_PROFILE, EMPTY_PREVIOUS);

            // The code is recognized and recorded as system-handled.
            // No natural utterance is generated, and the planner stops since
            // the system will route to OUT_OF_SCOPE.
            assertThat(answer.shouldStop()).isTrue();
            assertThat(answer.answeredQuestionCodes()).containsExactly("CAMPAIGN_TYPE_UNSUPPORTED");
            assertThat(answer.unknownCodes()).isEmpty();
            assertThat(answer.missingFactCodes()).isEmpty();
        }
    }
}