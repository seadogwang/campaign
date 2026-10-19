package com.company.campaign.action;

/**
 * The one narrow SPI through which a MESSAGE action reaches a message Provider.
 *
 * <p>It has exactly the three operations this layer needs and nothing else: the owner-scoped capability
 * evidence a submission is gated on, one {@code send} submission under an explicit business idempotency key,
 * and one {@code queryByKey} reconciliation query that takes nothing but the same key.
 *
 * <p>There is deliberately no operation to read a template body, a member profile, an inventory, a balance or a
 * credential, and no operation that could be interpreted as a second send under the same key. A Provider that
 * throws, times out, returns no capability evidence or declares no business-key idempotency or no reliable
 * reconciliation fails closed before it is ever submitted to, because a local lock is never a substitute for a
 * remote guarantee.
 *
 * <p>{@link #send} and {@link #queryByKey} are always called with no store lock or transaction held, so a
 * network round trip can never hold a durable lock open.
 */
public interface MessageProvider {

    /**
     * The bounded capability evidence of this Provider for one owner, or {@code null} when it states none.
     *
     * <p>The caller must re-prove the evidence's owner, action kind and own hash before it trusts a field, and
     * must fail closed when it is missing, false, thrown, owner-mismatched or hash-mismatched.
     */
    ProviderCapability capability(String tenantId);

    /**
     * Submits the one exact request under one exact business idempotency key.
     *
     * <p>The same key must never produce a second remote effect. A timeout or a thrown transport error here
     * means the effect may have applied remotely, so the caller records an unknown outcome rather than a
     * failure.
     */
    ProviderResult send(ProviderRequest request, String idempotencyKey);

    /**
     * Reconciles one possibly-applied effect by the original business idempotency key.
     *
     * <p>It is always called before any retry, so a retry can never double-apply an effect the first submission
     * already applied.
     */
    ProviderResult queryByKey(String idempotencyKey);
}