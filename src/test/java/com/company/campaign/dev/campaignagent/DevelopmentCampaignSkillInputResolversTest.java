package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.CampaignGovernanceInputs;
import com.company.campaign.builder.campaignagent.CampaignJourneyInputs;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.campaignagent.CampaignPromotionInputs;
import com.company.campaign.builder.campaignagent.CampaignScheduleInputs;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T32 requirement 3: every Skill input resolver accepts exactly the public authoring fields of its accepted
 * input record and fails closed to the honest unresolved input on a missing fact, an unknown or reserved field,
 * the wrong shape, a non-canonical ordering, an unknown node/capability kind and an out-of-bound value.
 */
class DevelopmentCampaignSkillInputResolversTest {

    private static final List<CampaignClarificationAnswer> NO_ANSWERS = List.of();

    private static final class FakeTransport implements DevelopmentStructuredModelPort {

        private final Deque<byte[]> queued = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        private FakeTransport enqueue(String json) {
            queued.add(json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public byte[] invokeStructured(String prompt) {
            prompts.add(prompt);
            return queued.poll();
        }
    }

    private static DevelopmentSkillInputResolvers over(String json) {
        return new DevelopmentSkillInputResolvers(new FakeTransport().enqueue(json));
    }

    // ------------------------------------------------------------------ Message

    @Test
    void messageResolvesACompleteValue() {
        CampaignMessageInputs inputs = over("{\"referenceCode\":\"tpl-welcome\","
                + "\"variables\":{\"firstName\":\"Ada\"}}").messageInputs("welcome", NO_ANSWERS);
        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.referenceCode()).isEqualTo("tpl-welcome");
        assertThat(inputs.variables().fields()).containsEntry("firstName", "Ada");
    }

    @Test
    void messageFailsClosedOnMissingUnknownReservedAndMalformedValues() {
        assertThat(over("{}").messageInputs("welcome", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"referenceCode\":\"tpl-welcome\",\"variables\":{},\"extra\":1}")
                .messageInputs("welcome", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"referenceCode\":\"tpl-welcome\",\"variables\":{\"timezone\":\"UTC\"}}")
                .messageInputs("welcome", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"referenceCode\":\"has space\",\"variables\":{}}")
                .messageInputs("welcome", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("not json").messageInputs("welcome", NO_ANSWERS).resolved()).isFalse();
    }

    // ------------------------------------------------------------------ Promotion

    @Test
    void promotionResolvesACompleteValue() {
        CampaignPromotionInputs inputs = over("{\"referenceCode\":\"rwd-points\",\"quantity\":3,"
                + "\"governanceCeiling\":100.0}").promotionInputs("points", NO_ANSWERS);
        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.quantity()).isEqualTo(3);
        assertThat(inputs.governanceCeiling()).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    void promotionFailsClosedOnMissingAndOutOfBoundValues() {
        assertThat(over("{\"referenceCode\":\"rwd-points\",\"quantity\":0,\"governanceCeiling\":1}")
                .promotionInputs("points", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"referenceCode\":\"rwd-points\",\"quantity\":1000001,\"governanceCeiling\":1}")
                .promotionInputs("points", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"referenceCode\":\"rwd-points\",\"quantity\":3,\"governanceCeiling\":-1}")
                .promotionInputs("points", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"referenceCode\":\"rwd-points\",\"quantity\":3}")
                .promotionInputs("points", NO_ANSWERS).resolved()).isFalse();
    }

    // ------------------------------------------------------------------ Journey

    private static final String JOURNEY = "{\"nodes\":["
            + "{\"ref\":\"n-1\",\"capabilityCode\":\"flow.start\",\"payload\":{}},"
            + "{\"ref\":\"n-2\",\"capabilityCode\":\"flow.end\",\"payload\":{\"terminal\":\"END\"}}],"
            + "\"edges\":[{\"edgeId\":\"e-1\",\"fromRef\":\"n-1\",\"toRef\":\"n-2\","
            + "\"branchName\":\"DEFAULT\"}]}";

    @Test
    void journeyResolvesACompleteCanonicalGraph() {
        CampaignJourneyInputs inputs = over(JOURNEY).journeyInputs("journey", NO_ANSWERS);
        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.draft().nodes()).hasSize(2);
        assertThat(inputs.draft().edges()).hasSize(1);
        assertThat(inputs.hasSimulation()).isFalse();
    }

    @Test
    void journeyDeclaresTheServerOwnedMessageAndPromotionReferenceScaffolds() {
        String graph = "{\"nodes\":["
                + "{\"ref\":\"n-1\",\"capabilityCode\":\"flow.start\",\"payload\":{}},"
                + "{\"ref\":\"n-2\",\"capabilityCode\":\"flow.message\","
                + "\"payload\":{\"messageLogicalId\":\"campaign-message\"}},"
                + "{\"ref\":\"n-3\",\"capabilityCode\":\"flow.reward\","
                + "\"payload\":{\"promotionLogicalId\":\"campaign-promotion\"}},"
                + "{\"ref\":\"n-4\",\"capabilityCode\":\"flow.end\","
                + "\"payload\":{\"terminal\":\"END\"}}],"
                + "\"edges\":["
                + "{\"edgeId\":\"e-1\",\"fromRef\":\"n-1\",\"toRef\":\"n-2\",\"branchName\":\"DEFAULT\"},"
                + "{\"edgeId\":\"e-2\",\"fromRef\":\"n-2\",\"toRef\":\"n-3\",\"branchName\":\"DEFAULT\"},"
                + "{\"edgeId\":\"e-3\",\"fromRef\":\"n-3\",\"toRef\":\"n-4\",\"branchName\":\"DEFAULT\"}]}";

        CampaignJourneyInputs inputs = over(graph).journeyInputs("journey", NO_ANSWERS);

        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.draft().references())
                .extracting(reference -> reference.ref() + ':' + reference.capabilityCode())
                .containsExactly(
                        "campaign-message:message.template",
                        "campaign-promotion:promotion.reference");
    }

    @Test
    void journeyDeclaresEachServerOwnedChannelMessageReference() {
        String graph = "{\"nodes\":["
                + "{\"ref\":\"n-1\",\"capabilityCode\":\"flow.start\",\"payload\":{}},"
                + "{\"ref\":\"n-2\",\"capabilityCode\":\"flow.message\","
                + "\"payload\":{\"messageLogicalId\":\"campaign-message-wechat\"}},"
                + "{\"ref\":\"n-3\",\"capabilityCode\":\"flow.message\","
                + "\"payload\":{\"messageLogicalId\":\"campaign-message-sms\"}},"
                + "{\"ref\":\"n-4\",\"capabilityCode\":\"flow.end\","
                + "\"payload\":{\"terminal\":\"END\"}}],"
                + "\"edges\":["
                + "{\"edgeId\":\"e-1\",\"fromRef\":\"n-1\",\"toRef\":\"n-2\",\"branchName\":\"DEFAULT\"},"
                + "{\"edgeId\":\"e-2\",\"fromRef\":\"n-2\",\"toRef\":\"n-3\",\"branchName\":\"DEFAULT\"},"
                + "{\"edgeId\":\"e-3\",\"fromRef\":\"n-3\",\"toRef\":\"n-4\",\"branchName\":\"DEFAULT\"}]}";

        CampaignJourneyInputs inputs = over(graph).journeyInputs("journey", NO_ANSWERS);

        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.draft().references())
                .extracting(reference -> reference.ref() + ':' + reference.capabilityCode())
                .containsExactly(
                        "campaign-message-sms:message.template",
                        "campaign-message-wechat:message.template");
    }

    @Test
    void journeyCanonicalizesDeclarationOrderAndFailsClosedOnUnknownKindsDuplicatesAndMalformedEdges() {
        assertThat(over("{}").journeyInputs("journey", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"nodes\":[{\"ref\":\"n\",\"capabilityCode\":\"flow.unknown\",\"payload\":{}}],"
                + "\"edges\":[]}").journeyInputs("journey", NO_ANSWERS).resolved()).isFalse();
        CampaignJourneyInputs reordered = over("{\"nodes\":["
                + "{\"ref\":\"n-b\",\"capabilityCode\":\"flow.start\",\"payload\":{}},"
                + "{\"ref\":\"n-a\",\"capabilityCode\":\"flow.end\",\"payload\":{}}],\"edges\":[]}")
                .journeyInputs("journey", NO_ANSWERS);
        assertThat(reordered.resolved()).isTrue();
        assertThat(reordered.draft().nodes()).extracting(node -> node.ref())
                .containsExactly("n-a", "n-b");
        assertThat(over("{\"nodes\":["
                + "{\"ref\":\"n-a\",\"capabilityCode\":\"flow.start\",\"payload\":{}},"
                + "{\"ref\":\"n-a\",\"capabilityCode\":\"flow.end\",\"payload\":{}}],\"edges\":[]}")
                .journeyInputs("journey", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"nodes\":[{\"ref\":\"n\",\"capabilityCode\":\"flow.start\",\"payload\":{}}],"
                + "\"edges\":[{\"edgeId\":\"e\",\"fromRef\":\"n\",\"toRef\":\"missing\","
                + "\"branchName\":\"DEFAULT\"}]}").journeyInputs("journey", NO_ANSWERS).resolved()).isFalse();
    }

    // ------------------------------------------------------------------ Schedule

    @Test
    void scheduleResolvesTheClosedDeclaration() {
        CampaignScheduleInputs inputs = over("{\"form\":\"DAILY\",\"zoneId\":\"Asia/Shanghai\"," 
                + "\"localTime\":\"09:00\",\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-30\"}")
                .scheduleInputs("daily", NO_ANSWERS);
        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.declaration().fields()).containsEntry("form", "DAILY");
    }

    @Test
    void schedulePromptDeclaresTheExactAcceptedFormsAndShapes() {
        FakeTransport transport = new FakeTransport()
                .enqueue("{\"form\":\"ONCE\",\"zoneId\":\"Asia/Shanghai\"," 
                        + "\"runAt\":\"2026-11-01T09:00:00+08:00\"}");
        DevelopmentSkillInputResolvers resolvers = new DevelopmentSkillInputResolvers(transport);

        assertThat(resolvers.scheduleInputs("one execution", NO_ANSWERS).resolved()).isTrue();
        assertThat(transport.prompts.get(0))
                .contains("IMMEDIATE, ONCE, or DAILY")
                .contains("{\"form\":\"ONCE\"")
                .contains(DevelopmentSkillInputResolvers.SchedulePrompt.VERSION);
    }

    @Test
    void scheduleFailsClosedOnUnknownFieldsWrongShapesAndMissingForm() {
        assertThat(over("{}").scheduleInputs("daily", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"form\":\"DAILY\",\"cron\":\"* * * * *\"}")
                .scheduleInputs("daily", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"form\":7}").scheduleInputs("daily", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"zoneId\":\"Asia/Shanghai\"}")
                .scheduleInputs("daily", NO_ANSWERS).resolved()).isFalse();
    }

    // ------------------------------------------------------------------ Governance

    @Test
    void governanceResolvesTheCompleteBoundedLimitSet() {
        CampaignGovernanceInputs inputs = over("{\"maxRecipients\":1000,\"maxRewardGrants\":100,"
                + "\"maxRewardFaceValue\":25.00,\"currency\":\"CNY\"}")
                .governanceInputs("limits", NO_ANSWERS);
        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.declaration().fields()).containsEntry("currency", "CNY");
    }

    @Test
    void governanceFailsClosedOnMissingOutOfBoundAndMalformedValues() {
        assertThat(over("{}").governanceInputs("limits", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"maxRecipients\":0,\"maxRewardGrants\":100,"
                + "\"maxRewardFaceValue\":25.00,\"currency\":\"CNY\"}")
                .governanceInputs("limits", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"maxRecipients\":1000,\"maxRewardGrants\":100,"
                + "\"maxRewardFaceValue\":25,\"currency\":\"CNY\"}")
                .governanceInputs("limits", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"maxRecipients\":1000,\"maxRewardGrants\":100,"
                + "\"maxRewardFaceValue\":25.00,\"currency\":\"cny\"}")
                .governanceInputs("limits", NO_ANSWERS).resolved()).isFalse();
        assertThat(over("{\"maxRecipients\":1000,\"maxRewardGrants\":100,"
                + "\"maxRewardFaceValue\":25.00,\"currency\":\"CNY\",\"provider\":\"x\"}")
                .governanceInputs("limits", NO_ANSWERS).resolved()).isFalse();
    }

    /**
     * The declared face value is a value a JSON number cannot spell: a number is free to drop the trailing
     * minor unit. The canonical decimal text is therefore read as the same declaration, and must produce the
     * identical payload — a live turn whose natural-language limits were answered with the text form
     * {@code "50.00"} was discarded and the same question re-asked.
     */
    @Test
    void governanceReadsTheCanonicalFaceValueTextAsTheSameDeclarationAsItsNumber() {
        CampaignGovernanceInputs fromText = over("{\"maxRecipients\":1000,\"maxRewardGrants\":500,"
                + "\"maxRewardFaceValue\":\"50.00\",\"currency\":\"CNY\"}")
                .governanceInputs("limits", NO_ANSWERS);
        CampaignGovernanceInputs fromNumber = over("{\"maxRecipients\":1000,\"maxRewardGrants\":500,"
                + "\"maxRewardFaceValue\":50.00,\"currency\":\"CNY\"}")
                .governanceInputs("limits", NO_ANSWERS);
        assertThat(fromText.resolved()).isTrue();
        assertThat(fromNumber.resolved()).isTrue();
        assertThat(fromText.declaration().canonicalText())
                .isEqualTo(fromNumber.declaration().canonicalText());
    }

    @Test
    void governanceReadsAnExactCanonicalTextForEveryDeclaredField() {
        CampaignGovernanceInputs inputs = over("{\"maxRecipients\":\"1000\",\"maxRewardGrants\":\"500\","
                + "\"maxRewardFaceValue\":\"50.0\",\"currency\":\"CNY\"}")
                .governanceInputs("limits", NO_ANSWERS);
        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.declaration().fields()).containsEntry("currency", "CNY");
    }

    @Test
    void governanceRefusesEveryNonCanonicalTextDeclaration() {
        for (String faceValue : List.of("\"+50.00\"", "\"50.00 \"", "\"050.00\"", "\"50.000\"",
                "\"1e3\"", "\"-50.00\"", "\"50,00\"", "\"\"", "\"a\"")) {
            assertThat(over("{\"maxRecipients\":1000,\"maxRewardGrants\":500,"
                    + "\"maxRewardFaceValue\":" + faceValue + ",\"currency\":\"CNY\"}")
                    .governanceInputs("limits", NO_ANSWERS).resolved())
                    .withFailMessage("face value %s must be refused", faceValue)
                    .isFalse();
        }
        for (String count : List.of("\"1_000\"", "\" 1000\"", "\"1000.0\"", "\"+1000\"", "\"01000\"")) {
            assertThat(over("{\"maxRecipients\":" + count + ",\"maxRewardGrants\":500,"
                    + "\"maxRewardFaceValue\":\"50.00\",\"currency\":\"CNY\"}")
                    .governanceInputs("limits", NO_ANSWERS).resolved())
                    .withFailMessage("recipient count %s must be refused", count)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ continuation and prompts

    @Test
    void theClarificationAnswersAreInputsToTheResolverPrompt() {
        FakeTransport transport = new FakeTransport()
                .enqueue("{\"referenceCode\":\"tpl-welcome\",\"variables\":{\"firstName\":\"Ada\"}}");
        DevelopmentSkillInputResolvers resolvers = new DevelopmentSkillInputResolvers(transport);
        resolvers.messageInputs("last 90 days", List.of(
                new CampaignClarificationAnswer("MESSAGE_TEMPLATE_REQUIRED", null, "the welcome template")));
        assertThat(transport.prompts.get(0))
                .contains("the welcome template")
                .contains("MESSAGE_TEMPLATE_REQUIRED")
                .contains(DevelopmentSkillInputResolvers.MessagePrompt.VERSION);
    }

    @Test
    void everySkillHasItsOwnVersionedPromptContract() {
        assertThat(DevelopmentSkillInputResolvers.MessagePrompt.HASH.value()).startsWith("sha256:");
        assertThat(DevelopmentSkillInputResolvers.PromotionPrompt.HASH.value()).startsWith("sha256:");
        assertThat(DevelopmentSkillInputResolvers.JourneyPrompt.HASH.value()).startsWith("sha256:");
        assertThat(DevelopmentSkillInputResolvers.SchedulePrompt.HASH.value()).startsWith("sha256:");
        assertThat(DevelopmentSkillInputResolvers.GovernancePrompt.HASH.value()).startsWith("sha256:");
        assertThat(List.of(
                DevelopmentSkillInputResolvers.MessagePrompt.VERSION,
                DevelopmentSkillInputResolvers.PromotionPrompt.VERSION,
                DevelopmentSkillInputResolvers.JourneyPrompt.VERSION,
                DevelopmentSkillInputResolvers.SchedulePrompt.VERSION,
                DevelopmentSkillInputResolvers.GovernancePrompt.VERSION))
                .doesNotHaveDuplicates();
        assertThat(DevelopmentSkillInputResolvers.JourneyPrompt.TEXT)
                .contains("at most one flow.reward node")
                .contains("may have multiple incoming edges")
                .contains("audience eligibility is evaluated before flow.start")
                .contains("when it is NONE, emit no")
                .contains("derive every flow.condition only from the accepted branchDetail and branchRule");
    }
}
