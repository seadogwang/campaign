package com.company.campaign.conversation;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The trusted boundary key of one Campaign conversation create request.
 *
 * <p>It is built from the trusted tenant, program and user plus the bounded Idempotency-Key of the create call.
 * It deliberately carries no conversation or run identity: the server owns those, so a caller can never select
 * the identity it is about to be issued. The idempotency ledger it addresses is scoped to the exact trusted
 * boundary, so one tenant/program/user can never replay or conflict with another's create.
 */
public record CampaignConversationCreateKey(
        String tenantId,
        String programId,
        String userId,
        String idempotencyKey
) {

    /** The declared bound on one create Idempotency-Key. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 96;

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignConversationCreateKey {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        if (idempotencyKey == null || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "idempotencyKey",
                    "A create request requires a bounded Idempotency-Key.");
        }
    }

    /** The canonical bytes of this key, used only to fingerprint it; never published as an identity. */
    public byte[] canonicalBytes() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("tenantId", tenantId);
        material.put("programId", programId);
        material.put("userId", userId);
        material.put("idempotencyKey", idempotencyKey);
        return CANONICAL_JSON.write(material);
    }

    /** The content hash of {@link #canonicalBytes()}. */
    public ContentHash fingerprint() {
        return ContentHash.sha256(canonicalBytes());
    }

    /** A bounded diagnostic: the fingerprint only, never a trusted identifier or the key itself. */
    @Override
    public String toString() {
        return "CampaignConversationCreateKey[fingerprint=" + fingerprint() + ']';
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, field, "A create key requires bounded identity.");
        }
    }
}