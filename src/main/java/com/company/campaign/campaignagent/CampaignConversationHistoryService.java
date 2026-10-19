package com.company.campaign.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignAgentSession;
import com.company.campaign.builder.campaignagent.CampaignConversationAgent;
import com.company.campaign.builder.campaignagent.CampaignConversationProjection;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryListResponse;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentHttpErrorCodes;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnCodec;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpResponse;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.conversation.CampaignConversationBoundary;
import com.company.campaign.conversation.CampaignConversationHistoryStore;
import com.company.campaign.conversation.CampaignConversationKey;
import com.company.campaign.conversation.CampaignConversationSnapshot;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The neutral concrete {@link CampaignAgentConversationHistoryService} of this build.
 *
 * <p>It is the read-only twin of {@link CampaignConversationTurnService}: the same trusted-context resolution
 * and the same {@code campaign.edit} requirement, but no turn, no model, no Tool and no mutation. The list is
 * scoped to the caller's own tenant/program/user boundary; the read is addressed by the full trusted key, so a
 * conversation identifier alone can never reach another boundary's session. A conversation that holds no stored
 * session is reported as the same bounded not-found as an absent or cross-boundary conversation, so the route
 * never discloses whether another boundary owns an identity.
 *
 * <p>It lives outside every {@code dev} package, so the production Campaign host runs the same read semantics
 * over its durable store and the development host keeps its clearly named subclass.
 */
public class CampaignConversationHistoryService implements CampaignAgentConversationHistoryService {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final TrustedContextProvider trustedContextProvider;
    private final CampaignConversationHistoryStore historyStore;
    private final CampaignAgentEnvironment environment;
    private final CampaignConversationAgent agent;
    private final CampaignAgentTurnCodec codec = new CampaignAgentTurnCodec();

    public CampaignConversationHistoryService(
            TrustedContextProvider trustedContextProvider,
            CampaignConversationHistoryStore historyStore,
            CampaignAgentEnvironment environment) {
        this.trustedContextProvider = Objects.requireNonNull(trustedContextProvider, "trustedContextProvider");
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.agent = new CampaignConversationAgent(this.environment);
    }

    @Override
    public CampaignAgentConversationHistoryListResponse list(int limit) {
        TrustedExecutionContext context = resolveContext();
        requirePermission(context);
        int bounded = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        return CampaignAgentConversationHistoryListResponse.of(
                historyStore.list(boundaryOf(context), bounded));
    }

    @Override
    public CampaignAgentTurnHttpResponse read(String conversationId, String runId) {
        TrustedExecutionContext context = resolveContext();
        requirePermission(context);
        CampaignConversationKey key = new CampaignConversationKey(
                context.tenantId(), context.programId(), context.userId(),
                requireIdentifier(conversationId, "conversationId"),
                requireIdentifier(runId, "runId"));
        CampaignConversationSnapshot snapshot = historyStore.read(key);
        if (snapshot == null || !snapshot.hasSession()) {
            // An absent, unreserved or cross-boundary conversation is the same bounded absence here.
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.NOT_FOUND,
                    "No conversation exists at this trusted boundary.");
        }
        CampaignAgentSession session =
                CampaignAgentSession.restore(snapshot.assemblyContext(), snapshot.session());
        CampaignConversationProjection projection = agent.inspect(session);
        return codec.projection(projection);
    }

    // ------------------------------------------------------------------ trusted boundary

    private TrustedExecutionContext resolveContext() {
        TrustedExecutionContext context;
        try {
            context = trustedContextProvider.trustedContext();
        } catch (CampaignAgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.UNAUTHENTICATED, "No trusted identity could be resolved.");
        }
        if (context == null || !context.membership().isActive()) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.UNAUTHENTICATED, "No active trusted identity could be resolved.");
        }
        return context;
    }

    private static void requirePermission(TrustedExecutionContext context) {
        if (!context.hasPermission(CampaignPermission.CAMPAIGN_EDIT)) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.FORBIDDEN,
                    "The trusted identity does not hold the permission conversation history requires.");
        }
    }

    private static CampaignConversationBoundary boundaryOf(TrustedExecutionContext context) {
        return new CampaignConversationBoundary(
                context.tenantId(), context.programId(), context.userId());
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.REQUEST_INVALID,
                    "A bounded " + field + " is required.");
        }
        return value;
    }

    /** A bounded diagnostic: the wiring facts only, never a value. */
    @Override
    public String toString() {
        return "CampaignConversationHistoryService[readOnly=true]";
    }
}