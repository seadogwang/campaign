package com.company.campaign.builder.segment.testsupport;

import com.company.campaign.builder.segment.SegmentAdapterErrorCodes;
import com.company.campaign.builder.segment.SegmentAdapterException;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test-only Anthropic Messages-compatible model port for the opt-in live smoke test.
 *
 * <p>It exists only under {@code src/test}: it uses the JDK {@link HttpClient} directly (no provider SDK), calls
 * an Anthropic Messages-compatible endpoint derived safely from the configured base URL, bounds every timeout
 * and response size, performs no retry, and keeps the credential only in one {@code Authorization: Bearer}
 * request header. {@code ANTHROPIC_AUTH_TOKEN} is never sent as {@code x-api-key} and never sent twice.
 *
 * <p>The configured base URL, the configured model name and the token are injected constructor values: no
 * endpoint or model value is committed here, and the token is never logged, stored, placed in an exception,
 * report or snapshot, or copied into the URL, query string or body.
 *
 * <p>{@link #buildHttpRequest(SegmentModelRequest)} and {@link #requestBody(SegmentModelRequest)} build the
 * exact wire request without sending it, so a local deterministic test can inspect the headers, URL and body
 * with no network access.
 */
public final class AnthropicCompatibleModelPort implements SegmentIntentModelPort {

    /** The fixed live-smoke probe utterance. */
    public static final String SMOKE_UTTERANCE = "Gold会员";

    /** The Anthropic Messages API version this test-only adapter speaks. */
    public static final String API_VERSION = "2023-06-01";

    /** The maximum accepted response body size, in bytes. */
    public static final long MAX_RESPONSE_BYTES = 65_536;

    /** The header that alone may carry the credential. */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** The header the credential must never be sent in. */
    public static final String API_KEY_HEADER = "x-api-key";

    /**
     * The fixed raw T10 Intent shape required for the smoke utterance {@code Gold会员}: one clause referencing
     * {@code member.tier} with a grounded source span, a candidate hint, a logical expression, empty
     * ambiguity/unsupported lists and every required field. It asks for neither a DSL nor SQL.
     */
    public static final String SMOKE_INTENT_SHAPE =
            "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[{"
                    + "\"clauseId\":\"c1\",\"concept\":\"MEMBER_TIER\",\"definitionKind\":\"ATTRIBUTE\","
                    + "\"negated\":false,\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},"
                    + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"Gold会员\",\"start\":0,\"end\":6},"
                    + "\"candidateHints\":[\"member.tier\"],\"assumptions\":[]}],"
                    + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},"
                    + "\"ambiguities\":[],\"unsupportedRequirements\":[]}";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final String token;
    private final String baseUrl;
    private final String model;
    private final HttpClient client;

    public AnthropicCompatibleModelPort(String token, String baseUrl, String model) {
        this.token = token;
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public byte[] invoke(SegmentModelRequest request) {
        try {
            HttpResponse<byte[]> response =
                    client.send(buildHttpRequest(request), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.PROVIDER_FAILED, "The live model call did not succeed");
            }
            byte[] responseBody = response.body();
            if (responseBody == null || responseBody.length > MAX_RESPONSE_BYTES) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.OUTPUT_OVERSIZED, "The live model response is out of bounds");
            }
            return extractText(responseBody).getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.PROVIDER_FAILED, "The live model call failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.PROVIDER_FAILED, "The live model call was interrupted");
        }
    }

    /**
     * Builds the exact HTTP request without sending it.
     *
     * <p>The single credential header is {@code Authorization: Bearer <token>}; no {@code x-api-key} header is
     * ever added and the credential never reaches the URL, query string or body.
     */
    public HttpRequest buildHttpRequest(SegmentModelRequest request) {
        return HttpRequest.newBuilder(messagesEndpoint(baseUrl))
                .timeout(Duration.ofSeconds(20))
                .header("content-type", "application/json")
                .header("anthropic-version", API_VERSION)
                .header(AUTHORIZATION_HEADER, "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
                .build();
    }

    /** The exact JSON request body for one model request. The configured model is the only model value. */
    public String requestBody(SegmentModelRequest request) {
        return CANONICAL_JSON.writeString(Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(Map.of("role", "user", "content", prompt(request)))));
    }

    /** Derives the Messages endpoint safely from the configured base URL. */
    public static URI messagesEndpoint(String configuredBaseUrl) {
        String base = configuredBaseUrl.endsWith("/")
                ? configuredBaseUrl.substring(0, configuredBaseUrl.length() - 1)
                : configuredBaseUrl;
        String path = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        return URI.create(path);
    }

    private static String extractText(byte[] responseBody) {
        var root = CANONICAL_JSON.readTree(new String(responseBody, StandardCharsets.UTF_8));
        var content = root.path("content");
        if (!content.isArray()) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_MALFORMED, "The live model response has no content");
        }
        for (var block : content) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        throw SegmentAdapterException.invalid(
                SegmentAdapterErrorCodes.OUTPUT_MALFORMED, "The live model response has no text block");
    }

    private static String prompt(SegmentModelRequest request) {
        Map<String, Object> projection = new LinkedHashMap<>();
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (SegmentModelRequest.MetadataProjection definition : request.permittedDefinitions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stableId", definition.stableId());
            entry.put("conceptCodes", definition.conceptCodes());
            entry.put("kind", definition.kind().name());
            entry.put("displayName", definition.displayName());
            entry.put("aliases", definition.aliases());
            entry.put("dataType", definition.dataType() == null ? null : definition.dataType().name());
            entry.put("operators", definition.allowedOperators().stream().map(Enum::name).toList());
            entry.put("enumAliases", definition.enumAliases());
            entry.put("metricFilterDimensions", definition.metricFilterDimensions());
            definitions.add(entry);
        }
        projection.put("permittedDefinitions", definitions);
        return request.instructions()
                + "\nUtterance: " + request.utterance()
                + "\nLocale: " + request.localeTag()
                + "\nintentVersion: " + request.intentSchemaVersion().value()
                + "\nRequired raw JSON shape for this utterance: " + SMOKE_INTENT_SHAPE
                + "\nReturn exactly that one raw JSON object, filling the source span from the utterance:"
                + " no Markdown fence, no prose and no other fields."
                + "\nPermitted Metadata projection: " + CANONICAL_JSON.writeString(projection);
    }
}
