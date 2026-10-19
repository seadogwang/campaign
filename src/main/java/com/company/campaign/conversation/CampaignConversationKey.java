package com.company.campaign.conversation;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The trusted boundary key of one Campaign conversation.
 *
 * <p>It is deliberately built from the trusted tenant, program and user plus the bounded conversation and run
 * identity: a conversation is never addressed by its conversation identifier alone, so one tenant/program/user
 * can never reach another's session by naming its conversation. The two identity components come from the
 * bounded request, but the tenant, program and user come only from the resolved
 * {@link com.company.campaign.security.context.TrustedExecutionContext}.
 */
public record CampaignConversationKey(
        String tenantId,
        String programId,
        String userId,
        String conversationId,
        String runId
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignConversationKey {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        requireIdentifier(conversationId, "conversationId");
        requireIdentifier(runId, "runId");
    }

    /** The canonical bytes of this key, used only to fingerprint it; never published as an identity. */
    public byte[] canonicalBytes() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("tenantId", tenantId);
        material.put("programId", programId);
        material.put("userId", userId);
        material.put("conversationId", conversationId);
        material.put("runId", runId);
        return CANONICAL_JSON.write(material);
    }

    /** The content hash of {@link #canonicalBytes()}. */
    public ContentHash fingerprint() {
        return ContentHash.sha256(canonicalBytes());
    }

    /** A bounded diagnostic: the fingerprint only, never a trusted identifier. */
    @Override
    public String toString() {
        return "CampaignConversationKey[fingerprint=" + fingerprint() + ']';
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new ConversationStoreException(
                    ConversationStoreErrorCodes.KEY_INVALID, field, "A conversation key requires bounded identity.");
        }
    }
}