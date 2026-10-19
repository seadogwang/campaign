package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ProviderOutcome;
import com.company.campaign.action.ProviderRequest;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * T35C1: deterministic, explicitly non-production fixtures for the production Provider adapter tests.
 *
 * <p>Everything here is a loopback scripted server or a fixed literal. No test that uses this support reads a
 * real Provider, a real credential or the developer's machine settings, and a result observed through it is
 * never production evidence and can never satisfy a readiness level.
 */
final class ProductionProviderTestSupport {

    static final String CREDENTIAL = "scripted-credential-that-must-never-leak";
    static final String PROVIDER_ID = "scripted-provider";
    static final String OWNER = "tenant-1";
    static final String KEY = "tenant-1:participant-1:node-1:MESSAGE";
    static final String REWARD_KEY = "tenant-1:participant-1:node-1:REWARD";

    private static final CanonicalJson JSON = new CanonicalJson();

    private ProductionProviderTestSupport() {
    }

    /** One enabled loopback configuration for the given kind, with the conservative test bounds. */
    static ProductionProviderProperties properties(ProductionProviderKind kind, String url) {
        return properties(kind, url, 65_536, 1);
    }

    /** One enabled loopback configuration with explicit response and query bounds. */
    static ProductionProviderProperties properties(
            ProductionProviderKind kind, String url, int maxResponseBytes, int queryMaxAttempts) {
        ProductionProviderProperties properties = new ProductionProviderProperties();
        properties.setQueryMaxAttempts(queryMaxAttempts);
        properties.setInitialBackoff(java.time.Duration.ofMillis(1));
        properties.setMaxBackoff(java.time.Duration.ofMillis(2));
        ProductionProviderProperties.Adapter adapter = properties.adapter(kind);
        adapter.setEnabled(true);
        adapter.setRequireHttps(false);
        adapter.setEndpoint(url);
        adapter.setAllowedHosts(List.of("127.0.0.1"));
        adapter.setProviderId(PROVIDER_ID);
        adapter.setOwnerTenantId(OWNER);
        adapter.setCredential(CREDENTIAL);
        adapter.setMaxResponseBytes(maxResponseBytes);
        adapter.setAssertBusinessKeyIdempotency(true);
        adapter.setAssertReliableQueryByKey(true);
        properties.validate();
        return properties;
    }

    /** One bounded message request over the fixed fixture key. */
    static ProviderRequest messageRequest() {
        return ProviderRequest.of(
                ActionKind.MESSAGE, KEY, "email", "template-1", "template-version-1",
                ContentHash.sha256("template".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null, null, null, "recipient-1", null,
                ContentHash.sha256("message-request".getBytes(StandardCharsets.UTF_8)));
    }

    /** One bounded reward request over the fixed fixture key. */
    static ProviderRequest rewardRequest() {
        return ProviderRequest.of(
                ActionKind.REWARD, REWARD_KEY, null, null, null, null,
                "reward-1", "reward-version-1",
                ContentHash.sha256("reward".getBytes(StandardCharsets.UTF_8)),
                2, "USD", new java.math.BigDecimal("10.00"), "recipient-1", null,
                ContentHash.sha256("reward-request".getBytes(StandardCharsets.UTF_8)));
    }

    /** One strict, accepted response body. */
    static String responseBody(
            ActionKind kind, String key, ProviderOutcome outcome, String providerReference,
            String acceptedInstant, String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", ProductionProviderProperties.CONTRACT_VERSION);
        body.put("actionKind", kind.code());
        body.put("idempotencyKey", key);
        body.put("outcome", outcome.name());
        if (providerReference != null) {
            body.put("providerReference", providerReference);
        }
        if (acceptedInstant != null) {
            body.put("acceptedInstant", acceptedInstant);
        }
        body.put("reasonCode", reasonCode);
        return JSON.writeString(body);
    }

    /** One accepted success over the given key. */
    static String successBody(ActionKind kind, String key) {
        return responseBody(kind, key, ProviderOutcome.SUCCEEDED, "prvref-0001",
                "2026-01-01T00:00:00Z", "PROVIDER_APPLIED");
    }

    /** One strict response with an arbitrary override of one field, for contract-violation tests. */
    static String responseBodyWith(String field, String value, ActionKind kind, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", ProductionProviderProperties.CONTRACT_VERSION);
        body.put("actionKind", kind.code());
        body.put("idempotencyKey", key);
        body.put("outcome", ProviderOutcome.SUCCEEDED.name());
        body.put("providerReference", "prvref-0001");
        body.put("reasonCode", "PROVIDER_APPLIED");
        if (field != null) {
            body.put(field, value);
        }
        return JSON.writeString(body);
    }

    /** One loopback scripted server that replays queued responses and records every request. */
    static ScriptedServer serve(Response... responses) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("The scripted HTTP server could not be started", failure);
        }
        ScriptedServer scripted = new ScriptedServer(server, new ArrayDeque<>(List.of(responses)));
        server.createContext("/", scripted::handle);
        server.start();
        return scripted;
    }

    /** One scripted response: status, content type, raw payload bytes and an optional pre-response delay. */
    record Response(int status, String contentType, byte[] payload, long delayMillis) {

        static Response ok(String body) {
            return new Response(200, "application/json", body.getBytes(StandardCharsets.UTF_8), 0);
        }

        static Response json(int status) {
            return new Response(status, "application/json", new byte[0], 0);
        }

        static Response raw(int status, String contentType, byte[] payload) {
            return new Response(status, contentType, payload, 0);
        }

        /** One response the server deliberately withholds past a bounded request timeout. */
        static Response delayed(long delayMillis) {
            return new Response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), delayMillis);
        }
    }

    /** One recorded request. */
    record Recorded(String method, String path, Map<String, String> headers, String body) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** The decoded key segment of the query route, or null when the path carries none. */
        String pathSuffix() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? null : path.substring(slash + 1);
        }
    }

    /** One running scripted server. */
    static final class ScriptedServer implements AutoCloseable {

        private final HttpServer server;
        private final Deque<Response> queued;
        private final List<Recorded> requests = new ArrayList<>();

        private ScriptedServer(HttpServer server, Deque<Response> queued) {
            this.server = server;
            this.queued = queued;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        List<Recorded> requests() {
            return requests;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
                }
            });
            requests.add(new Recorded(
                    exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), headers, body));

            Response response = queued.poll();
            if (response == null) {
                response = Response.json(500);
            }
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = response.payload();
            if (response.contentType() != null && !response.contentType().isEmpty()) {
                exchange.getResponseHeaders().add("Content-Type", response.contentType());
            }
            exchange.sendResponseHeaders(response.status(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}