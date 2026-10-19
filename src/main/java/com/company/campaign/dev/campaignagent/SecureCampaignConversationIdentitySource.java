package com.company.campaign.dev.campaignagent;

import com.company.campaign.conversation.CampaignConversationIdentity;
import com.company.campaign.conversation.CampaignConversationIdentitySource;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The one cryptographically strong, server-owned identity source of the opt-in local host.
 *
 * <p>It mints bounded, opaque identifiers from a {@link SecureRandom}: neither component embeds a raw user,
 * tenant, program or other trusted value, so an issued identity discloses nothing about the boundary it belongs
 * to. It is deterministic only in shape — a test injects its own source instead of reading this one — and it
 * holds no static state and no clock.
 */
public final class SecureCampaignConversationIdentitySource implements CampaignConversationIdentitySource {

    private static final int IDENTITY_BYTES = 16;
    private static final String CONVERSATION_PREFIX = "conv-";
    private static final String RUN_PREFIX = "run-";

    private final SecureRandom random = new SecureRandom();

    @Override
    public CampaignConversationIdentity next() {
        return new CampaignConversationIdentity(conversationId(), runId());
    }

    private String conversationId() {
        return CONVERSATION_PREFIX + token();
    }

    private String runId() {
        return RUN_PREFIX + token();
    }

    private String token() {
        byte[] bytes = new byte[IDENTITY_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A bounded diagnostic: the source shape only, never an issued value. */
    @Override
    public String toString() {
        return "SecureCampaignConversationIdentitySource[serverOwned=true]";
    }
}