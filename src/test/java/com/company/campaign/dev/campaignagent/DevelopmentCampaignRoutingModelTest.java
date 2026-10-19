package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.builder.campaignagent.CampaignAgentErrorCodes;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignAgentLimits;
import com.company.campaign.builder.campaignagent.CampaignRoutingPromptContract;
import com.company.campaign.builder.campaignagent.CampaignRoutingRequest;
import com.company.campaign.builder.campaignagent.CampaignTurnPosition;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T32 requirement 2: the development routing model is a thin pinned adapter. It renders the versioned contract,
 * returns the bounded bytes unchanged, keeps repair distinct and code-only, maps a transport failure to the
 * bounded model-unavailable path and leaks nothing through {@code toString}.
 */
class DevelopmentCampaignRoutingModelTest {

    private static final byte[] DECISION = ("{\"routeKind\":\"AUDIENCE_REQUEST\",\"campaignType\":null,"
            + "\"skillIds\":[\""
            + CampaignSkillIds.SEGMENT_RULE_BASED
            + "\"],\"questionCodes\":[],\"assistantCopy\":null,\"options\":[]}").getBytes(StandardCharsets.UTF_8);

    private static final class FakeTransport implements DevelopmentStructuredModelPort {

        private final Deque<byte[]> queued = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();
        private boolean failing;

        private FakeTransport enqueue(byte[] output) {
            queued.add(output);
            return this;
        }

        private FakeTransport failing() {
            this.failing = true;
            return this;
        }

        @Override
        public byte[] invokeStructured(String prompt) {
            prompts.add(prompt);
            if (failing) {
                throw new IllegalStateException("scripted provider failure that must never leak");
            }
            return queued.poll();
        }
    }

    private static CampaignRoutingRequest request() {
        return new CampaignRoutingRequest(
                "conversation-t32", "run-t32", CampaignTurnPosition.INITIAL,
                List.of(new CampaignSkillState(
                        CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.MISSING, null, List.of())),
                List.of("USER: audience please"),
                "who are our best members");
    }

    @Test
    void itReturnsTheBoundedBytesUnchanged() {
        FakeTransport transport = new FakeTransport().enqueue(DECISION);
        byte[] output = new DevelopmentCampaignRoutingModel(transport).route(request());
        assertThat(output).isEqualTo(DECISION);
        assertThat(transport.prompts).hasSize(1);
    }

    @Test
    void itRendersThePinnedContractAndTheBoundedRequestFacts() {
        FakeTransport transport = new FakeTransport().enqueue(DECISION);
        new DevelopmentCampaignRoutingModel(transport).route(request());
        String prompt = transport.prompts.get(0);
        assertThat(prompt)
                .startsWith("Prompt contract version: " + CampaignRoutingPromptContract.VERSION)
                .contains("Prompt contract hash: " + CampaignRoutingPromptContract.HASH.value())
                .contains(CampaignRoutingPromptContract.text())
                .contains(CampaignRoutingPromptContract.VERSION)
                .contains("conversation-t32")
                .contains("who are our best members");
    }

    @Test
    void repairCarriesTheIssueCodesAndNeverTheRejectedOutput() {
        FakeTransport transport = new FakeTransport().enqueue(DECISION).enqueue(DECISION);
        DevelopmentCampaignRoutingModel model = new DevelopmentCampaignRoutingModel(transport);
        model.route(request());
        model.repair(request(), List.of("ROUTING_SHAPE_INVALID"));
        String repair = transport.prompts.get(1);
        assertThat(repair)
                .contains("ROUTING_SHAPE_INVALID")
                .doesNotContain(new String(DECISION, StandardCharsets.UTF_8))
                .doesNotContain("rejected output");
    }

    @Test
    void aTransportFailureMapsToTheBoundedModelUnavailablePath() {
        FakeTransport transport = new FakeTransport().failing();
        CampaignAgentException failure = catchThrowableOfType(
                CampaignAgentException.class, () -> new DevelopmentCampaignRoutingModel(transport).route(request()));
        assertThat(failure.code()).isEqualTo(CampaignAgentErrorCodes.MODEL_UNAVAILABLE);
        assertThat(failure.getMessage()).doesNotContain("scripted provider failure");
    }

    @Test
    void anEmptyOutputFailsClosedAsModelUnavailable() {
        FakeTransport transport = new FakeTransport();
        assertThatThrownBy(() -> new DevelopmentCampaignRoutingModel(transport).route(request()))
                .isInstanceOf(CampaignAgentException.class)
                .extracting(thrown -> ((CampaignAgentException) thrown).code())
                .isEqualTo(CampaignAgentErrorCodes.MODEL_UNAVAILABLE);
    }

    @Test
    void anOversizedOutputFailsClosedAsOversized() {
        FakeTransport transport = new FakeTransport().enqueue(new byte[CampaignAgentLimits.MAX_MODEL_OUTPUT_BYTES + 1]);
        assertThatThrownBy(() -> new DevelopmentCampaignRoutingModel(transport).route(request()))
                .isInstanceOf(CampaignAgentException.class)
                .extracting(thrown -> ((CampaignAgentException) thrown).code())
                .isEqualTo(CampaignAgentErrorCodes.MODEL_OUTPUT_OVERSIZED);
    }

    @Test
    void theDiagnosticPinsTheContractAndLeaksNoPrompt() {
        FakeTransport transport = new FakeTransport().enqueue(DECISION);
        String described = new DevelopmentCampaignRoutingModel(transport).toString();
        assertThat(described)
                .contains(CampaignRoutingPromptContract.VERSION)
                .doesNotContain("best members")
                .doesNotContain("conversation-t32");
    }
}
