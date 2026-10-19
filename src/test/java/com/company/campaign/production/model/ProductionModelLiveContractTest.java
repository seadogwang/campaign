package com.company.campaign.production.model;

import com.company.campaign.model.StructuredModelRequest;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T35B: the environment-gated live model contract test.
 *
 * <p>It is skipped unless an operator supplies the {@code CAMPAIGN_PRODUCTION_MODEL_*} variables for a real
 * endpoint. When it runs it invokes the configured endpoint with one minimal bounded request, validates the
 * strict structured output with the same adapter the host uses, and records only redacted evidence — the
 * provider kind, the model-id fingerprint, the contract version and the configuration hash. It never prints the
 * endpoint, the credential, the model id, the prompt or the raw model output.
 *
 * <p>Passing this test is the only way the exact configuration can become live-verified evidence; the
 * deterministic scripted-server test is explicitly non-production evidence and cannot substitute for it.
 */
class ProductionModelLiveContractTest {

    private static final CanonicalJson JSON = new CanonicalJson();

    private static final String ENDPOINT = "CAMPAIGN_PRODUCTION_MODEL_ENDPOINT";
    private static final String ALLOWED_HOSTS = "CAMPAIGN_PRODUCTION_MODEL_ALLOWED_HOSTS";
    private static final String MODEL_ID = "CAMPAIGN_PRODUCTION_MODEL_MODEL_ID";
    private static final String CREDENTIAL = "CAMPAIGN_PRODUCTION_MODEL_CREDENTIAL";

    @Test
    void theConfiguredEndpointHonoursTheStrictStructuredContract() {
        Map<String, String> environment = System.getenv();
        String endpoint = environment.get(ENDPOINT);
        String hosts = environment.get(ALLOWED_HOSTS);
        String modelId = environment.get(MODEL_ID);
        String credential = environment.get(CREDENTIAL);
        assumeTrue(endpoint != null && !endpoint.isBlank(), "No live production model endpoint is configured");
        assumeTrue(hosts != null && !hosts.isBlank(), "No live production model host allowlist is configured");
        assumeTrue(modelId != null && !modelId.isBlank(), "No live production model id is configured");
        assumeTrue(credential != null && !credential.isBlank(), "No live production model credential is configured");

        ProductionModelProperties properties = new ProductionModelProperties();
        properties.setEnabled(true);
        properties.setEndpoint(endpoint);
        properties.setAllowedHosts(Arrays.stream(hosts.split(",")).map(String::trim).toList());
        properties.setModelId(modelId);
        properties.setCredential(credential);
        properties.validate();

        ProductionAnthropicModelAdapter adapter = new ProductionAnthropicModelAdapter(
                properties, ProductionAnthropicModelAdapter.httpClient(properties),
                ProductionModelBackoff.none());

        byte[] output = adapter.invokeStructured(new StructuredModelRequest(
                "You answer with one JSON document and nothing else.",
                "Return exactly {\"ok\": true}.",
                "{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"type\":\"boolean\"}}}"));

        String document = new String(output, StandardCharsets.UTF_8);
        JsonNode root = JSON.readTree(document);
        assertThat(root.isObject()).isTrue();
        assertThat(root.path("ok").isBoolean()).isTrue();
        assertThat(root.path("ok").asBoolean()).isTrue();

        // Only redacted, fingerprinted evidence is ever produced by a live run.
        ProductionModelEvidence evidence = new ProductionModelEvidence(
                ProductionModelProperties.PROVIDER_KIND,
                properties.modelIdFingerprint().value(),
                ProductionModelProperties.CONTRACT_VERSION,
                properties.configurationHash(),
                ProductionModelEvidenceStatus.VERIFIED_LIVE,
                Instant.now(),
                List.copyOf(properties.getGatePins()));
        assertThat(evidence.liveVerified()).isTrue();
        assertThat(evidence.configurationHash()).isEqualTo(properties.configurationHash());
        assertThat(evidence.toString())
                .doesNotContain(credential)
                .doesNotContain(endpoint)
                .doesNotContain(modelId)
                .doesNotContain(document);
    }
}