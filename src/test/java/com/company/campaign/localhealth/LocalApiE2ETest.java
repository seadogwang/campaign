package com.company.campaign.localhealth;

import com.company.campaign.CampaignApplication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real API end-to-end run: Spring Boot listens on a random real TCP port, and the endpoint is exercised
 * through the JDK {@link HttpClient} over that socket.
 *
 * <p>Nothing here is MockMvc, an in-process dispatcher or a stubbed transport, so the evidence is that an
 * actual HTTP server answered an actual request with the exact bounded contract:
 *
 * <ul>
 *   <li>strict JSON with exactly the expected key sets at every level;</li>
 *   <li>the documented content type;</li>
 *   <li>bounded values for every enumerated field;</li>
 *   <li>deterministic unknown-route handling with no framework internals disclosed;</li>
 *   <li>no credential, JDBC URL, host, identifier, hash or evidence payload anywhere in the bytes.</li>
 * </ul>
 */
@SpringBootTest(classes = CampaignApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalApiE2ETest {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private static final Set<String> STORAGE_MODES = Set.of("IN_MEMORY", "POSTGRESQL");
    private static final Set<String> VERIFICATIONS = Set.of("PASSED", "NOT_RUN", "FAILED");
    private static final Set<String> STATUSES = Set.of("READY", "BLOCKED", "INVALID");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void theHealthEndpointAnswersOverARealListeningPortWithStrictBoundedJson() throws Exception {
        assertThat(port).isPositive();

        HttpResponse<String> response = get("/api/v1/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));

        Map<String, Object> body = objectMapper.readValue(response.body(), OBJECT);
        assertThat(body.keySet()).containsExactlyInAnyOrder("success", "data", "requestId");
        assertThat(body.get("success")).isEqualTo(true);
        assertThat((String) body.get("requestId")).matches("^req-[0-9a-f]{32}$");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.keySet())
                .containsExactlyInAnyOrder("process", "storageMode", "postgresqlVerification", "readiness");
        assertThat(data.get("process")).isEqualTo("UP");
        assertThat(data.get("storageMode")).isIn(STORAGE_MODES);
        assertThat(data.get("postgresqlVerification")).isIn(VERIFICATIONS);

        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) data.get("readiness");
        assertThat(readiness.keySet())
                .containsExactlyInAnyOrder("level", "status", "evaluatedRequirements", "blockers");
        assertThat(readiness.get("level")).isEqualTo("LOCAL_READY");
        assertThat(readiness.get("status")).isIn(STATUSES);

        // The default build has no PostgreSQL configured, so the honest answer is a non-empty blocker list
        // and never an optimistic READY claim.
        assertThat(data.get("storageMode")).isEqualTo("IN_MEMORY");
        assertThat(data.get("postgresqlVerification")).isEqualTo("NOT_RUN");
        assertThat(readiness.get("status")).isEqualTo("BLOCKED");
        assertThat(stringList(readiness.get("blockers")))
                .isNotEmpty()
                .allSatisfy(code -> assertThat(code).matches("^READINESS_[A-Z_]{1,48}$"));

        assertThat(blockersAreBounded(readiness)).isTrue();
    }

    @Test
    void theResponseDisclosesNoCredentialUrlHostIdentifierHashOrPayload() throws Exception {
        String body = get("/api/v1/health").body().toLowerCase(Locale.ROOT);

        assertThat(body)
                .doesNotContain("password")
                .doesNotContain("jdbc:")
                .doesNotContain("postgresql:")
                .doesNotContain("127.0.0.1")
                .doesNotContain("localhost")
                .doesNotContain("sha256:")
                .doesNotContain("tenant")
                .doesNotContain("actor")
                .doesNotContain("exception")
                .doesNotContain("stacktrace");
    }

    @Test
    void anUnknownApiRouteIsNotFoundWithoutFrameworkInternals() throws Exception {
        HttpResponse<String> response = get("/api/v1/this-route-does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        String body = response.body().toLowerCase(Locale.ROOT);
        assertThat(body)
                .doesNotContain("trace")
                .doesNotContain("exception")
                .doesNotContain("stacktrace")
                .doesNotContain("password")
                .doesNotContain("jdbc:");
    }

    @Test
    void repeatedRequestsProduceIdenticalBodies() throws Exception {
        assertThat(get("/api/v1/health").body()).isEqualTo(get("/api/v1/health").body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static List<String> stringList(Object value) {
        assertThat(value).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) value;
        return raw.stream().map(String::valueOf).toList();
    }

    private static boolean blockersAreBounded(Map<String, Object> readiness) {
        List<String> blockers = stringList(readiness.get("blockers"));
        assertThat(stringList(readiness.get("evaluatedRequirements")))
                .containsExactly("LOCAL_POSTGRESQL_INTEGRATION", "LOCAL_ORDERED_MIGRATIONS");
        return blockers.size() <= 32 && blockers.stream().allMatch(code -> code.length() <= 64);
    }
}