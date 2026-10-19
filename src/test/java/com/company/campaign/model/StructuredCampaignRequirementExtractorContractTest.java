package com.company.campaign.model;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.campaignagent.BranchDecision;
import com.company.campaign.builder.campaignagent.CampaignAnswerOption;
import com.company.campaign.builder.campaignagent.CampaignCadence;
import com.company.campaign.builder.campaignagent.CampaignComponentClarification;
import com.company.campaign.builder.campaignagent.CampaignComponentCode;
import com.company.campaign.builder.campaignagent.CampaignExecutionSchedule;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.campaignagent.CampaignRequirementBrief;
import com.company.campaign.builder.campaignagent.CampaignRequirementDelta;
import com.company.campaign.builder.campaignagent.CommunicationDecision;
import com.company.campaign.builder.campaignagent.OfferDecision;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requirement-extraction prompt contract is versioned, hashed and defines the bounded
 * extraction rules including the preservation of explicitly stated missing audience qualifiers.
 */
class StructuredCampaignRequirementExtractorContractTest {

    @Test
    void theContractIsVersionedAndContentHashed() {
        assertThat(StructuredCampaignRequirementExtractor.RequirementPrompt.VERSION)
                .isEqualTo("campaign-requirement-extract/12");
        assertThat(StructuredCampaignRequirementExtractor.RequirementPrompt.HASH)
                .isEqualTo(ContentHash.sha256(
                        (StructuredCampaignRequirementExtractor.RequirementPrompt.VERSION + "\n"
                                + StructuredCampaignRequirementExtractor.RequirementPrompt.TEXT + "\n"
                                + StructuredCampaignRequirementExtractor.RequirementPrompt.SCHEMA)
                                .getBytes(StandardCharsets.UTF_8)));
        assertThat(StructuredCampaignRequirementExtractor.RequirementPrompt.HASH.value())
                .startsWith("sha256:");
    }

    @Test
    void thePromptPreservesExplicitlyStatedMissingAudienceQualifiers() {
        String prompt = StructuredCampaignRequirementExtractor.RequirementPrompt.TEXT;

        // Core audience preservation rules — the \"audience\" label may appear on
        // the next line in the text block, so assert the fragments independently.
        assertThat(prompt)
                .contains("Preserve that stated cohort as");
        assertThat(prompt)
                .contains("\"audience\"")
                .contains("never invents a cohort or criterion");

        // The missing-qualifier preservation instruction
        assertThat(prompt)
                .contains("explicitly states that a concrete identifier")
                .contains("preserve that constraint verbatim")
                .contains("Do not silently drop the qualifier")
                .contains("replace it with a guessed field");
    }

    @Test
    void thePromptDeclaresTheClosedChannelSet() {
        String prompt = StructuredCampaignRequirementExtractor.RequirementPrompt.TEXT;
        // The closed channel set must be declared in the prompt for the model to use
        assertThat(prompt).contains("closed set");
    }

    @Test
    void thePromptContainsNoForbiddenHostContent() {
        String prompt = StructuredCampaignRequirementExtractor.RequirementPrompt.TEXT;
        assertThat(prompt)
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("saveDraft")
                .doesNotContain("preview.safe")
                .doesNotContain("SELECT")
                .doesNotContain("GRANT");
    }

    @Test
    void theSchemaDeclaresEveryRequiredField() {
        String schema = StructuredCampaignRequirementExtractor.RequirementPrompt.SCHEMA;
        assertThat(schema)
                .contains("\"audience\"")
                .contains("\"offerDecision\"")
                .contains("\"communicationDecision\"")
                .contains("\"channels\"")
                .contains("\"message\"")
                .contains("\"branchDecision\"")
                .contains("\"cadence\"")
                .contains("\"schedule\"")
                .contains("\"objectiveEvidence\"");
    }

    @Test
    void componentClarificationWordingInvokesTransport() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        AtomicReference<String> capturedUserDocument = new AtomicReference<>();
        StructuredModelPort transport = request -> {
            invoked.set(true);
            capturedUserDocument.set(request.user());
            String response = "{\"prompt\": \"Please specify the time period for this audience condition.\"}";
            return response.getBytes(StandardCharsets.UTF_8);
        };

        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(transport);
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        Optional<String> result = extractor.componentClarificationWording(
                CampaignRequirementBrief.empty(), clarification, "English user text");

        assertThat(invoked.get())
                .as("transport must be invoked for component clarification wording")
                .isTrue();
        assertThat(result)
                .as("valid model wording must be returned")
                .isPresent();
        assertThat(result.get())
                .as("returned wording must equal the model output that passed validation")
                .isEqualTo("Please specify the time period for this audience condition.");

        // Data-boundary assertions: raw user text and cross-component facts must not leak.
        String userDoc = capturedUserDocument.get();
        assertThat(userDoc)
                .as("raw user text must not be sent to the component wording model")
                .doesNotContain("English user text");
        assertThat(userDoc)
                .as("scenario text must not appear in the model request")
                .doesNotContain("scenario");
        assertThat(userDoc)
                .as("only a derived language label must be sent, not the raw message")
                .contains("User preferred language: en");
        // Cross-component facts must not appear for a SEGMENT component clarification.
        assertThat(userDoc)
                .as("offer facts must not appear for a Segment component")
                .doesNotContain("offerDecision")
                .doesNotContain("offerDetail");
        assertThat(userDoc)
                .as("communication facts must not appear for a Segment component")
                .doesNotContain("communicationDecision")
                .doesNotContain("channels:")
                .doesNotContain("message:");
        assertThat(userDoc)
                .as("branch facts must not appear for a Segment component")
                .doesNotContain("branchDecision")
                .doesNotContain("branchDetail")
                .doesNotContain("branchRule");
        assertThat(userDoc)
                .as("schedule facts must not appear for a Segment component")
                .doesNotContain("cadence:")
                .doesNotContain("schedule:");
        // Component-relevant facts must be present.
        assertThat(userDoc)
                .as("campaignType must be present as shared context")
                .contains("campaignType:");
        assertThat(userDoc)
                .as("audience must be present for a Segment component")
                .contains("audience:");
    }

    @Test
    void chineseSelectWindowWordingIsAcceptedByRetentionValidation() {
        String chineseWording = "请指定此受众条件的时间范围，以便规则可以设定具体的时间窗口。";

        AtomicReference<String> capturedUserDocument = new AtomicReference<>();
        StructuredModelPort transport = request -> {
            capturedUserDocument.set(request.user());
            String response = "{\"prompt\": \"" + chineseWording + "\"}";
            return response.getBytes(StandardCharsets.UTF_8);
        };

        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(transport);
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        Optional<String> result = extractor.componentClarificationWording(
                CampaignRequirementBrief.empty(), clarification, "请设置时间范围");

        assertThat(result)
                .as("Chinese SELECT_WINDOW wording with domain keywords must be accepted")
                .isPresent();
        assertThat(result.get())
                .as("accepted Chinese wording must equal the model output")
                .isEqualTo(chineseWording);

        // Data-boundary: raw Chinese user text must not leak.
        String userDoc = capturedUserDocument.get();
        assertThat(userDoc)
                .as("raw Chinese user text must not be sent to the model")
                .doesNotContain("请设置时间范围");
        assertThat(userDoc)
                .as("only a derived zh-CN language label must be sent")
                .contains("User preferred language: zh-CN");
        // Cross-component facts must not appear for a SEGMENT component.
        assertThat(userDoc)
                .doesNotContain("offerDecision")
                .doesNotContain("communicationDecision")
                .doesNotContain("branchDecision")
                .doesNotContain("cadence:")
                .doesNotContain("schedule:");
        // Component-relevant facts must be present.
        assertThat(userDoc)
                .contains("campaignType:")
                .contains("audience:");
    }

    @Test
    void vagueChineseOutputFallsBackToChineseDeterministicPrompt() {
        String vagueChinese = "请提供更多信息。";

        StructuredModelPort transport = request -> {
            String response = "{\"prompt\": \"" + vagueChinese + "\"}";
            return response.getBytes(StandardCharsets.UTF_8);
        };

        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(transport);
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        Optional<String> result = extractor.componentClarificationWording(
                CampaignRequirementBrief.empty(), clarification, "请提供更多信息");

        assertThat(result)
                .as("vague Chinese output must trigger Chinese deterministic fallback")
                .isPresent();
        assertThat(result.get())
                .as("fallback must be the component-owned Chinese SELECT_WINDOW wording")
                .contains("时间窗口")
                .doesNotContain("SELECT_WINDOW")
                .doesNotContain("更多信息");
    }

    @Test
    void transportFailureReturnsChineseFallbackForChineseUser() {
        StructuredModelPort failingTransport = request -> {
            throw new RuntimeException("transport unavailable");
        };

        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(failingTransport);
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        Optional<String> result = extractor.componentClarificationWording(
                CampaignRequirementBrief.empty(), clarification, "请设置时间范围");

        assertThat(result)
                .as("transport failure for Chinese user must return Chinese fallback")
                .isPresent();
        assertThat(result.get())
                .as("Chinese fallback must be the component-owned deterministic wording")
                .contains("时间窗口")
                .doesNotContain("SELECT_WINDOW");
    }

    @Test
    void transportFailureReturnsEmptyForEnglishUser() {
        StructuredModelPort failingTransport = request -> {
            throw new RuntimeException("transport unavailable");
        };

        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(failingTransport);
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        Optional<String> result = extractor.componentClarificationWording(
                CampaignRequirementBrief.empty(), clarification, "Set the time range");

        assertThat(result)
                .as("transport failure for English user must return empty")
                .isEmpty();
    }

    @Test
    void codesAndOptionsPreservedAfterChineseFallback() {
        List<CampaignAnswerOption> originalOptions = List.of(
                new CampaignAnswerOption("opt-30d", "最近30天"),
                new CampaignAnswerOption("opt-60d", "最近60天"));

        StructuredModelPort failingTransport = request -> {
            throw new RuntimeException("transport unavailable");
        };

        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(failingTransport);
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                originalOptions,
                false);

        Optional<String> result = extractor.componentClarificationWording(
                CampaignRequirementBrief.empty(), clarification, "请设置时间范围");

        assertThat(result)
                .as("Chinese fallback must be present")
                .isPresent();
        assertThat(result.get())
                .as("Chinese fallback wording must contain time window without raw codes")
                .contains("时间窗口")
                .doesNotContain("SELECT_WINDOW");

        assertThat(clarification.code())
                .isEqualTo("SEGMENT_INPUT_REQUIRED");
        assertThat(clarification.questionCodes())
                .containsExactly("SELECT_WINDOW");
        assertThat(clarification.options())
                .isEqualTo(originalOptions);
        assertThat(clarification.component())
                .isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(clarification.freeTextAllowed())
                .isFalse();
    }

    @Test
    void crossComponentFactsAreFilteredFromComponentWordingDocument() {
        // Build a brief with facts from multiple components.
        CampaignRequirementBrief mixedBrief = CampaignRequirementBrief.empty()
                .merge(new CampaignRequirementDelta(
                        "all premium members",                        // audience (SEGMENT)
                        OfferDecision.DEFINED, "10% off all items",   // offer (OFFER)
                        CommunicationDecision.DEFINED,                // communication (COMMUNICATION)
                        List.of(CampaignMessageInputs.Channel.EMAIL),
                        "Welcome to our premium program",            // message (COMMUNICATION)
                        BranchDecision.REQUIRED,                      // branch (JOURNEY)
                        "if loyalty tier then VIP path else standard", // branchDetail (JOURNEY)
                        CampaignCadence.DAILY,                        // cadence (SCHEDULE)
                        null,                                         // branchRule
                        null,                                         // channelOperation
                        new CampaignExecutionSchedule(                // schedule (SCHEDULE)
                                "Asia/Shanghai", null, null, "2026-01-01", "2026-12-31"),
                        null));                                       // objectiveEvidence

        // Verify the brief has all facts.
        assertThat(mixedBrief.audience())
                .as("brief must contain audience for cross-component test setup")
                .isEqualTo("all premium members");
        assertThat(mixedBrief.offerDecision())
                .isEqualTo(OfferDecision.DEFINED);

        // SEGMENT component: only campaignType and audience must appear.
        AtomicReference<String> segmentDoc = new AtomicReference<>();
        StructuredModelPort segmentTransport = request -> {
            segmentDoc.set(request.user());
            return "{\"prompt\": \"Which time window?\"}".getBytes(StandardCharsets.UTF_8);
        };
        StructuredCampaignRequirementExtractor extractor =
                new StructuredCampaignRequirementExtractor(segmentTransport);
        CampaignComponentClarification segmentClarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                "SEGMENT_INPUT_REQUIRED",
                "Which time window?",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        extractor.componentClarificationWording(
                mixedBrief, segmentClarification, "user text");

        String segDoc = segmentDoc.get();
        assertThat(segDoc)
                .as("SEGMENT doc must contain component-relevant audience fact")
                .contains("audience:");
        assertThat(segDoc)
                .as("SEGMENT doc must contain campaignType as shared context")
                .contains("campaignType:");
        assertThat(segDoc)
                .as("SEGMENT doc must not contain cross-component offer facts")
                .doesNotContain("offerDecision")
                .doesNotContain("offerDetail");
        assertThat(segDoc)
                .as("SEGMENT doc must not contain cross-component communication facts")
                .doesNotContain("communicationDecision")
                .doesNotContain("channels:")
                .doesNotContain("message:");
        assertThat(segDoc)
                .as("SEGMENT doc must not contain cross-component branch facts")
                .doesNotContain("branchDecision")
                .doesNotContain("branchDetail");
        assertThat(segDoc)
                .as("SEGMENT doc must not contain cross-component schedule facts")
                .doesNotContain("cadence:")
                .doesNotContain("schedule:");
        // Raw user text must not leak.
        assertThat(segDoc)
                .as("raw user text must not be sent to the model")
                .doesNotContain("user text");

        // COMMUNICATION component: only campaignType, communicationDecision, channels, message.
        AtomicReference<String> commDoc = new AtomicReference<>();
        StructuredModelPort commTransport = request -> {
            commDoc.set(request.user());
            return "{\"prompt\": \"What message template?\"}".getBytes(StandardCharsets.UTF_8);
        };
        extractor = new StructuredCampaignRequirementExtractor(commTransport);
        CampaignComponentClarification commClarification = new CampaignComponentClarification(
                CampaignComponentCode.COMMUNICATION,
                CampaignSkillIds.MESSAGE_TEMPLATE,
                "MESSAGE_INPUT_REQUIRED",
                "What message template?",
                List.of("MESSAGE_INPUT_REQUIRED"),
                List.of(),
                true);

        extractor.componentClarificationWording(
                mixedBrief, commClarification, "English text");

        String commDocStr = commDoc.get();
        assertThat(commDocStr)
                .as("COMMUNICATION doc must contain communication facts")
                .contains("communicationDecision")
                .contains("channels:")
                .contains("message:");
        assertThat(commDocStr)
                .as("COMMUNICATION doc must not contain cross-component audience fact")
                .doesNotContain("audience:");
        assertThat(commDocStr)
                .as("COMMUNICATION doc must not contain cross-component offer facts")
                .doesNotContain("offerDecision")
                .doesNotContain("offerDetail");
        assertThat(commDocStr)
                .as("COMMUNICATION doc must not contain cross-component branch facts")
                .doesNotContain("branchDecision");
        assertThat(commDocStr)
                .as("COMMUNICATION doc must not contain cross-component schedule facts")
                .doesNotContain("cadence:")
                .doesNotContain("schedule:");
        // Raw user text must not leak.
        assertThat(commDocStr)
                .doesNotContain("English text");
    }
}