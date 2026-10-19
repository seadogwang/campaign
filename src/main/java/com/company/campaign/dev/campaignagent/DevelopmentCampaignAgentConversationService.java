package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHttpResponse;
import com.company.campaign.campaignagent.CampaignConversationCreateService;
import com.company.campaign.conversation.CampaignConversationIdentitySource;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.security.context.TrustedContextProvider;

import java.util.Objects;

/**
 * The development host's concrete {@link com.company.campaign.builder.campaignagent.http.CampaignAgentConversationService}.
 *
 * <p>Its behavior is exactly the accepted development behavior — it is a typed subclass of the neutral
 * {@link CampaignConversationCreateService}, so the production Campaign host runs the same reservation,
 * idempotency and collision-retry logic without importing this development type. It keeps its development name
 * and its original constructor, so the opt-in development host and its tests stay source- and binary-compatible;
 * the bounded constants and the shared create request fingerprint are inherited unchanged.
 *
 * <p>It adds exactly one thing: after the unchanged reservation succeeds it records the server-issued
 * conversation identity — the opaque {@code conversationId}, the {@code runId} and the revision — so the
 * development transcript opens with the creation identity of the conversation it describes. No trusted tenant,
 * program or user value is recorded, and a disabled transcript is completely inert.
 */
public final class DevelopmentCampaignAgentConversationService extends CampaignConversationCreateService {

    private final DevelopmentTranscriptRecorder transcript;

    public DevelopmentCampaignAgentConversationService(
            TrustedContextProvider trustedContextProvider,
            CampaignConversationStore store,
            CampaignConversationIdentitySource identitySource) {
        this(trustedContextProvider, store, identitySource, DevelopmentTranscriptRecorder.disabled());
    }

    public DevelopmentCampaignAgentConversationService(
            TrustedContextProvider trustedContextProvider,
            CampaignConversationStore store,
            CampaignConversationIdentitySource identitySource,
            DevelopmentTranscriptRecorder transcript) {
        super(Objects.requireNonNull(trustedContextProvider, "trustedContextProvider"),
                Objects.requireNonNull(store, "store"),
                Objects.requireNonNull(identitySource, "identitySource"));
        this.transcript = transcript == null ? DevelopmentTranscriptRecorder.disabled() : transcript;
    }

    @Override
    public CampaignAgentConversationHttpResponse create(String idempotencyKey) {
        CampaignAgentConversationHttpResponse response = super.create(idempotencyKey);
        transcript.recordConversationCreated(
                response.conversationId(), response.runId(), response.revision());
        return response;
    }

    /** A bounded diagnostic: the wiring facts only, never a value. */
    @Override
    public String toString() {
        return "DevelopmentCampaignAgentConversationService[developmentOnly=true]";
    }
}