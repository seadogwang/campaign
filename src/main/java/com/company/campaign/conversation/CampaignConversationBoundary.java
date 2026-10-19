package com.company.campaign.conversation;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * The trusted boundary of one conversation-history query.
 *
 * <p>It carries only the trusted tenant, program and user — never a conversation or run identity, and never a
 * fact read from a request body. A history read or list is therefore always scoped to the exact boundary the
 * resolved trusted context owns, so one tenant/program/user can never enumerate or open another's conversations.
 */
public record CampaignConversationBoundary(
        String tenantId,
        String programId,
        String userId
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignConversationBoundary {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
    }

    /** A bounded diagnostic: the component count only, never a trusted identifier. */
    @Override
    public String toString() {
        return "CampaignConversationBoundary[bounded=true]";
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, field,
                    "A conversation history boundary requires bounded identity.");
        }
    }
}