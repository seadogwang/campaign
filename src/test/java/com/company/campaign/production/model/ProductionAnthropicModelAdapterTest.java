package com.company.campaign.production.model;

import com.company.campaign.model.StructuredModelRequest;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T35B: the deterministic, locally scripted HTTP server contract of the production structured-model adapter.
 *
 * <p>Every observation here is explicitly <strong>non-production evidence</strong>: a loopback scripted server
 * proves the adapter's request/response contract, its bounds, its retry policy and its redaction, and it can
 * never satisfy a readiness level. No test in this class reads a real provider, a real credential or the
 * developer's machine settings.
 */
class ProductionAnthropicModelAdapterTest {

    private static final CanonicalJson JSON = new CanonicalJson();
    private static final String CREDENTIAL = "scripted-credential-that-must-never-leak";
    private static final String MODEL_ID = "scripted-model";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------ request contract

    @Test
    void itSendsOnlyTheBoundedDeterministicRequest() {
        Script script = serve(ok(textBody("{\"a\":1}")));
        byte[] output = adapter(script.url(), 65_536, 262_144, 1)
                .invokeStructured(StructuredModelRequest.of("system line", "user line", "schema line"));

        assertThat(new String(output, StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
        assertThat(script.requests).hasSize(1);
        Recorded recorded = script.requests.get(0);
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/v1/messages");
        assertThat(recorded.header("x-api-key")).isEqualTo(CREDENTIAL);
        assertThat(recorded.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(recorded.header("accept")).isEqualTo("application/json");
        JsonNode body = JSON.readTree(recorded.body());
        assertThat(body.path("model").asText()).isEqualTo(MODEL_ID);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(ProductionAnthropicModelAdapter.MAX_OUTPUT_TOKENS);
        assertThat(body.path("temperature").asInt()).isZero();
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages").isArray()).isTrue();
        assertThat(body.path("messages").size()).isEqualTo(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(0).path("content").asText())
                .isEqualTo("system line\nuser line\nschema line");
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("stream_options")).isFalse();
    }

    // ------------------------------------------------------------------ response contract

    @Test
    void itRejectsARedirectAndNeverFollowsIt() {
        Script script = serve(new Response(302, "text/html", ""));
        ProductionModelException failure = failureOf(script, 65_536, 262_144, 1);
        assertThat(failure.code()).isEqualTo(ProductionModelErrorCodes.MODEL_REDIRECT_REJECTED);
        assertThat(script.requests).hasSize(1);
    }

    @Test
    void itRejectsAStreamedResponse() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                serve(new Response(200, "text/event-stream", "data: {}\n\n")));
    }

    @Test
    void itRejectsAToolUseContentBlock() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                serve(ok("{\"content\":[{\"type\":\"tool_use\",\"id\":\"t\"}]}")));
    }

    @Test
    void itRejectsANonTextContentBlock() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                serve(ok("{\"content\":[{\"type\":\"image\"}]}")));
    }

    @Test
    void itAcceptsOneTextBlockAfterABoundedThinkingBlock() {
        Script script = serve(ok("{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"internal\",\"signature\":\"signed\"},"
                + "{\"type\":\"text\",\"text\":\"{\\\"a\\\":1}\"}]}"));

        byte[] output = adapter(script.url(), 65_536, 262_144, 1)
                .invokeStructured(StructuredModelRequest.ofUser("prompt"));

        assertThat(new String(output, StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void itRejectsMoreThanOneTextBlock() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT, serve(ok(
                "{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}")));
    }

    @Test
    void itRejectsATruncatedResponse() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_TRUNCATED, serve(ok(
                "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}")));
    }

    @Test
    void itRejectsAnUnexpectedResponseField() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT, serve(ok(
                "{\"content\":[{\"type\":\"text\",\"text\":\"a\"}],\"provider_debug\":\"x\"}")));
    }

    @Test
    void itRejectsMalformedJson() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_MALFORMED, serve(ok("{\"content\":[")));
    }

    @Test
    void itRejectsAnEmptyResponse() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_EMPTY, serve(ok("")));
    }

    @Test
    void itRejectsAnOversizedResponse() {
        StringBuilder big = new StringBuilder("{\"content\":[{\"type\":\"text\",\"text\":\"");
        big.append("x".repeat(4_096));
        big.append("\"}]}");
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_OVERSIZED, serve(ok(big.toString())));
    }

    @Test
    void itRejectsUnsafeControlCharacters() {
        assertCode(ProductionModelErrorCodes.MODEL_RESPONSE_UNSAFE,
                serve(ok("{\"content\":[{\"type\":\"text\",\"text\":\"a\\u0007b\"}]}")));
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void itRefusesAnOversizedInputBeforeAnyRequest() {
        Script script = serve(ok(textBody("{}")));
        ProductionModelException failure = catchThrowableOfType(
                ProductionModelException.class,
                () -> adapter(script.url(), 65_536, 1_024, 1)
                        .invokeStructured(StructuredModelRequest.ofUser("x".repeat(2_048))));
        assertThat(failure.code()).isEqualTo(ProductionModelErrorCodes.MODEL_INPUT_OVERSIZED);
        assertThat(script.requests).isEmpty();
    }

    @Test
    void itRefusesAnEmptyRequestBeforeAnyRequest() {
        Script script = serve(ok(textBody("{}")));
        ProductionModelException failure = catchThrowableOfType(
                ProductionModelException.class,
                () -> adapter(script.url(), 65_536, 262_144, 1)
                        .invokeStructured(new StructuredModelRequest("", "", "")));
        assertThat(failure.code()).isEqualTo(ProductionModelErrorCodes.MODEL_REQUEST_INVALID);
        assertThat(script.requests).isEmpty();
    }

    // ------------------------------------------------------------------ retries

    @Test
    void itRetriesOnlyRetryableStatusesWithBoundedAttemptsAndInjectedBackoff() {
        Script script = serve(new Response(500, "application/json", ""),
                new Response(503, "application/json", ""),
                ok(textBody("{}")));
        RecordingBackoff backoff = new RecordingBackoff();
        byte[] output = adapter(script.url(), 65_536, 262_144, 3, backoff)
                .invokeStructured(StructuredModelRequest.ofUser("prompt"));
        assertThat(new String(output, StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(script.requests).hasSize(3);
        assertThat(backoff.attempts).containsExactly(2, 3);
    }

    @Test
    void itFailsClosedAfterBoundedAttempts() {
        Script script = serve(new Response(429, "application/json", ""),
                new Response(429, "application/json", ""),
                new Response(429, "application/json", ""));
        ProductionModelException failure = failureOf(script, 65_536, 262_144, 3);
        assertThat(failure.code()).isEqualTo(ProductionModelErrorCodes.MODEL_RATE_LIMITED);
        assertThat(script.requests).hasSize(3);
    }

    @Test
    void itNeverReplaysAnAcceptedButMalformedResponse() {
        Script script = serve(ok("{\"content\":["), ok(textBody("{}")));
        ProductionModelException failure = failureOf(script, 65_536, 262_144, 3);
        assertThat(failure.code()).isEqualTo(ProductionModelErrorCodes.MODEL_RESPONSE_MALFORMED);
        assertThat(script.requests).hasSize(1);
    }

    @Test
    void itDoesNotRetryAnAuthenticationFailure() {
        Script script = serve(new Response(401, "application/json", ""));
        ProductionModelException failure = failureOf(script, 65_536, 262_144, 3);
        assertThat(failure.code()).isEqualTo(ProductionModelErrorCodes.MODEL_AUTHENTICATION_FAILED);
        assertThat(script.requests).hasSize(1);
    }

    // ------------------------------------------------------------------ redaction

    @Test
    void itNeverDisclosesTheCredentialEndpointOrModelIdInAFailure() {
        Script script = serve(new Response(500, "application/json", ""),
                new Response(500, "application/json", ""),
                new Response(500, "application/json", ""));
        ProductionModelException failure = failureOf(script, 65_536, 262_144, 3);
        assertThat(failure.getMessage())
                .doesNotContain(CREDENTIAL)
                .doesNotContain("127.0.0.1")
                .doesNotContain(MODEL_ID)
                .doesNotContain("prompt");
    }

    @Test
    void itsDiagnosticCarriesOnlyFingerprintsAndPins() {
        Script script = serve(ok(textBody("{}")));
        String described = adapter(script.url(), 65_536, 262_144, 1).toString();
        assertThat(described)
                .contains("anthropic-messages")
                .contains(ProductionModelProperties.CONTRACT_VERSION)
                .doesNotContain(CREDENTIAL)
                .doesNotContain(MODEL_ID)
                .doesNotContain("127.0.0.1");
    }

    // ------------------------------------------------------------------ helpers

    private void assertCode(String expectedCode, Script script) {
        ProductionModelException failure = failureOf(script, 1_024, 262_144, 1);
        assertThat(failure.code()).isEqualTo(expectedCode);
    }

    private ProductionModelException failureOf(
            Script script, int maxResponseBytes, int maxInputBytes, int maxAttempts) {
        return catchThrowableOfType(
                ProductionModelException.class,
                () -> adapter(script.url(), maxResponseBytes, maxInputBytes, maxAttempts)
                        .invokeStructured(StructuredModelRequest.ofUser("prompt")));
    }

    private ProductionAnthropicModelAdapter adapter(
            String url, int maxResponseBytes, int maxInputBytes, int maxAttempts) {
        return adapter(url, maxResponseBytes, maxInputBytes, maxAttempts, ProductionModelBackoff.none());
    }

    private ProductionAnthropicModelAdapter adapter(
            String url, int maxResponseBytes, int maxInputBytes, int maxAttempts,
            ProductionModelBackoff backoff) {
        ProductionModelProperties properties = properties(url, maxResponseBytes, maxInputBytes, maxAttempts);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        return new ProductionAnthropicModelAdapter(properties, client, backoff);
    }

    private static ProductionModelProperties properties(
            String url, int maxResponseBytes, int maxInputBytes, int maxAttempts) {
        ProductionModelProperties properties = new ProductionModelProperties();
        properties.setEnabled(true);
        properties.setRequireHttps(false);
        properties.setEndpoint(url);
        properties.setAllowedHosts(List.of("127.0.0.1"));
        properties.setModelId(MODEL_ID);
        properties.setCredential(CREDENTIAL);
        properties.setMaxResponseBytes(maxResponseBytes);
        properties.setMaxInputBytes(maxInputBytes);
        properties.setMaxAttempts(maxAttempts);
        properties.setInitialBackoff(Duration.ofMillis(1));
        properties.setMaxBackoff(Duration.ofMillis(2));
        properties.validate();
        return properties;
    }

    private static Response ok(String body) {
        return new Response(200, "application/json", body);
    }

    private static String textBody(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("content", List.of(block));
        root.put("stop_reason", "end_turn");
        return JSON.writeString(root);
    }

    private Script serve(Response... responses) {
        HttpServer started;
        try {
            started = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("The scripted HTTP server could not be started", failure);
        }
        Script script = new Script(started, new ArrayDeque<>(List.of(responses)));
        started.createContext("/", script::handle);
        started.start();
        this.server = started;
        return script;
    }

    private record Response(int status, String contentType, String body) {
    }

    private static final class Script {

        private final HttpServer server;
        private final Deque<Response> queued;
        private final List<Recorded> requests = new ArrayList<>();

        private Script(HttpServer server, Deque<Response> queued) {
            this.server = server;
            this.queued = queued;
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
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
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));

            Response response = queued.poll();
            if (response == null) {
                response = new Response(500, "application/json", "");
            }
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            if (response.contentType() != null && !response.contentType().isEmpty()) {
                exchange.getResponseHeaders().add("Content-Type", response.contentType());
            }
            exchange.sendResponseHeaders(response.status(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        }
    }

    private record Recorded(String method, String path, Map<String, String> headers, String body) {

        private String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final class RecordingBackoff implements ProductionModelBackoff {

        private final List<Integer> attempts = new ArrayList<>();

        @Override
        public void await(int attempt) {
            attempts.add(attempt);
        }
    }

    }
