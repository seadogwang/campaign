package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignComponentClarificationWordingTest {

    @Test
    void mainAgentNeverCallsComponentWordingDuringAggregateProjection() {
        CampaignComponentClarification segmentRequest = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SELECT_WINDOW",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);
        CampaignComponentClarification journeyRequest = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                "JOURNEY_INPUT_REQUIRED",
                "What journey?",
                List.of("JOURNEY_INPUT_REQUIRED"),
                List.of(),
                true);
        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentRequest, journeyRequest));

        CampaignRequirementExtractorPort throwingExtractor = new CampaignRequirementExtractorPort() {
            @Override
            public Optional<CampaignRequirementDelta> extractDelta(
                    String utterance, CampaignRequirementBrief accepted,
                    CampaignRequirementCode pendingQuestion) {
                throw new AssertionError("extractDelta must not be called");
            }

            @Override
            public Optional<String> skillClarificationQuestion(
                    CampaignRequirementBrief accepted, CampaignClarification clarification) {
                throw new AssertionError("skillClarificationQuestion must not be called");
            }

            @Override
            public Optional<String> aggregateClarificationQuestion(
                    List<CampaignComponentClarification> requests) {
                throw new AssertionError("aggregateClarificationQuestion must not be called");
            }

            @Override
            public Optional<String> componentClarificationWording(
                    CampaignRequirementBrief accepted,
                    CampaignComponentClarification clarification,
                    String userText) {
                throw new AssertionError("componentClarificationWording must not be called by Main Agent");
            }
        };

        CampaignClarification projected = aggregate.project(
                throwingExtractor, CampaignRequirementBrief.empty());
        assertThat(projected).isNotNull();
        assertThat(projected.prompt())
                .as("aggregate prompt must be deterministic host-built concatenation")
                .startsWith("More details are needed before I can continue:");
    }

    @Test
    void selectWindowAndSelectDefinitionProduceDifferentUnderstandableFallbacks() {
        CampaignSkillOutcome.NeedsInput windowInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        CampaignSkillOutcome.NeedsInput definitionInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("SELECT_DEFINITION"),
                List.of(),
                true);

        String windowPrompt = CampaignComponentClarification.promptFor(windowInput);
        String definitionPrompt = CampaignComponentClarification.promptFor(definitionInput);

        assertThat(windowPrompt)
                .as("SELECT_WINDOW and SELECT_DEFINITION must produce different prompts")
                .isNotEqualTo(definitionPrompt);

        assertThat(windowPrompt)
                .as("SELECT_WINDOW must describe a time window decision")
                .containsAnyOf("time", "period", "window", "range", "days", "weeks", "months");

        assertThat(definitionPrompt)
                .as("SELECT_DEFINITION must describe a definition selection")
                .containsAnyOf("definition", "condition", "rule", "metric");

        assertThat(windowPrompt).doesNotContain("SELECT_WINDOW");
        assertThat(definitionPrompt).doesNotContain("SELECT_DEFINITION");

        assertThat(windowPrompt)
                .isNotEqualTo(CampaignConversationAgent.AUDIENCE_CLARIFY_COPY);
        assertThat(definitionPrompt)
                .isNotEqualTo(CampaignConversationAgent.AUDIENCE_CLARIFY_COPY);
    }

    @Test
    void allKnownSegmentQuestionCodesProduceDistinctUnderstandableFallbacks() {
        List<String> segmentCodes = List.of(
                "SELECT_WINDOW", "SELECT_DEFINITION", "DISAMBIGUATE_DEFINITION",
                "MISSING_CURRENCY", "AMBIGUOUS_GROUPING", "AMBIGUOUS_METRIC_MEANING",
                "AMBIGUOUS_NULL_INCLUSION", "AMBIGUOUS_RELATION_SCOPE",
                "AMBIGUOUS_FILTER_SCOPE", "UNSUPPORTED_REQUIREMENT",
                "SELECT_AUDIENCE_REFERENCE", "SELECT_AUDIENCE_VERSION",
                "AUDIENCE_REFERENCE_UNAVAILABLE");

        String generic = CampaignConversationAgent.AUDIENCE_CLARIFY_COPY;

        for (String code : segmentCodes) {
            CampaignSkillOutcome.NeedsInput input = new CampaignSkillOutcome.NeedsInput(
                    CampaignSkillIds.SEGMENT_RULE_BASED,
                    "SEGMENT_INPUT_REQUIRED",
                    List.of(code),
                    List.of(),
                    true);

            String prompt = CampaignComponentClarification.promptFor(input);

            assertThat(prompt)
                    .as("Segment code " + code + " must produce a non-null prompt")
                    .isNotNull();

            assertThat(prompt)
                    .as("Segment code " + code + " must differ from generic")
                    .isNotEqualTo(generic);

            assertThat(prompt)
                    .as("Fallback must not expose raw code " + code)
                    .doesNotContain(code);
        }
    }

    @Test
    void disambiguateDefinitionFallbackIsDistinctFromSelectDefinitionFallback() {
        CampaignSkillOutcome.NeedsInput disambiguateInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("DISAMBIGUATE_DEFINITION"),
                List.of(),
                true);

        CampaignSkillOutcome.NeedsInput selectInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("SELECT_DEFINITION"),
                List.of(),
                true);

        String disambiguatePrompt = CampaignComponentClarification.promptFor(disambiguateInput);
        String selectPrompt = CampaignComponentClarification.promptFor(selectInput);

        assertThat(disambiguatePrompt)
                .isNotEqualTo(selectPrompt);
        assertThat(disambiguatePrompt)
                .isNotEqualTo(CampaignConversationAgent.AUDIENCE_CLARIFY_COPY);
    }

    @Test
    void genericAudienceClarifyCopyUsedOnlyWhenNoSpecificFallbackApplies() {
        CampaignSkillOutcome.NeedsInput unknownInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("SEGMENT_UNKNOWN_CODE"),
                List.of(),
                true);

        String prompt = CampaignComponentClarification.promptFor(unknownInput);

        assertThat(prompt)
                .as("unknown code falls through to generic audience clarify copy")
                .isEqualTo(CampaignConversationAgent.AUDIENCE_CLARIFY_COPY);
    }

    @Test
    void safeSemanticModelWordingIsAcceptedForItsComponent() {
        CampaignComponentClarification original = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        String safeWording = "Please specify the time period for this audience condition "
                + "so the rule can be built with a specific date range.";

        CampaignComponentClarification accepted = CampaignComponentClarification
                .validateAndAcceptWording(original, safeWording);

        assertThat(accepted.prompt())
                .as("safe semantic wording must be accepted")
                .isEqualTo(safeWording);

        assertThat(accepted.questionCodes())
                .containsExactly("SELECT_WINDOW");
        assertThat(accepted.code())
                .isEqualTo("SEGMENT_INPUT_REQUIRED");
        assertThat(accepted.component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(accepted.skillId())
                .isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
    }

    @Test
    void safeWordingForSelectDefinitionIsAccepted() {
        CampaignComponentClarification original = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which definition?",
                List.of("SELECT_DEFINITION"),
                List.of(),
                true);

        String safeWording = "Select the metric definition to use for this "
                + "audience condition so a specific rule can be evaluated.";

        CampaignComponentClarification accepted = CampaignComponentClarification
                .validateAndAcceptWording(original, safeWording);

        assertThat(accepted.prompt())
                .as("safe SELECT_DEFINITION wording must be accepted")
                .isEqualTo(safeWording);

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(safeWording, original))
                .as("semantic retention must pass for correctly-worded SELECT_DEFINITION")
                .isTrue();
    }

    @Test
    void semanticsDroppingWordingIsRejected() {
        CampaignComponentClarification original = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Segment fallback for window selection",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        String vagueWording = "Please supply the missing detail so this can be completed.";

        CampaignComponentClarification rejected = CampaignComponentClarification
                .validateAndAcceptWording(original, vagueWording);

        assertThat(rejected.prompt())
                .as("vague semantics-dropping wording must be rejected to fallback")
                .isEqualTo("Segment fallback for window selection");
    }

    @Test
    void oversizedWordingIsRejected() {
        CampaignComponentClarification original = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Segment fallback",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        StringBuilder oversizedBuilder = new StringBuilder();
        int target = CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH + 10;
        while (oversizedBuilder.length() < target) {
            oversizedBuilder.append("padding text to exceed limit ");
        }
        String oversized = oversizedBuilder.toString();

        CampaignComponentClarification rejected = CampaignComponentClarification
                .validateAndAcceptWording(original, oversized);

        assertThat(rejected.prompt())
                .as("oversized wording must be rejected")
                .isEqualTo("Segment fallback");
    }

    @Test
    void crossComponentWordingIsRejected() {
        CampaignComponentClarification segmentOriginal = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Segment fallback",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        String crossPromotionWording = "Select the time window and also choose a promotion "
                + "reward for this campaign.";

        CampaignComponentClarification rejected = CampaignComponentClarification
                .validateAndAcceptWording(segmentOriginal, crossPromotionWording);

        assertThat(rejected.prompt())
                .as("cross-component wording mentioning promotion must be rejected")
                .isEqualTo("Segment fallback");

        String crossChannelWording = "Select a time window and pick a delivery channel "
                + "for the message.";

        CampaignComponentClarification rejected2 = CampaignComponentClarification
                .validateAndAcceptWording(segmentOriginal, crossChannelWording);

        assertThat(rejected2.prompt())
                .as("cross-component wording mentioning channel must be rejected")
                .isEqualTo("Segment fallback");
    }

    @Test
    void nullOrBlankWordingIsRejected() {
        CampaignComponentClarification original = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Segment fallback",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        assertThat(CampaignComponentClarification.validateAndAcceptWording(original, null).prompt())
                .as("null wording must be rejected")
                .isEqualTo("Segment fallback");

        assertThat(CampaignComponentClarification.validateAndAcceptWording(original, "").prompt())
                .as("blank wording must be rejected")
                .isEqualTo("Segment fallback");

        assertThat(CampaignComponentClarification.validateAndAcceptWording(original, "   ").prompt())
                .as("whitespace wording must be rejected")
                .isEqualTo("Segment fallback");
    }

    @Test
    void sameQuestionCodeAcrossTwoComponentsRemainsTwoIssues() {
        String sharedCode = "SHARED_INPUT";
        CampaignComponentClarification segmentIssue = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                sharedCode,
                "Segment wording for shared code",
                List.of(sharedCode),
                List.of(),
                true);
        CampaignComponentClarification journeyIssue = new CampaignComponentClarification(
                CampaignComponentCode.JOURNEY,
                CampaignSkillIds.JOURNEY_FLOW,
                sharedCode,
                "Journey wording for shared code",
                List.of(sharedCode),
                List.of(),
                true);

        CampaignClarificationAggregate aggregate = CampaignClarificationAggregate.of(
                List.of(segmentIssue, journeyIssue));

        assertThat(aggregate.requests()).hasSize(2);
        assertThat(aggregate.requests().get(0).component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(aggregate.requests().get(0).prompt())
                .isEqualTo("Segment wording for shared code");
        assertThat(aggregate.requests().get(1).component())
                .isEqualTo(CampaignComponentCode.JOURNEY);
        assertThat(aggregate.requests().get(1).prompt())
                .isEqualTo("Journey wording for shared code");

        CampaignComponentClarification seg = aggregate.requestFor(CampaignComponentCode.SEGMENT);
        CampaignComponentClarification jou = aggregate.requestFor(CampaignComponentCode.JOURNEY);

        assertThat(seg).isNotNull();
        assertThat(jou).isNotNull();
        assertThat(seg.prompt()).isNotEqualTo(jou.prompt());
        assertThat(seg.code()).isEqualTo(jou.code());
        assertThat(seg.component()).isNotEqualTo(jou.component());
    }

    @Test
    void optionsAndCodesPreservedAfterWordingValidation() {
        List<CampaignAnswerOption> originalOptions = List.of(
                new CampaignAnswerOption("opt-last-30", "Last 30 days"),
                new CampaignAnswerOption("opt-last-60", "Last 60 days"),
                new CampaignAnswerOption("opt-last-90", "Last 90 days"));

        CampaignComponentClarification original = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW", "SEGMENT_AUDIENCE_REFINE"),
                originalOptions,
                false);

        String safeWording = "Specify the time range for this audience "
                + "by choosing from the available periods.";

        CampaignComponentClarification accepted = CampaignComponentClarification
                .validateAndAcceptWording(original, safeWording);

        assertThat(accepted.options())
                .as("options must be preserved after wording")
                .isEqualTo(originalOptions);
        assertThat(accepted.options()).hasSize(3);
        assertThat(accepted.options().get(0).optionId()).isEqualTo("opt-last-30");
        assertThat(accepted.options().get(0).label()).isEqualTo("Last 30 days");

        assertThat(accepted.questionCodes())
                .as("question codes must be preserved exactly")
                .containsExactly("SELECT_WINDOW", "SEGMENT_AUDIENCE_REFINE");

        assertThat(accepted.code())
                .as("error code must be preserved")
                .isEqualTo("SEGMENT_INPUT_REQUIRED");

        assertThat(accepted.component())
                .as("component must be preserved")
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(accepted.skillId())
                .as("skillId must be preserved")
                .isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);

        assertThat(accepted.freeTextAllowed())
                .as("freeTextAllowed must be false as in original")
                .isFalse();
    }

    @Test
    void optionsPreservedEvenWhenWordingIsRejected() {
        List<CampaignAnswerOption> originalOptions = List.of(
                new CampaignAnswerOption("def-a", "Definition A"),
                new CampaignAnswerOption("def-b", "Definition B"));

        CampaignComponentClarification original = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which definition?",
                List.of("SELECT_DEFINITION"),
                originalOptions,
                false);

        String vagueWording = "Supply missing detail.";

        CampaignComponentClarification rejected = CampaignComponentClarification
                .validateAndAcceptWording(original, vagueWording);

        assertThat(rejected.prompt())
                .as("vague wording is rejected")
                .isEqualTo("Which definition?");

        assertThat(rejected.options())
                .as("options survive wording rejection")
                .isEqualTo(originalOptions);

        assertThat(rejected.questionCodes())
                .as("question codes survive wording rejection")
                .containsExactly("SELECT_DEFINITION");
    }

    @Test
    void componentAgentConstructedWithWordingPort() {
        CampaignAgentEnvironment env = new CampaignAgentEnvironment() {
            @Override
            public CampaignRoutingModelPort routingModel() {
                return request -> "[]".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public CampaignRequirementExtractorPort requirementExtractor() {
                return new CampaignRequirementExtractorPort() {
                    @Override
                    public Optional<CampaignRequirementDelta> extractDelta(
                            String utterance, CampaignRequirementBrief accepted,
                            CampaignRequirementCode pendingQuestion) {
                        return Optional.empty();
                    }
                };
            }
        };
        CampaignSkillRouter router = new CampaignSkillRouter("conv-word-1", "run-word-1", env);
        CampaignComponentAgent agent = new CampaignComponentAgent(
                router, env.requirementExtractor());

        assertThat(agent.toString())
                .as("agent with wording port must report wordingPort=true")
                .contains("wordingPort=true");

        CampaignComponentAgent agentWithout = new CampaignComponentAgent(router);
        assertThat(agentWithout.toString())
                .as("agent without wording port must report wordingPort=false")
                .contains("wordingPort=false");
    }

    @Test
    void retainsQuestionSemanticsReturnsTrueWhenKeywordsMatch() {
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "fallback",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(
                "Specify the time period for this audience condition", clarification))
                .as("wording with time keyword must retain semantics")
                .isTrue();

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(
                "Choose the window of days for the rule", clarification))
                .as("wording with window and days keywords must retain semantics")
                .isTrue();
    }

    @Test
    void retainsQuestionSemanticsReturnsFalseWhenNoKeywordMatches() {
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "fallback",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(
                "Please provide more information", clarification))
                .as("wording without time/window keywords must fail")
                .isFalse();

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(
                "Supply the missing detail", clarification))
                .as("generic wording without any time concept must fail")
                .isFalse();
    }

    @Test
    void retainsQuestionSemanticsWithMultipleQuestionCodes() {
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "fallback",
                List.of("SELECT_WINDOW", "SEGMENT_AUDIENCE_REFINE"),
                List.of(),
                true);

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(
                "Specify the time range for this audience", clarification))
                .as("wording matching SELECT_WINDOW with unregistered second code must pass")
                .isTrue();
    }

    @Test
    void disambiguateDefinitionRetainsSemanticsWithDefinitionKeywords() {
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "fallback",
                List.of("DISAMBIGUATE_DEFINITION"),
                List.of(),
                true);

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(
                "Which definition should be applied to resolve this ambiguity?", clarification))
                .as("DISAMBIGUATE_DEFINITION must accept definition keywords")
                .isTrue();

        assertThat(CampaignComponentClarification.retainsQuestionSemantics(
                "Supply the missing detail", clarification))
                .as("DISAMBIGUATE_DEFINITION must reject generic wording")
                .isFalse();
    }

    @Test
    void audienceReferenceCodesProduceDistinctFallbacks() {
        CampaignSkillOutcome.NeedsInput refInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("SELECT_AUDIENCE_REFERENCE"),
                List.of(),
                true);

        CampaignSkillOutcome.NeedsInput versionInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("SELECT_AUDIENCE_VERSION"),
                List.of(),
                true);

        CampaignSkillOutcome.NeedsInput unavailableInput = new CampaignSkillOutcome.NeedsInput(
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                List.of("AUDIENCE_REFERENCE_UNAVAILABLE"),
                List.of(),
                true);

        String refPrompt = CampaignComponentClarification.promptFor(refInput);
        String versionPrompt = CampaignComponentClarification.promptFor(versionInput);
        String unavailablePrompt = CampaignComponentClarification.promptFor(unavailableInput);

        assertThat(refPrompt).isNotEqualTo(CampaignConversationAgent.AUDIENCE_CLARIFY_COPY);
        assertThat(versionPrompt).isNotEqualTo(CampaignConversationAgent.AUDIENCE_CLARIFY_COPY);
        assertThat(unavailablePrompt).isNotEqualTo(CampaignConversationAgent.AUDIENCE_CLARIFY_COPY);

        assertThat(refPrompt).isNotEqualTo(versionPrompt);
        assertThat(refPrompt).isNotEqualTo(unavailablePrompt);
        assertThat(versionPrompt).isNotEqualTo(unavailablePrompt);
    }
}