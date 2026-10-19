package com.company.campaign.model;

import com.company.campaign.builder.campaignagent.CampaignAgentErrorCodes;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignAgentLimits;
import com.company.campaign.builder.campaignagent.CampaignRoutingModelPort;
import com.company.campaign.builder.campaignagent.CampaignRoutingPromptContract;
import com.company.campaign.builder.campaignagent.CampaignRoutingRequest;

import java.util.List;
import java.util.Objects;

/**
 * The neutral {@link CampaignRoutingModelPort} over the bounded structured model transport.
 *
 * <p>It is a thin, pinned adapter: it renders the one {@link CampaignRoutingPromptContract} prompt for the
 * request, calls the shared transport once, and returns the bounded bytes unmodified. It never parses the model
 * output, never repairs it locally, never selects a route by keyword and never falls back to a canned decision:
 * the returned bytes remain untrusted and continue through the accepted
 * {@link com.company.campaign.builder.campaignagent.CampaignRoutingDecisionDecoder} and the host's own
 * re-judgement.
 *
 * <p>A transport failure is mapped to the existing bounded model-unavailable path with one stable code, and no
 * prompt, output, header or credential is ever logged or attached to the failure. {@link #repair} sends only the
 * distinct bounded repair instruction with the decoder's stable issue codes — never the rejected output.
 *
 * <p>It lives outside every {@code dev} package, so both the development host and the production Campaign host
 * can wire it over their own transport without either depending on the other.
 */
public class StructuredCampaignRoutingModel implements CampaignRoutingModelPort {

    private final StructuredModelPort transport;

    public StructuredCampaignRoutingModel(StructuredModelPort transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public byte[] route(CampaignRoutingRequest request) {
        Objects.requireNonNull(request, "request");
        return invoke(CampaignRoutingPromptContract.routePrompt(request));
    }

    @Override
    public byte[] repair(CampaignRoutingRequest request, List<String> issueCodes) {
        Objects.requireNonNull(request, "request");
        return invoke(CampaignRoutingPromptContract.repairPrompt(request, issueCodes));
    }

    private byte[] invoke(String prompt) {
        byte[] output;
        try {
            output = transport.invokeStructured(StructuredModelRequest.ofUser(prompt));
        } catch (RuntimeException failure) {
            // No provider text, exception type or cause is allowed to escape this boundary.
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_UNAVAILABLE, "The routing model call failed.");
        }
        if (output == null || output.length == 0) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_UNAVAILABLE, "The routing model returned nothing.");
        }
        if (output.length > CampaignAgentLimits.MAX_MODEL_OUTPUT_BYTES) {
            throw CampaignAgentException.of(
                    CampaignAgentErrorCodes.MODEL_OUTPUT_OVERSIZED,
                    "The routing model output exceeded its declared bound.");
        }
        return output;
    }

    /** A bounded diagnostic: the pinned contract only, never a prompt or an output. */
    @Override
    public String toString() {
        return "StructuredCampaignRoutingModel[contract=" + CampaignRoutingPromptContract.VERSION
                + ", hash=" + CampaignRoutingPromptContract.HASH + ']';
    }
}