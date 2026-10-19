package com.company.campaign.model;

import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.builder.campaignagent.CampaignAgentErrorCodes;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignAgentLimits;
import com.company.campaign.builder.campaignagent.CampaignRoutingPromptContract;
import com.company.campaign.builder.campaignagent.CampaignRoutingRequest;
import com.company.campaign.builder.campaignagent.CampaignTurnPosition;
import com.company.campaign.builder.campaignagent.CampaignType;
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
 * T40A requirement 6: the neutral routing-model adapter renders the type-aware versioned contract, returns the
 * bounded bytes unmodified and never parses, repairs or defaults a Campaign type itself.
 */
class StructuredCampaignRoutingModelTest {

    private static final byte[] OUTPUT =
            ("{\"routeKind\":\"CAMPAIGN_REQUEST\",\"campaignType\":\"AD_HOC\","
                    + "\"skillIds\":[\"segment.rule-based\"],\"questionCodes\":[],\"assistantCopy\":null,"
                    + "\"options\":[]}").getBytes(StandardCharsets.UTF_8);

    private static final class FakeTransport implements StructuredModelPort {

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
        public byte[] invokeStructured(StructuredModelRequest request) {
            prompts.add(request.prompt());
            if (failing) {
                throw new IllegalStateException("scripted provider failure that must never leak");
            }
            return queued.poll();
        }
    }

    private static CampaignRoutingRequest request() {
        return new CampaignRoutingRequest(
                "conversation-t40a", "run-t40a", CampaignTurnPosition.INITIAL,
                List.of(new CampaignSkillState(
                        CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.MISSING, null, List.of())),
                List.of("USER: build a lifecycle reminder"),
                "build a lifecycle reminder");
    }

    @Test
    void itRendersTheTypeAwarePinnedContractAndReturnsTheBoundedBytesUnchanged() {
        FakeTransport transport = new FakeTransport().enqueue(OUTPUT);
        byte[] output = new StructuredCampaignRoutingModel(transport).route(request());
        assertThat(output).isEqualTo(OUTPUT);
        String prompt = transport.prompts.get(0);
        assertThat(prompt)
                .startsWith("Prompt contract version: " + CampaignRoutingPromptContract.VERSION)
                .contains("Prompt contract hash: " + CampaignRoutingPromptContract.HASH.value())
                .contains(CampaignRoutingPromptContract.text())
                .contains("campaignType")
                .contains("conversation-t40a");
        for (CampaignType type : CampaignType.values()) {
            assertThat(prompt).contains(type.name()).contains(type.description());
        }
    }

    @Test
    void repairCarriesTheIssueCodesAndNeverTheRejectedOutput() {
        FakeTransport transport = new FakeTransport().enqueue(OUTPUT).enqueue(OUTPUT);
        StructuredCampaignRoutingModel model = new StructuredCampaignRoutingModel(transport);
        model.route(request());
        model.repair(request(), List.of("CAMPAIGN_ROUTING_SHAPE_INVALID"));
        String repair = transport.prompts.get(1);
        assertThat(repair)
                .contains("CAMPAIGN_ROUTING_SHAPE_INVALID")
                .doesNotContain(new String(OUTPUT, StandardCharsets.UTF_8))
                .doesNotContain("rejected output");
    }

    @Test
    void aTransportFailureMapsToTheBoundedModelUnavailablePath() {
        FakeTransport transport = new FakeTransport().failing();
        CampaignAgentException failure = catchThrowableOfType(
                CampaignAgentException.class,
                () -> new StructuredCampaignRoutingModel(transport).route(request()));
        assertThat(failure.code()).isEqualTo(CampaignAgentErrorCodes.MODEL_UNAVAILABLE);
        assertThat(failure.getMessage()).doesNotContain("scripted provider failure");
    }

    @Test
    void anEmptyOutputFailsClosedAsModelUnavailable() {
        FakeTransport transport = new FakeTransport();
        assertThatThrownBy(() -> new StructuredCampaignRoutingModel(transport).route(request()))
                .isInstanceOf(CampaignAgentException.class)
                .extracting(thrown -> ((CampaignAgentException) thrown).code())
                .isEqualTo(CampaignAgentErrorCodes.MODEL_UNAVAILABLE);
    }

    @Test
    void anOversizedOutputFailsClosedAsOversized() {
        FakeTransport transport =
                new FakeTransport().enqueue(new byte[CampaignAgentLimits.MAX_MODEL_OUTPUT_BYTES + 1]);
        assertThatThrownBy(() -> new StructuredCampaignRoutingModel(transport).route(request()))
                .isInstanceOf(CampaignAgentException.class)
                .extracting(thrown -> ((CampaignAgentException) thrown).code())
                .isEqualTo(CampaignAgentErrorCodes.MODEL_OUTPUT_OVERSIZED);
    }

    @Test
    void theDiagnosticPinsTheContractAndLeaksNoPrompt() {
        FakeTransport transport = new FakeTransport().enqueue(OUTPUT);
        String described = new StructuredCampaignRoutingModel(transport).toString();
        assertThat(described)
                .contains(CampaignRoutingPromptContract.VERSION)
                .doesNotContain("lifecycle reminder")
                .doesNotContain("conversation-t40a");
    }
}