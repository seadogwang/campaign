package com.company.campaign.production.provider;

import com.company.campaign.action.ActionProviderException;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.action.ProviderRequest;
import com.company.campaign.action.ProviderResult;
import com.company.campaign.action.RewardProvider;

import java.net.http.HttpClient;
import java.util.Objects;

/**
 * The one real, bounded production {@link RewardProvider} adapter.
 *
 * <p>It mirrors {@link ProductionProviderMessageAdapter} deliberately rather than sharing a wider type: the two
 * accepted SPIs stay separate so neither can grow an operation the other would have to reject, and the action
 * layer never reaches a Provider through an interface that could serve both by accident.
 *
 * <p>It implements exactly the accepted SPI: the owner-scoped capability evidence a submission is gated on, one
 * {@code grant} submission under an explicit business idempotency key, and one {@code queryByKey} reconciliation
 * query that takes nothing but the same key. The endpoint, the credential, the fixed provider id, the fixed owner
 * tenant, the exact API/contract version and the action kind are construction-time facts; no request can choose
 * any of them.
 *
 * <p>A timeout, an interrupted call or a transport failure on submission throws the accepted
 * {@link ActionProviderException}, so the Action machine records an unknown remote outcome and reconciles by the
 * original key before any retry. A submission is never retried inside this adapter.
 */
public final class ProductionProviderRewardAdapter implements RewardProvider {

    private final ProductionProviderTransport transport;

    public ProductionProviderRewardAdapter(
            ProductionProviderProperties properties,
            HttpClient client,
            ProductionProviderBackoff backoff) {
        this.transport = new ProductionProviderTransport(
                ProductionProviderKind.REWARD, properties, client, backoff);
    }

    /** One adapter over the given client and the inert backoff, for a caller that manages its own strategy. */
    public static ProductionProviderRewardAdapter create(
            ProductionProviderProperties properties, HttpClient client) {
        return new ProductionProviderRewardAdapter(properties, client, ProductionProviderBackoff.none());
    }

    @Override
    public ProviderCapability capability(String tenantId) {
        return transport.capability(tenantId);
    }

    @Override
    public ProviderResult grant(ProviderRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        return transport.submit(request, idempotencyKey);
    }

    @Override
    public ProviderResult queryByKey(String idempotencyKey) {
        return transport.query(idempotencyKey);
    }

    /** A bounded diagnostic: only wiring facts and fingerprints, never an endpoint, a credential or a key. */
    @Override
    public String toString() {
        return "ProductionProviderRewardAdapter[" + transport + ']';
    }
}