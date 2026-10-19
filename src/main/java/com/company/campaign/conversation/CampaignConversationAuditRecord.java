package com.company.campaign.conversation;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One bounded, safe audit record of a Campaign conversation turn.
 *
 * <p>It carries only the trusted tenant/program/user identifiers, the fingerprint of the conversation key, the
 * bounded route/phase/disposition facts, the session revision and the injected instant. It deliberately has no
 * field for the user utterance, a clarification answer, a prompt, a model output, a Proposal, a capability
 * payload or a credential: none of those can be audited, so no audit line can become a channel for them.
 */
public record CampaignConversationAuditRecord(
        ContentHash conversationFingerprint,
        String tenantId,
        String programId,
        String userId,
        String routeKind,
        String phase,
        boolean accepted,
        String code,
        long revision,
        Instant occurredAt
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public CampaignConversationAuditRecord {
        Objects.requireNonNull(conversationFingerprint, "conversationFingerprint");
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        if (routeKind != null && !routeKind.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "routeKind", "An audited route kind must be a code.");
        }
        if (phase != null && !phase.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "phase", "An audited phase must be a code.");
        }
        if (code != null && !MACHINE_CODE.matcher(code).matches()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "code", "An audited code must be a machine code.");
        }
        if (revision < 0L) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, "revision", "An audited revision is non-negative.");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** A bounded diagnostic: the fingerprint, the disposition and the revision only, never a value. */
    @Override
    public String toString() {
        return "CampaignConversationAuditRecord[fingerprint=" + conversationFingerprint
                + ", accepted=" + accepted
                + ", revision=" + revision + ']';
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.KEY_INVALID, field, "An audited identifier must be bounded.");
        }
    }
}