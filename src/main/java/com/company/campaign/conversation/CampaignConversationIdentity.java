package com.company.campaign.conversation;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * One server-owned, opaque Campaign conversation/run identity pair.
 *
 * <p>It is produced only by a host {@link CampaignConversationIdentitySource}: no caller names it, and neither
 * component embeds a raw user, tenant or program value. Both components satisfy the accepted reference
 * identifier contract, so an issued identity is indistinguishable in shape from any other bounded identifier.
 */
public record CampaignConversationIdentity(String conversationId, String runId) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignConversationIdentity {
        requireIdentifier(conversationId, "conversationId");
        requireIdentifier(runId, "runId");
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, field, "An issued identity must be bounded.");
        }
    }

    /** A bounded diagnostic: the identity shape only, never the issued value. */
    @Override
    public String toString() {
        return "CampaignConversationIdentity[issued=true]";
    }
}