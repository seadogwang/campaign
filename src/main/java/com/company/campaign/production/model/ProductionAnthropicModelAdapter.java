package com.company.campaign.production.model;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.model.SegmentIntentPrompt;
import com.company.campaign.model.StructuredModelPort;
import com.company.campaign.model.StructuredModelRequest;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one real, bounded production structured-model adapter: an Anthropic-compatible Messages HTTP client.
 *
 * <p>It implements the neutral {@link StructuredModelPort} and the accepted {@link SegmentIntentModelPort}, so
 * one exact instance serves Campaign routing, all six Skill input resolvers and Segment intent extraction.
 * There is no second hidden model, no fallback model and no request-selectable model: the endpoint, the model
 * id, the API version and the credential are fixed at construction from validated host configuration.
 *
 * <p>It sends only the bounded prompt of the injected request and one deterministic parameter set. It never
 * streams, never sends a tool/function declaration, never follows a redirect and never selects a provider
 * object. It rejects a streamed, redirected, non-text, truncated, oversized, malformed or unsafe response, and
 * it maps every failure to one bounded {@link ProductionModelErrorCodes} code that carries no credential,
 * endpoint, host, model id, prompt, schema or raw output.
 *
 * <p>It retries only explicitly retryable pre-acceptance conditions — a connect/transport failure, a timeout, a
 * rate limit and a server error — with the bounded attempt count and the injected {@link ProductionModelBackoff}.
 * A response that was accepted with HTTP 200 is never replayed: a parse or contract failure of that response
 * fails closed immediately.
 *
 * <p>Its core logic reads no clock, no random source, no environment and no thread-local; the only wait it can
 * cause is the one the injected backoff strategy performs.
 */
public final class ProductionAnthropicModelAdapter implements StructuredModelPort, SegmentIntentModelPort {

    /** The one bounded completion-token bound; it is a constant, so a caller cannot widen it. */
    public static final int MAX_OUTPUT_TOKENS = 8_192;

    /** The one bounded deterministic temperature. */
    public static final int TEMPERATURE = 0;

    private static final CanonicalJson JSON = new CanonicalJson();

    /** Control characters that must never appear in a returned text document. */
    private static final Pattern UNSAFE_CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "id", "type", "role", "model", "content", "stop_reason", "stop_sequence", "usage");

    private static final Set<String> TEXT_BLOCK_FIELDS = Set.of("type", "text");

    private static final Set<String> THINKING_BLOCK_FIELDS = Set.of("type", "thinking", "signature");

    private final ProductionModelProperties properties;
    private final URI endpoint;
    private final HttpClient client;
    private final ProductionModelBackoff backoff;

    public ProductionAnthropicModelAdapter(
            ProductionModelProperties properties,
            HttpClient client,
            ProductionModelBackoff backoff) {
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
        this.endpoint = properties.messagesEndpoint();
        this.client = Objects.requireNonNull(client, "client");
        this.backoff = backoff == null ? ProductionModelBackoff.none() : backoff;
    }

    /**
     * The one bounded JDK HTTP client this adapter builds for a validated configuration.
     *
     * <p>It never follows a redirect and its connect timeout is the configured bound.
     */
    public static HttpClient httpClient(ProductionModelProperties properties) {
        properties.validate();
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** One adapter over the given client and the inert backoff, for a caller that manages its own strategy. */
    public static ProductionAnthropicModelAdapter create(
            ProductionModelProperties properties, HttpClient client) {
        return new ProductionAnthropicModelAdapter(properties, client, ProductionModelBackoff.none());
    }

    // ------------------------------------------------------------------ the neutral port

    @Override
    public byte[] invokeStructured(StructuredModelRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.isEmpty()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_REQUEST_INVALID,
                    "The production structured model request carries no bounded text.");
        }
        String prompt = request.prompt();
        if (prompt.getBytes(StandardCharsets.UTF_8).length > properties.getMaxInputBytes()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_INPUT_OVERSIZED,
                    "The production structured model request exceeds its configured input bound.");
        }
        return completionText(send(requestBody(prompt)));
    }

    @Override
    public byte[] invoke(SegmentModelRequest request) {
        Objects.requireNonNull(request, "request");
        return invokeStructured(StructuredModelRequest.ofUser(SegmentIntentPrompt.render(request)));
    }

    // ------------------------------------------------------------------ transport

    private byte[] send(byte[] body) {
        int attempts = properties.getMaxAttempts();
        ProductionModelException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                backoff.await(attempt);
            }
            try {
                return once(body);
            } catch (ProductionModelException failure) {
                if (!failure.retryable()) {
                    throw failure;
                }
                last = failure;
            }
        }
        throw last;
    }

    private byte[] once(byte[] body) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.getRequestTimeout())
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("anthropic-version", properties.getApiVersion())
                .header("x-api-key", properties.getCredential())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException failure) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_TIMEOUT, "The production model request timed out.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_TRANSPORT_FAILED,
                    "The production model request was interrupted.");
        } catch (IOException failure) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_TRANSPORT_FAILED,
                    "The production model transport failed before any accepted response.");
        }
        if (response.statusCode() == 200) {
            return readBounded(response);
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
        verifyContentType(response);
        byte[] body;
        try (InputStream stream = response.body()) {
            if (stream == null) {
                throw ProductionModelException.of(
                        ProductionModelErrorCodes.MODEL_RESPONSE_EMPTY, "The production model response is empty.");
            }
            body = stream.readNBytes(properties.getMaxResponseBytes() + 1);
        } catch (IOException failure) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_TRANSPORT_FAILED,
                    "The production model response could not be read.");
        }
        if (body.length > properties.getMaxResponseBytes()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_OVERSIZED,
                    "The production model response exceeds its configured byte bound.");
        }
        if (body.length == 0) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_EMPTY, "The production model response is empty.");
        }
        return body;
    }

    private static void verifyContentType(HttpResponse<InputStream> response) {
        Optional<String> header = response.headers().firstValue("content-type");
        String value = header.orElse("").toLowerCase(Locale.ROOT);
        if (value.startsWith("text/event-stream")) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                    "The production model response is a stream, which is never accepted.");
        }
        if (!value.startsWith("application/json")) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                    "The production model response is not a plain JSON document.");
        }
    }

    private static ProductionModelException forStatus(int status) {
        if (status >= 300 && status < 400) {
            return ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_REDIRECT_REJECTED,
                    "The production model endpoint answered with a redirect, which is never followed.");
        }
        if (status == 401 || status == 403) {
            return ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_AUTHENTICATION_FAILED,
                    "The production model endpoint refused the configured credential.");
        }
        if (status == 408 || status == 504) {
            return ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_TIMEOUT, "The production model endpoint timed out.");
        }
        if (status == 429) {
            return ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RATE_LIMITED, "The production model endpoint rate-limited.");
        }
        if (status >= 500) {
            return ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_UNAVAILABLE, "The production model endpoint is unavailable.");
        }
        if (status == 400 || status == 422) {
            return ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_REQUEST_INVALID,
                    "The production model endpoint refused the bounded request.");
        }
        if (status == 404) {
            return ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_ENDPOINT_REJECTED,
                    "The production model endpoint does not expose the configured Messages route.");
        }
        return ProductionModelException.of(
                ProductionModelErrorCodes.MODEL_TRANSPORT_FAILED,
                "The production model endpoint answered with an unexpected status.");
    }

    // ------------------------------------------------------------------ deterministic request

    byte[] requestBody(String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModelId());
        payload.put("max_tokens", MAX_OUTPUT_TOKENS);
        payload.put("temperature", TEMPERATURE);
        payload.put("thinking", Map.of("type", "disabled"));
        payload.put("stream", false);
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return JSON.write(payload);
    }

    // ------------------------------------------------------------------ strict response contract

    byte[] completionText(byte[] response) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_NOT_UTF8,
                    "The production model response is not valid UTF-8.");
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (RuntimeException failure) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_MALFORMED,
                    "The production model response is not a well-formed JSON document.");
        }
        if (root == null || !root.isObject()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_MALFORMED,
                    "The production model response is not a JSON object.");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            if (!RESPONSE_FIELDS.contains(names.next())) {
                throw ProductionModelException.of(
                        ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                        "The production model response carries a field this contract does not accept.");
            }
        }
        JsonNode stopReason = root.get("stop_reason");
        if (stopReason != null && stopReason.isTextual() && "max_tokens".equals(stopReason.asText())) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_TRUNCATED,
                    "The production model response was truncated by its own token bound.");
        }
        JsonNode content = root.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                    "The production model response carries no plain text completion.");
        }
        JsonNode textBlock = null;
        for (JsonNode block : content) {
            if (block == null || !block.isObject()) {
                throw ProductionModelException.of(
                        ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                        "The production model response content block is not an object.");
            }
            String type = block.path("type").asText();
            Set<String> allowedFields = "text".equals(type) ? TEXT_BLOCK_FIELDS
                    : "thinking".equals(type) ? THINKING_BLOCK_FIELDS : Set.of();
            if (allowedFields.isEmpty()) {
                throw ProductionModelException.of(
                        ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                        "The production model response carries a content block this contract does not accept.");
            }
            for (Iterator<String> names = block.fieldNames(); names.hasNext(); ) {
                if (!allowedFields.contains(names.next())) {
                    throw ProductionModelException.of(
                            ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                            "The production model response carries a content block this contract does not accept.");
                }
            }
            if ("thinking".equals(type)) {
                if (!block.path("thinking").isTextual()
                        || (block.has("signature") && !block.path("signature").isTextual())) {
                    throw ProductionModelException.of(
                            ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                            "The production model response carries an invalid thinking block.");
                }
                continue;
            }
            if (textBlock != null) {
                throw ProductionModelException.of(
                        ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                        "The production model response carries more than one text completion.");
            }
            textBlock = block;
        }
        if (textBlock == null) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                    "The production model response carries no plain text completion.");
        }
        JsonNode textNode = textBlock.get("text");
        if (textNode == null || !textNode.isTextual()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_NOT_TEXT,
                    "The production model response content block carries no text document.");
        }
        String output = textNode.asText();
        if (output.isEmpty()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_EMPTY,
                    "The production model returned an empty text document.");
        }
        if (UNSAFE_CONTROL.matcher(output).find()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_UNSAFE,
                    "The production model text document contains unsafe control characters.");
        }
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.getMaxResponseBytes()) {
            throw ProductionModelException.of(
                    ProductionModelErrorCodes.MODEL_RESPONSE_OVERSIZED,
                    "The production model text document exceeds its configured byte bound.");
        }
        return bytes;
    }

    /** A bounded diagnostic: the wiring facts only, never an endpoint, a model id or a credential. */
    @Override
    public String toString() {
        return "ProductionAnthropicModelAdapter[providerKind=" + ProductionModelProperties.PROVIDER_KIND
                + ", contract=" + ProductionModelProperties.CONTRACT_VERSION
                + ", apiVersion=" + properties.getApiVersion()
                + ", modelIdFingerprint=" + properties.modelIdFingerprint()
                + ", configurationHash=" + properties.configurationHash() + ']';
    }
}
