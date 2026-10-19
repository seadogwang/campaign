package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentLimits;
import com.company.campaign.builder.campaignagent.CampaignRoutingModelPort;
import com.company.campaign.builder.campaignagent.CampaignRoutingRequest;

import java.util.List;
import java.util.Objects;

/**
 * The observation decorator that records every raw Campaign routing-model response of the development host.
 *
 * <p>It is a pure pass-through: it delegates both the first-pass {@link #route} and the single bounded
 * {@link #repair} call unchanged and returns exactly the bytes the delegate returned, so it cannot change a
 * route, a Skill selection, a clarification, a revision or a refusal. It records the response <em>after</em> the
 * accepted routing adapter has already applied its own response-size bound, so only a response the accepted
 * boundary accepted is ever stored, and it records only the response document — never the request, the prompt,
 * the routing vocabulary or a model setting.
 *
 * <p>When no conversation is being served on this thread, or when the transcript is disabled, the decorator is
 * completely inert.
 */
public final class DevelopmentTranscriptRoutingModel implements CampaignRoutingModelPort {

    private final CampaignRoutingModelPort delegate;
    private final DevelopmentTranscriptRecorder transcript;

    public DevelopmentTranscriptRoutingModel(
            CampaignRoutingModelPort delegate, DevelopmentTranscriptRecorder transcript) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    @Override
    public byte[] route(CampaignRoutingRequest request) {
        byte[] output = delegate.route(request);
        transcript.recordModelResponse(
                DevelopmentTranscriptRecorder.ROUTING, output, CampaignAgentLimits.MAX_MODEL_OUTPUT_BYTES);
        return output;
    }

    @Override
    public byte[] repair(CampaignRoutingRequest request, List<String> issueCodes) {
        byte[] output = delegate.repair(request, issueCodes);
        transcript.recordModelResponse(
                DevelopmentTranscriptRecorder.ROUTING_SCHEMA_REPAIR, output,
                CampaignAgentLimits.MAX_MODEL_OUTPUT_BYTES);
        return output;
    }

    /** A bounded diagnostic: the wiring facts only, never a prompt or an output. */
    @Override
    public String toString() {
        return "DevelopmentTranscriptRoutingModel[observing=true, " + delegate + ']';
    }
}