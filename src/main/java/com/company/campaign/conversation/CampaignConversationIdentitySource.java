package com.company.campaign.conversation;

/**
 * The narrow, host-injected source of fresh Campaign conversation/run identities.
 *
 * <p>Only the host supplies it, and only main wiring may use a cryptographically strong implementation; a test
 * injects a deterministic one so the issued identity is reproducible. It is deliberately narrow: it generates
 * one opaque identity pair and can name no tenant, program, user, permission or other trusted fact.
 */
@FunctionalInterface
public interface CampaignConversationIdentitySource {

    /** One fresh, opaque, bounded, server-owned conversation/run identity pair. */
    CampaignConversationIdentity next();
}