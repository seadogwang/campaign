package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpRequest;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpResponse;
import com.company.campaign.campaignagent.CampaignConversationTurnService;
import com.company.campaign.conversation.CampaignConversationAuditSink;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.security.context.TrustedContextProvider;

import java.time.Clock;
import java.util.Objects;

/**
 * The development host's concrete {@link com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService}.
 *
 * <p>Its behavior is exactly the accepted development behavior — it is a typed subclass of the neutral
 * {@link CampaignConversationTurnService}, so the production Campaign host runs the same turn semantics over the
 * production trusted-context provider, the durable store and the durable audit sink without importing this
 * development type. It keeps its development name and its original constructor, so the opt-in development host
 * and its tests stay source- and binary-compatible.
 *
 * <p>It adds exactly one thing: the development-only conversation transcript. Around the unchanged
 * {@code super.turn} call it records the submitted user turn, opens the run-local transcript scope that lets the
 * raw-model observers attribute a routing, schema-repair, Skill input-resolution or Segment response to this
 * conversation, and records the accepted or refused result. It changes no routing, revision, idempotency,
 * permission or persistence semantics: the scope is a diagnostic correlation only, the recorded values are
 * never read back, and a disabled transcript is completely inert.
 */
public final class DevelopmentCampaignAgentTurnService extends CampaignConversationTurnService {

    /** The stable code recorded for a turn that failed with something other than a bounded agent failure. */
    private static final String UNEXPECTED_FAILURE_CODE = "CAMPAIGN_AGENT_DEVELOPMENT_TURN_FAILED";

    private final DevelopmentTranscriptRecorder transcript;

    public DevelopmentCampaignAgentTurnService(
            TrustedContextProvider trustedContextProvider,
            CampaignAgentEnvironment environment,
            CampaignConversationStore store,
            DevelopmentCampaignAssemblyContextFactory assemblyContextFactory,
            CampaignConversationAuditSink auditSink,
            Clock clock) {
        this(trustedContextProvider, environment, store, assemblyContextFactory, auditSink, clock,
                DevelopmentTranscriptRecorder.disabled());
    }

    public DevelopmentCampaignAgentTurnService(
            TrustedContextProvider trustedContextProvider,
            CampaignAgentEnvironment environment,
            CampaignConversationStore store,
            DevelopmentCampaignAssemblyContextFactory assemblyContextFactory,
            CampaignConversationAuditSink auditSink,
            Clock clock,
            DevelopmentTranscriptRecorder transcript) {
        super(Objects.requireNonNull(trustedContextProvider, "trustedContextProvider"),
                Objects.requireNonNull(environment, "environment"),
                Objects.requireNonNull(store, "store"),
                Objects.requireNonNull(assemblyContextFactory, "assemblyContextFactory"),
                auditSink,
                Objects.requireNonNull(clock, "clock"));
        this.transcript = transcript == null ? DevelopmentTranscriptRecorder.disabled() : transcript;
    }

    @Override
    public CampaignAgentTurnHttpResponse turn(CampaignAgentTurnHttpRequest request, String idempotencyKey) {
        if (request == null) {
            // The neutral service owns the null contract; the transcript never runs ahead of it.
            return super.turn(request, idempotencyKey);
        }
        String conversationId = request.conversationId();
        String runId = request.runId();
        transcript.recordTurnSubmitted(
                conversationId, runId, request.expectedRevision(), request.message(), request.answers());
        try (DevelopmentTranscriptScope ignored = DevelopmentTranscriptScope.open(conversationId, runId)) {
            CampaignAgentTurnHttpResponse response;
            try {
                response = super.turn(request, idempotencyKey);
            } catch (RuntimeException failure) {
                // Only the bounded machine code is recorded: never an exception type, message, cause or trace.
                transcript.recordTurnFailure(conversationId, runId, codeOf(failure));
                throw failure;
            }
            transcript.recordTurnResult(response);
            return response;
        }
    }

    private static String codeOf(RuntimeException failure) {
        return failure instanceof CampaignAgentException bounded ? bounded.code() : UNEXPECTED_FAILURE_CODE;
    }

    /** A bounded diagnostic: the wiring facts only, never a value. */
    @Override
    public String toString() {
        return "DevelopmentCampaignAgentTurnService[developmentOnly=true]";
    }
}