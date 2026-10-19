package com.company.campaign.conversation;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The port of the local, non-durable Campaign conversation registry.
 *
 * <p>It is keyed by the trusted tenant/program plus the bounded conversation/run identity, so a conversation is
 * never addressed by its conversation identifier alone. It holds no static map, no background thread, no
 * {@code Instant.now()} and no random identifier: a host supplies the clock and the explicit retention limits.
 *
 * <p><strong>This is not durable storage.</strong> The only implementation this build ships is a development
 * in-memory registry that loses every session and every idempotency entry when the process stops. Durable
 * conversation persistence remains a production blocker.
 */
public interface CampaignConversationStore {

    /**
     * Opens the exclusive transaction of one conversation key.
     *
     * <p>The caller must close the transaction (directly or through {@link AutoCloseable}); the key stays
     * locked until then, so a turn is atomic per key.
     */
    CampaignConversationTransaction open(CampaignConversationKey key);

    /**
     * Atomically reserves one new conversation identity at revision zero for the trusted boundary of
     * {@code createKey}, and returns the effective reservation.
     *
     * <p>Reservation is the only way a conversation comes into existence: a turn never creates one. Three
     * bounded outcomes exist. A replay with the same create key and the same request fingerprint returns the
     * identity already reserved, without minting a second conversation. The same create key with a different
     * fingerprint is refused as a bounded conflict. A requested identity that already exists at this trusted
     * boundary is refused as a bounded collision, so the host retries through its own explicit bounded policy
     * and then fails with a stable code rather than overwriting the existing conversation.
     *
     * <p>The operation is scoped to the trusted boundary shared by {@code createKey} and {@code requestedKey};
     * an inconsistent pair is refused as an invalid reservation rather than stored under a mixed boundary.
     */
    CampaignConversationReservation reserve(
            CampaignConversationCreateKey createKey,
            CampaignConversationKey requestedKey,
            ContentHash requestFingerprint);
}