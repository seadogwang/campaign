package com.company.campaign.production.provider;

import com.company.campaign.action.ActionProviderException;
import com.company.campaign.action.MessageProvider;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.action.ProviderRequest;
import com.company.campaign.action.ProviderResult;

import java.net.http.HttpClient;
import java.util.Objects;

/**
 * The one real, bounded production {@link MessageProvider} adapter.
 *
 * <p>It implements exactly the accepted SPI: the owner-scoped capability evidence a submission is gated on, one
 * {@code send} submission under an explicit business idempotency key, and one {@code queryByKey} reconciliation
 * query that takes nothing but the same key. The endpoint, the credential, the fixed provider id, the fixed owner
 * tenant, the exact API/contract version and the action kind are construction-time facts; no request can choose
 * any of them.
 *
 * <p>A timeout, an interrupted call or a transport failure on submission throws the accepted
 * {@link ActionProviderException}, so the Action machine records an unknown remote outcome and reconciles by the
 * original key before any retry. A submission is never retried inside this adapter. Every failure maps to one
 * bounded, non-disclosing {@link ProductionProviderErrorCodes} code and no credential, endpoint, recipient
 * reference, routing pin, payload or raw error text can leave this boundary.
 *
 * <p>Its declared capability can state production only for a validated HTTPS configuration that explicitly
 * asserts business-key idempotency and reliable query-by-key; capability configuration alone is never live
 * evidence.
 */
public final class ProductionProviderMessageAdapter implements MessageProvider {

    private final ProductionProviderTransport transport;

    public ProductionProviderMessageAdapter(
            ProductionProviderProperties properties,
            HttpClient client,
            ProductionProviderBackoff backoff) {
        this.transport = new ProductionProviderTransport(
                ProductionProviderKind.MESSAGE, properties, client, backoff);
    }

    /** One adapter over the given client and the inert backoff, for a caller that manages its own strategy. */
    public static ProductionProviderMessageAdapter create(
            ProductionProviderProperties properties, HttpClient client) {
        return new ProductionProviderMessageAdapter(properties, client, ProductionProviderBackoff.none());
    }

    @Override
    public ProviderCapability capability(String tenantId) {
        return transport.capability(tenantId);
    }

    @Override
    public ProviderResult send(ProviderRequest request, String idempotencyKey) {
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
        return "ProductionProviderMessageAdapter[" + transport + ']';
    }
}