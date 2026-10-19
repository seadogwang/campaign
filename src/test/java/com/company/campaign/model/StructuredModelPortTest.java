package com.company.campaign.model;

import com.company.campaign.builder.campaignagent.CampaignRoutingRequest;
import com.company.campaign.builder.campaignagent.CampaignTurnPosition;
import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignRoutingModel;
import com.company.campaign.dev.campaignagent.DevelopmentSkillInputResolvers;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35B: the neutral structured-model boundary is the shared architectural contract, and the development
 * compatibility view is a byte-preserving refactor of it.
 *
 * <p>The neutral port lives outside every {@code dev} package, so shared Skill resolution and the production
 * adapter depend on it alone; the development host keeps its own clearly named view and its accepted prompt
 * bytes unchanged.
 */
class StructuredModelPortTest {

    private static final class RecordingTransport implements DevelopmentStructuredModelPort {

        private final Deque<byte[]> queued = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        private RecordingTransport enqueue(byte[] output) {
            queued.add(output);
            return this;
        }

        @Override
        public byte[] invokeStructured(String prompt) {
            prompts.add(prompt);
            return queued.poll();
        }
    }

    @Test
    void theNeutralPortIsNotADevelopmentType() {
        assertThat(StructuredModelPort.class.getPackageName()).isEqualTo("com.company.campaign.model");
        assertThat(StructuredModelPort.class.getPackageName()).doesNotContain("dev");
        assertThat(StructuredModelPort.class.isAssignableFrom(DevelopmentStructuredModelPort.class)).isTrue();
        assertThat(DevelopmentStructuredModelPort.class.isAssignableFrom(StructuredModelPort.class)).isFalse();
    }

    @Test
    void theDevelopmentViewAdaptsTheNeutralRequestToTheExactPromptBytes() {
        RecordingTransport transport = new RecordingTransport().enqueue("{}".getBytes(StandardCharsets.UTF_8));
        byte[] output = ((StructuredModelPort) transport)
                .invokeStructured(new StructuredModelRequest("system", "user", "schema"));
        assertThat(new String(output, StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(transport.prompts).containsExactly("system\nuser\nschema");
    }

    @Test
    void anEmptyPartAddsNoSeparator() {
        RecordingTransport transport = new RecordingTransport().enqueue("{}".getBytes(StandardCharsets.UTF_8));
        ((StructuredModelPort) transport).invokeStructured(StructuredModelRequest.ofUser("only user"));
        assertThat(transport.prompts).containsExactly("only user");
    }

    @Test
    void theNeutralAndDevelopmentRoutingModelsRenderTheSamePrompt() {
        RecordingTransport first = new RecordingTransport().enqueue("{}".getBytes(StandardCharsets.UTF_8));
        RecordingTransport second = new RecordingTransport().enqueue("{}".getBytes(StandardCharsets.UTF_8));
        CampaignRoutingRequest request = new CampaignRoutingRequest(
                "conversation-neutral", "run-neutral", CampaignTurnPosition.INITIAL,
                List.of(new CampaignSkillState(
                        CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.MISSING, null, List.of())),
                List.of("USER: hello"),
                "a bounded utterance");
        new DevelopmentCampaignRoutingModel(first).route(request);
        new StructuredCampaignRoutingModel(second).route(request);
        assertThat(first.prompts).containsExactlyElementsOf(second.prompts);
    }

    @Test
    void theNeutralSkillResolversRenderTheSamePromptAsTheDevelopmentOnes() {
        RecordingTransport first = new RecordingTransport().enqueue("{}".getBytes(StandardCharsets.UTF_8));
        RecordingTransport second = new RecordingTransport().enqueue("{}".getBytes(StandardCharsets.UTF_8));
        new DevelopmentSkillInputResolvers(first).messageInputs("welcome", List.of());
        new StructuredSkillInputResolvers(second).messageInputs("welcome", List.of());
        assertThat(first.prompts).containsExactlyElementsOf(second.prompts);
        assertThat(first.prompts).hasSize(1);
        assertThat(first.prompts.get(0)).contains("Utterance: welcome");
    }
}