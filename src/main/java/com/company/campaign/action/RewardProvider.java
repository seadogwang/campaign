package com.company.campaign.action;

/**
 * The one narrow SPI through which a REWARD action reaches a reward Provider.
 *
 * <p>It mirrors {@link MessageProvider} deliberately rather than sharing a wider type: the two SPIs are
 * separate so neither can grow a message-only or reward-only operation the other would have to reject, and the
 * action layer never talks to a Provider through an interface that could serve both by accident.
 *
 * <p>It has exactly the three operations this layer needs: the owner-scoped capability evidence a submission is
 * gated on, one {@code grant} submission under an explicit business idempotency key, and one {@code queryByKey}
 * reconciliation query that takes nothing but the same key. A grant is never submitted to a Provider that
 * declares no business-key idempotency or no reliable reconciliation, and a possibly-applied grant is always
 * reconciled by key before any retry.
 *
 * <p>{@link #grant} and {@link #queryByKey} are always called with no store lock or transaction held.
 */
public interface RewardProvider {

    /**
     * The bounded capability evidence of this Provider for one owner, or {@code null} when it states none.
     *
     * <p>The caller must re-prove the evidence's owner, action kind and own hash before it trusts a field, and
     * must fail closed when it is missing, false, thrown, owner-mismatched or hash-mismatched.
     */
    ProviderCapability capability(String tenantId);

    /**
     * Grants the one exact request under one exact business idempotency key.
     *
     * <p>The same key must never produce a second remote grant. A timeout or a thrown transport error here
     * means the grant may have applied remotely, so the caller records an unknown outcome rather than a
     * failure.
     */
    ProviderResult grant(ProviderRequest request, String idempotencyKey);

    /**
     * Reconciles one possibly-applied grant by the original business idempotency key.
     *
     * <p>It is always called before any retry, so a retry can never double-grant an effect the first submission
     * already applied.
     */
    ProviderResult queryByKey(String idempotencyKey);
}