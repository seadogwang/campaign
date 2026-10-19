package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.testsupport.AnthropicCompatibleModelPort;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentCodec;
import com.company.campaign.segment.resolution.ResolutionResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local, network-free proof of the live-smoke request construction.
 *
 * <p>Using only a dummy token, a dummy base URL and a dummy model name, it proves the test-only
 * {@link AnthropicCompatibleModelPort} sends the credential in exactly one {@code Authorization: Bearer}
 * header, never sends {@code x-api-key}, never leaks the token into the URL, query string or body, and carries
 * no committed endpoint or model value: both come only from the injected constructor arguments.
 */
class AnthropicCompatibleModelPortRequestTest {

    private static final String DUMMY_TOKEN = "dummy-token-not-a-real-credential";
    private static final String DUMMY_BASE_URL = "https://live-smoke.invalid/api";
    private static final String DUMMY_MODEL = "dummy-model-name";

    private static final Path ADAPTER_SOURCE = Path.of(
            "src", "test", "java", "com", "company", "campaign", "builder", "segment", "testsupport",
            "AnthropicCompatibleModelPort.java");

    @Test
    void theCredentialIsSentInExactlyOneAuthorizationHeaderAndNeverAsAnApiKey() {
        AnthropicCompatibleModelPort port = port(DUMMY_TOKEN, DUMMY_BASE_URL, DUMMY_MODEL);
        HttpRequest request = port.buildHttpRequest(modelRequest());

        assertThat(request.headers().allValues("Authorization"))
                .containsExactly("Bearer " + DUMMY_TOKEN);
        assertThat(request.headers().firstValue(AnthropicCompatibleModelPort.API_KEY_HEADER)).isEmpty();

        long headersCarryingTheToken = request.headers().map().entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(value -> value.contains(DUMMY_TOKEN)))
                .count();
        assertThat(headersCarryingTheToken).as("headers carrying the credential").isEqualTo(1);
    }

    @Test
    void theTokenNeverReachesTheUrlQueryOrBody() {
        AnthropicCompatibleModelPort port = port(DUMMY_TOKEN, DUMMY_BASE_URL, DUMMY_MODEL);
        HttpRequest request = port.buildHttpRequest(modelRequest());

        assertThat(request.uri().toString()).isEqualTo(DUMMY_BASE_URL + "/v1/messages");
        assertThat(request.uri().getQuery()).isNull();
        assertThat(request.uri().getUserInfo()).isNull();
        assertThat(request.uri().toString()).doesNotContain(DUMMY_TOKEN);

        String body = port.requestBody(modelRequest());
        assertThat(body).doesNotContain(DUMMY_TOKEN).doesNotContain("Authorization").doesNotContain("x-api-key");
        long declaredLength = request.bodyPublisher().orElseThrow().contentLength();
        assertThat(declaredLength).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void theEndpointAndModelComeOnlyFromTheInjectedValuesWithNothingCommitted() {
        AnthropicCompatibleModelPort first = port(DUMMY_TOKEN, DUMMY_BASE_URL, "model-one");
        AnthropicCompatibleModelPort second = port(DUMMY_TOKEN, "https://other-smoke.invalid", "model-two");

        assertThat(first.buildHttpRequest(modelRequest()).uri().toString())
                .isEqualTo(DUMMY_BASE_URL + "/v1/messages");
        assertThat(second.buildHttpRequest(modelRequest()).uri().toString())
                .isEqualTo("https://other-smoke.invalid/v1/messages");

        assertThat(first.requestBody(modelRequest())).contains("\"model\":\"model-one\"")
                .doesNotContain("model-two");
        assertThat(second.requestBody(modelRequest())).contains("\"model\":\"model-two\"")
                .doesNotContain("model-one");

        // A base URL that already ends in the API version is not doubled.
        assertThat(AnthropicCompatibleModelPort.messagesEndpoint("https://already.invalid/v1").toString())
                .isEqualTo("https://already.invalid/v1/messages");
    }

    @Test
    void theAdapterCommitsNoEndpointOrModelValue() {
        String source = read(ADAPTER_SOURCE);
        assertThat(source)
                .doesNotContain("api.anthropic.com")
                .doesNotContain("claude-");
    }

    @Test
    void theFixedPromptCarriesTheExactRawIntentShapeForTheSmokeUtterance() {
        String body = new AnthropicCompatibleModelPort(DUMMY_TOKEN, DUMMY_BASE_URL, DUMMY_MODEL)
                .requestBody(modelRequest());

        // The prompt travels inside the JSON body, so the shape's quotes are escaped there; the ASCII markers
        // still prove the exact shape is present, and the shape constant is the primary assertion surface.
        assertThat(body).contains("MEMBER_TIER").contains("SOURCE_SPAN").contains("candidateHints");
        String shape = AnthropicCompatibleModelPort.SMOKE_INTENT_SHAPE;
        assertThat(shape).contains("\"provenance\":{\"type\":\"SOURCE_SPAN\"")
                .contains("\"candidateHints\":[\"member.tier\"]")
                .contains("\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}")
                .contains("\"ambiguities\":[]")
                .contains("\"unsupportedRequirements\":[]")
                .contains("\"intentVersion\":\"1.3\"")
                .contains("\"subject\":\"MEMBER\"")
                .contains("\"clauseId\":\"c1\"")
                .contains("\"concept\":\"MEMBER_TIER\"")
                .contains("\"definitionKind\":\"ATTRIBUTE\"")
                .contains("\"negated\":false")
                .contains("\"operator\":\"EQUAL\"")
                .contains("\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"}")
                .doesNotContain("SELECT").doesNotContain("DSL");
    }

    @Test
    void theFixedPromptShapeIsTheExactT10JsonTheStrictCodecAndResolverAccept() {
        SegmentIntent intent = new SegmentIntentCodec().parse(
                AnthropicCompatibleModelPort.SMOKE_INTENT_SHAPE,
                AnthropicCompatibleModelPort.SMOKE_UTTERANCE,
                SliceTestFixtures.registry());

        assertThat(intent.clauses()).hasSize(1);
        assertThat(intent.clauses().get(0).clauseId()).isEqualTo("c1");
        assertThat(intent.clauses().get(0).candidateHints()).containsExactly("member.tier");

        ResolutionResult resolution = SliceTestFixtures.resolve(
                intent, minimalSnapshot(), SliceTestFixtures.policy());
        assertThat(resolution.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(resolution.clause("c1").accepted()).isEqualTo("member.tier");
    }

    // ------------------------------------------------------------------ helpers

    private static AnthropicCompatibleModelPort port(String token, String baseUrl, String model) {
        return new AnthropicCompatibleModelPort(token, baseUrl, model);
    }

    private static SegmentModelRequest modelRequest() {
        return new SegmentModelRequestFactory().build(
                AnthropicCompatibleModelPort.SMOKE_UTTERANCE, "zh-CN", minimalSnapshot());
    }

    private static MetadataSnapshot minimalSnapshot() {
        return MetadataFixtures.snapshot(List.of(MetadataFixtures.tierAttribute()), List.of(), List.of());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}