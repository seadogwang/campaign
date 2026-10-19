package com.company.campaign.conversation;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One atomically reserved Campaign conversation identity at revision zero.
 *
 * <p>It pairs the server-issued conversation and run identity with the canonical fingerprint of the create
 * request that reserved it. Replaying the same create key and fingerprint returns this exact reservation, so a
 * retried create cannot mint a second conversation. It carries no trusted tenant/program/user value: the
 * boundary lives only in the key the reservation is stored under.
 */
public record CampaignConversationReservation(
        String conversationId,
        String runId,
        long revision,
        ContentHash requestFingerprint
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignConversationReservation {
        requireIdentifier(conversationId, "conversationId");
        requireIdentifier(runId, "runId");
        if (revision != 0L) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.RESERVATION_INVALID, "revision",
                    "A fresh conversation reservation is always at revision zero.");
        }
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    }

    /** A bounded diagnostic: the revision only, never an identifier or a fingerprint. */
    @Override
    public String toString() {
        return "CampaignConversationReservation[revision=" + revision + ']';
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.RESERVATION_INVALID, field,
                    "A reservation requires a bounded identity.");
        }
    }
}