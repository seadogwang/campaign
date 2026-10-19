package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.SegmentAdapterErrorCodes;
import com.company.campaign.builder.segment.SegmentAdapterException;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelLimits;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.model.SegmentIntentPrompt;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Development-only Anthropic Messages adapter; it never logs credentials or raw provider output. */
public final class DevelopmentAnthropicModelPort implements SegmentIntentModelPort, DevelopmentStructuredModelPort {

    public static final String API_VERSION = "2023-06-01";

    private static final CanonicalJson JSON = new CanonicalJson();
    private static final int MAX_STREAM_ENVELOPE_BYTES = 1_048_576;

    private final String token;
    private final String model;
    private final URI endpoint;
    private final HttpClient client;
    private final Consumer<String> traceSink;

    public DevelopmentAnthropicModelPort(DevelopmentModelConfiguration configuration) {
        this(configuration, ignored -> { });
    }

    public DevelopmentAnthropicModelPort(
            DevelopmentModelConfiguration configuration,
            Consumer<String> traceSink) {
        this.token = configuration.token();
        this.model = configuration.model();
        this.endpoint = messagesEndpoint(configuration.baseUrl());
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public byte[] invoke(SegmentModelRequest request) {
        return invokeStructured(prompt(request));
    }

    @Override
    public byte[] invokeStructured(String prompt) {
        try {
            HttpResponse<InputStream> response = client.send(
                    buildHttpRequest(prompt), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.PROVIDER_FAILED, "The development model call did not succeed");
            }
            if (response.body() == null) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.OUTPUT_EMPTY, "The development model response is empty");
            }
            try (InputStream body = response.body()) {
                String output = extractStreamingText(body);
                traceSink.accept(output);
                return output.getBytes(StandardCharsets.UTF_8);
            }
        } catch (CharacterCodingException failure) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_NOT_UTF8,
                    "The development model response is not valid UTF-8");
        } catch (IOException failure) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.PROVIDER_FAILED, "The development model call failed");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.PROVIDER_FAILED, "The development model call was interrupted");
        }
    }

    HttpRequest buildHttpRequest(SegmentModelRequest request) {
        return buildHttpRequest(prompt(request));
    }

    private HttpRequest buildHttpRequest(String prompt) {
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .header("anthropic-version", API_VERSION)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody(prompt)))
                .build();
    }

    byte[] requestBody(SegmentModelRequest request) {
        return requestBody(prompt(request));
    }

    private byte[] requestBody(String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", 8192);
        payload.put("temperature", 0);
        payload.put("thinking", Map.of("type", "disabled"));
        payload.put("stream", true);
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return JSON.write(payload);
    }

    /**
     * The bounded Segment Intent prompt of one safe request, rendered by the neutral shared renderer.
     *
     * <p>The development adapter and the production adapter render the byte-identical prompt from the same
     * accepted request record and the same accepted prompt contract.
     */
    String prompt(SegmentModelRequest request) {
        return SegmentIntentPrompt.render(request);
    }

    static URI messagesEndpoint(String configuredBaseUrl) {
        URI base;
        try {
            base = URI.create(configuredBaseUrl);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("ANTHROPIC_BASE_URL is invalid");
        }
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("ANTHROPIC_BASE_URL must be an HTTPS URL without query or fragment");
        }
        String path = base.getPath() == null ? "" : base.getPath();
        path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        path = path.endsWith("/v1") ? path + "/messages" : path + "/v1/messages";
        try {
            return new URI(base.getScheme(), base.getAuthority(), path, null, null);
        } catch (Exception failure) {
            throw new IllegalArgumentException("ANTHROPIC_BASE_URL is invalid");
        }
    }

    private static String extractText(byte[] responseBody) {
        JsonNode root = JSON.readTree(new String(responseBody, StandardCharsets.UTF_8));
        JsonNode content = root.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (block.path("text").isTextual()) {
                    return block.path("text").asText();
                }
            }
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode text = choices.get(0).path("message").path("content");
            if (text.isTextual()) {
                return text.asText();
            }
        }
        throw SegmentAdapterException.invalid(
                SegmentAdapterErrorCodes.OUTPUT_MALFORMED, "The development model response has no text block");
    }

    static String extractStreamingText(InputStream responseBody) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        StringBuilder text = new StringBuilder();
        StringBuilder nonStreamingBody = new StringBuilder();
        int envelopeBytes = 0;
        boolean sawEvent = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                envelopeBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (envelopeBytes > MAX_STREAM_ENVELOPE_BYTES) {
                    throw SegmentAdapterException.invalid(
                            SegmentAdapterErrorCodes.OUTPUT_OVERSIZED,
                            "The development model stream is out of bounds");
                }
                if (!line.startsWith("data:")) {
                    if (!line.isBlank()) {
                        nonStreamingBody.append(line);
                    }
                    continue;
                }
                sawEvent = true;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode event = JSON.readTree(data);
                appendEventText(event, text);
                if (text.toString().getBytes(StandardCharsets.UTF_8).length
                        > SegmentModelLimits.MAX_MODEL_OUTPUT_BYTES) {
                    throw SegmentAdapterException.invalid(
                            SegmentAdapterErrorCodes.OUTPUT_OVERSIZED,
                            "The development model output is out of bounds");
                }
            }
        }
        if (!sawEvent && !nonStreamingBody.isEmpty()) {
            return extractText(nonStreamingBody.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (text.isEmpty()) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_EMPTY,
                    "The development model stream has no text output");
        }
        return text.toString();
    }

    private static void appendEventText(JsonNode event, StringBuilder text) {
        if ("content_block_start".equals(event.path("type").asText())) {
            JsonNode value = event.path("content_block").path("text");
            if (value.isTextual()) {
                text.append(value.asText());
            }
        }
        if ("content_block_delta".equals(event.path("type").asText())) {
            JsonNode value = event.path("delta").path("text");
            if (value.isTextual()) {
                text.append(value.asText());
            }
        }
        JsonNode choices = event.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode value = choices.get(0).path("delta").path("content");
            if (value.isTextual()) {
                text.append(value.asText());
            }
        }
    }
}
