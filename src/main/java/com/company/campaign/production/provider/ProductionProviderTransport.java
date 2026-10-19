package com.company.campaign.production.provider;

import com.company.campaign.action.ActionProviderException;
import com.company.campaign.action.ActionSupport;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.action.ProviderRequest;
import com.company.campaign.action.ProviderResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The one bounded transport core both production Provider adapters share.
 *
 * <p>It implements exactly the accepted Provider operations — a capability answer, one submission and one
 * reconciliation query by the same business idempotency key — over the bounded JDK HTTP client with redirects
 * disabled. The endpoint, the credential, the fixed provider id, the fixed owner tenant and the action kind are
 * construction-time facts read from validated host configuration; no request can choose any of them.
 *
 * <p>A submission is sent at most once: a timeout, an interrupted call or a transport failure throws the accepted
 * {@link ActionProviderException}, so the Action machine records an unknown remote outcome and reconciles by the
 * original key before any retry. A reconciliation query may repeat a bounded number of times under the injected
 * backoff, and never submits an effect. Every failure is one bounded, non-disclosing code and no endpoint,
 * credential, tenant, recipient reference, routing pin, Provider reference, payload or raw error text can leave
 * this boundary.
 *
 * <p>Its core logic reads no clock, no random source, no environment and no thread-local; the only wait it can
 * cause is the one the injected backoff strategy performs.
 */
final class ProductionProviderTransport {

    private final ProductionProviderKind kind;
    private final ProductionProviderProperties properties;
    private final ProductionProviderProperties.Adapter settings;
    private final URI submitEndpoint;
    private final HttpClient client;
    private final ProductionProviderBackoff backoff;
    private final ProviderCapability capability;

    ProductionProviderTransport(
            ProductionProviderKind kind,
            ProductionProviderProperties properties,
            HttpClient client,
            ProductionProviderBackoff backoff) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
        this.settings = properties.adapter(kind);
        if (!settings.isEnabled()) {
            throw new ProductionProviderException(
                    ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID,
                    "The production Provider adapter is only constructed from an explicitly enabled configuration.");
        }
        this.submitEndpoint = settings.submitEndpoint(kind);
        this.client = Objects.requireNonNull(client, "client");
        this.backoff = backoff == null ? ProductionProviderBackoff.none() : backoff;
        this.capability = settings.capability(kind.actionKind());
    }

    /**
     * The bounded JDK HTTP client one validated configuration builds.
     *
     * <p>It never follows a redirect and its connect timeout is the configured bound.
     */
    static HttpClient httpClient(ProductionProviderProperties.Adapter settings) {
        return HttpClient.newBuilder()
                .connectTimeout(settings.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ------------------------------------------------------------------ SPI

    /** The fixed capability evidence of this Provider for its own owner, or {@code null} for any other owner. */
    ProviderCapability capability(String tenantId) {
        if (tenantId == null || !tenantId.equals(settings.getOwnerTenantId())) {
            // A Provider states capability evidence only for the one owner its configuration fixes.
            return null;
        }
        return capability;
    }

    /** Submits the one exact request under one exact business idempotency key; never retried here. */
    ProviderResult submit(ProviderRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        if (request.actionKind() != kind.actionKind() || !idempotencyKey.equals(request.idempotencyKey())) {
            throw bounded(ProductionProviderErrorCodes.PROVIDER_REQUEST_INVALID);
        }
        byte[] body = ProductionProviderWireContract.submissionBody(request, idempotencyKey);
        // The documented key header and the body key are proved equal before any transport happens.
        ProductionProviderWireContract.assertHeaderMatchesBody(idempotencyKey, body);
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(submitEndpoint)
                    .timeout(settings.getRequestTimeout())
                    .header("content-type", ProductionProviderWireContract.CONTENT_TYPE)
                    .header("accept", ProductionProviderWireContract.CONTENT_TYPE)
                    .header(ProductionProviderWireContract.KEY_HEADER, idempotencyKey)
                    .header(ProductionProviderWireContract.CONTRACT_HEADER, settings.getApiVersion())
                    .header("authorization", "Bearer " + settings.getCredential())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (RuntimeException failure) {
            // A request that cannot even be constructed out of the validated configuration is out of contract.
            throw bounded(ProductionProviderErrorCodes.PROVIDER_REQUEST_INVALID);
        }
        return exchange(httpRequest, idempotencyKey);
    }

    /** Reconciles one possibly-applied effect by the original key, with a bounded pre-acceptance retry. */
    ProviderResult query(String idempotencyKey) {
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        int attempts = properties.getQueryMaxAttempts();
        ActionProviderException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                backoff.await(attempt);
            }
            try {
                return queryOnce(idempotencyKey);
            } catch (ActionProviderException failure) {
                if (!ProductionProviderErrorCodes.retryable(failure.code())) {
                    throw failure;
                }
                last = failure;
            }
        }
        throw last;
    }

    private ProviderResult queryOnce(String idempotencyKey) {
        // A reconciliation query sends only the encoded key and never a body, so it can never submit an effect.
        HttpRequest httpRequest;
        try {
            URI endpoint = URI.create(submitEndpoint + "/" + encode(idempotencyKey));
            httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(settings.getRequestTimeout())
                    .header("accept", ProductionProviderWireContract.CONTENT_TYPE)
                    .header(ProductionProviderWireContract.CONTRACT_HEADER, settings.getApiVersion())
                    .header("authorization", "Bearer " + settings.getCredential())
                    .GET()
                    .build();
        } catch (RuntimeException failure) {
            throw bounded(ProductionProviderErrorCodes.PROVIDER_REQUEST_INVALID);
        }
        return exchange(httpRequest, idempotencyKey);
    }

    private static String encode(String idempotencyKey) {
        return URLEncoder.encode(idempotencyKey, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ------------------------------------------------------------------ transport

    private ProviderResult exchange(HttpRequest httpRequest, String idempotencyKey) {
        HttpResponse<InputStream> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException failure) {
            throw bounded(ProductionProviderErrorCodes.PROVIDER_TIMEOUT);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw bounded(ProductionProviderErrorCodes.PROVIDER_TRANSPORT_FAILED);
        } catch (IOException failure) {
            throw bounded(ProductionProviderErrorCodes.PROVIDER_TRANSPORT_FAILED);
        } catch (RuntimeException failure) {
            // The bounded client itself refused the request; nothing was observed, so nothing is known.
            throw bounded(ProductionProviderErrorCodes.PROVIDER_TRANSPORT_FAILED);
        }
        if (response.statusCode() == 200) {
            return ProductionProviderWireContract.responseResult(
                    readBounded(response), kind.actionKind(), idempotencyKey);
        }
        close(response);
        throw forStatus(response.statusCode());
    }

    private static void close(HttpResponse<InputStream> response) {
        InputStream body = response.body();
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // Closing an unread body must never change the bounded failure that is already being raised.
            }
        }
    }

    private byte[] readBounded(HttpResponse<InputStream> response) {
        try {
            verifyContentType(response);
        } catch (ActionProviderException failure) {
            close(response);
            throw failure;
        }
        try (InputStream stream = response.body()) {
            if (stream == null) {
                throw bounded(ProductionProviderErrorCodes.PROVIDER_RESPONSE_EMPTY);
            }
            byte[] body = stream.readNBytes(settings.getMaxResponseBytes() + 1);
            if (body.length > settings.getMaxResponseBytes()) {
                throw bounded(ProductionProviderErrorCodes.PROVIDER_RESPONSE_OVERSIZED);
            }
            return body;
        } catch (IOException failure) {
            throw bounded(ProductionProviderErrorCodes.PROVIDER_TRANSPORT_FAILED);
        }
    }

    private static void verifyContentType(HttpResponse<InputStream> response) {
        Optional<String> header = response.headers().firstValue("content-type");
        String value = header.orElse("").toLowerCase(Locale.ROOT);
        if (!value.startsWith(ProductionProviderWireContract.CONTENT_TYPE)) {
            // A stream, a text body and any other type is never a single plain JSON document.
            throw bounded(ProductionProviderErrorCodes.PROVIDER_RESPONSE_CONTENT_TYPE_INVALID);
        }
    }

    private static ActionProviderException forStatus(int status) {
        if (status >= 300 && status < 400) {
            return bounded(ProductionProviderErrorCodes.PROVIDER_REDIRECT_REJECTED);
        }
        if (status == 401 || status == 403) {
            return bounded(ProductionProviderErrorCodes.PROVIDER_AUTHENTICATION_FAILED);
        }
        if (status == 408 || status == 504) {
            return bounded(ProductionProviderErrorCodes.PROVIDER_TIMEOUT);
        }
        if (status == 429) {
            return bounded(ProductionProviderErrorCodes.PROVIDER_RATE_LIMITED);
        }
        if (status >= 500) {
            return bounded(ProductionProviderErrorCodes.PROVIDER_UNAVAILABLE);
        }
        if (status == 400 || status == 422) {
            return bounded(ProductionProviderErrorCodes.PROVIDER_REQUEST_INVALID);
        }
        if (status == 404) {
            return bounded(ProductionProviderErrorCodes.PROVIDER_ENDPOINT_REJECTED);
        }
        return bounded(ProductionProviderErrorCodes.PROVIDER_PROTOCOL_FAILED);
    }

    private static ActionProviderException bounded(String code) {
        return new ActionProviderException(code);
    }

    /** A bounded diagnostic: the wiring facts only, never an endpoint, a credential, an owner or a key. */
    @Override
    public String toString() {
        return "ProductionProviderTransport[kind=" + kind
                + ", contract=" + ProductionProviderProperties.CONTRACT_VERSION
                + ", productionCapable=" + settings.productionCapable()
                + ", providerIdFingerprint=" + ProductionProviderEvidence.fingerprint(settings.getProviderId())
                + ", configurationHash=" + properties.configurationHash(kind) + ']';
    }
}