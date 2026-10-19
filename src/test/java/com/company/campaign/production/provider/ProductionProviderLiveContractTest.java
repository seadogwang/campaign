package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ProviderOutcome;
import com.company.campaign.action.ProviderRequest;
import com.company.campaign.action.ProviderResult;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T35C1: the environment-gated live Provider contract tests.
 *
 * <p>They are skipped unless an operator supplies the complete {@code CAMPAIGN_PRODUCTION_PROVIDER_*} contract
 * for a real endpoint: the endpoint, the host allowlist, the fixed provider id, the fixed owner tenant id, the
 * credential and the exact request material. When the contract is present, each test performs one intentionally
 * bounded verification: it submits one request under one key, proves same-key idempotent replay produces no
 * second remote effect, reconciles that key with {@code queryByKey}, and records only redacted evidence — the
 * provider kind, the provider-id fingerprint, the action kind, the configuration hash, the capability hash and
 * the exact Gate pins. It never prints the endpoint, the credential, the owner, the recipient, the key or the
 * remote reference.
 *
 * <p>Passing this test is the only way the exact configuration can become live-verified evidence; the
 * deterministic loopback tests are explicitly non-production evidence and cannot substitute for it. Absent the
 * environment contract, the tests skip and the live Provider requirement remains an honest blocker.
 */
class ProductionProviderLiveContractTest {

    private static final String PREFIX = "CAMPAIGN_PRODUCTION_PROVIDER_";
    private static final String MESSAGE_PREFIX = PREFIX + "MESSAGE_";
    private static final String REWARD_PREFIX = PREFIX + "REWARD_";

    // ------------------------------------------------------------------ message

    @Test
    void theConfiguredMessageProviderHonoursIdempotentReplayAndReconciliation() {
        Map<String, String> environment = System.getenv();
        String endpoint = required(environment, MESSAGE_PREFIX + "ENDPOINT");
        String hosts = required(environment, MESSAGE_PREFIX + "ALLOWED_HOSTS");
        String providerId = required(environment, MESSAGE_PREFIX + "PROVIDER_ID");
        String owner = required(environment, MESSAGE_PREFIX + "OWNER_TENANT_ID");
        String credential = required(environment, MESSAGE_PREFIX + "CREDENTIAL");
        String recipient = required(environment, MESSAGE_PREFIX + "RECIPIENT_REF");

        ProductionProviderProperties properties = properties(
                ProductionProviderKind.MESSAGE, endpoint, hosts, providerId, owner, credential);
        ProductionProviderMessageAdapter adapter = ProductionProviderMessageAdapter.create(
                properties, ProductionProviderTransport.httpClient(properties.getMessage()));

        assertThat(adapter.capability(owner)).isNotNull();
        assertThat(adapter.capability(owner).production()).isTrue();

        String key = liveKey(owner, "MESSAGE");
        ProviderRequest request = ProviderRequest.of(
                ActionKind.MESSAGE, key, "live-verification", "live-template", "live-template-version",
                ContentHash.sha256("live-template".getBytes(StandardCharsets.UTF_8)),
                null, null, null, null, null, null, recipient, null,
                ContentHash.sha256("live-message-request".getBytes(StandardCharsets.UTF_8)));

        ProviderResult first = adapter.send(request, key);
        assertThat(first.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(first.providerReference()).isNotBlank();

        // The same key and the same frozen material must never produce a second remote effect.
        ProviderResult replay = adapter.send(request, key);
        assertThat(replay.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(replay.providerReference()).isEqualTo(first.providerReference());

        ProviderResult reconciled = adapter.queryByKey(key);
        assertThat(reconciled.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(reconciled.providerReference()).isEqualTo(first.providerReference());

        ProductionProviderEvidence evidence = record(properties, ProductionProviderKind.MESSAGE);
        assertRedacted(evidence, endpoint, credential, owner, recipient, key, first.providerReference());
    }

    // ------------------------------------------------------------------ reward

    @Test
    void theConfiguredRewardProviderHonoursIdempotentReplayAndReconciliation() {
        Map<String, String> environment = System.getenv();
        String endpoint = required(environment, REWARD_PREFIX + "ENDPOINT");
        String hosts = required(environment, REWARD_PREFIX + "ALLOWED_HOSTS");
        String providerId = required(environment, REWARD_PREFIX + "PROVIDER_ID");
        String owner = required(environment, REWARD_PREFIX + "OWNER_TENANT_ID");
        String credential = required(environment, REWARD_PREFIX + "CREDENTIAL");
        String recipient = required(environment, REWARD_PREFIX + "RECIPIENT_REF");
        String rewardLogicalId = required(environment, REWARD_PREFIX + "REWARD_LOGICAL_ID");
        String rewardVersionId = required(environment, REWARD_PREFIX + "REWARD_VERSION_ID");
        String quantity = required(environment, REWARD_PREFIX + "REWARD_QUANTITY");
        String currency = required(environment, REWARD_PREFIX + "REWARD_CURRENCY");
        String faceValue = required(environment, REWARD_PREFIX + "REWARD_FACE_VALUE");

        ProductionProviderProperties properties = properties(
                ProductionProviderKind.REWARD, endpoint, hosts, providerId, owner, credential);
        ProductionProviderRewardAdapter adapter = ProductionProviderRewardAdapter.create(
                properties, ProductionProviderTransport.httpClient(properties.getReward()));

        assertThat(adapter.capability(owner)).isNotNull();
        assertThat(adapter.capability(owner).production()).isTrue();

        String key = liveKey(owner, "REWARD");
        ProviderRequest request = ProviderRequest.of(
                ActionKind.REWARD, key, null, null, null, null,
                rewardLogicalId, rewardVersionId,
                ContentHash.sha256("live-reward".getBytes(StandardCharsets.UTF_8)),
                Integer.parseInt(quantity), currency, new BigDecimal(faceValue), recipient, null,
                ContentHash.sha256("live-reward-request".getBytes(StandardCharsets.UTF_8)));

        ProviderResult first = adapter.grant(request, key);
        assertThat(first.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(first.providerReference()).isNotBlank();

        ProviderResult replay = adapter.grant(request, key);
        assertThat(replay.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(replay.providerReference()).isEqualTo(first.providerReference());

        ProviderResult reconciled = adapter.queryByKey(key);
        assertThat(reconciled.outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(reconciled.providerReference()).isEqualTo(first.providerReference());

        ProductionProviderEvidence evidence = record(properties, ProductionProviderKind.REWARD);
        assertRedacted(evidence, endpoint, credential, owner, recipient, key, first.providerReference());
    }

    // ------------------------------------------------------------------ helpers

    private static String required(Map<String, String> environment, String variable) {
        String value = environment.get(variable);
        assumeTrue(value != null && !value.isBlank(), "No live production Provider value is configured: " + variable);
        return value;
    }

    private static ProductionProviderProperties properties(
            ProductionProviderKind kind, String endpoint, String hosts, String providerId, String owner,
            String credential) {
        ProductionProviderProperties properties = new ProductionProviderProperties();
        properties.setEvidenceMaxAge(Duration.ofDays(1));
        ProductionProviderProperties.Adapter adapter = properties.adapter(kind);
        adapter.setEnabled(true);
        adapter.setEndpoint(endpoint);
        adapter.setAllowedHosts(Arrays.stream(hosts.split(",")).map(String::trim).toList());
        adapter.setProviderId(providerId);
        adapter.setOwnerTenantId(owner);
        adapter.setCredential(credential);
        adapter.setAssertBusinessKeyIdempotency(true);
        adapter.setAssertReliableQueryByKey(true);
        properties.validate();
        return properties;
    }

    /** A bounded, unique-per-run key, so a prior run's remote effect can never be mistaken for this one's. */
    private static String liveKey(String owner, String actionKind) {
        String instant = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        return "live-verify:" + owner + ":" + instant + ":" + actionKind;
    }

    /** The one redacted evidence record a successful live run publishes. */
    private static ProductionProviderEvidence record(
            ProductionProviderProperties properties, ProductionProviderKind kind) {
        Instant now = Instant.now();
        return new ProductionProviderEvidence(
                kind,
                properties.providerIdFingerprint(kind).value(),
                kind.actionKind(),
                properties.configurationHash(kind),
                ProductionProviderProperties.CONTRACT_VERSION,
                properties.capabilityHash(kind),
                List.copyOf(properties.getGatePins()),
                ProductionProviderVerificationStatus.VERIFIED_LIVE,
                now,
                now.plus(properties.getEvidenceMaxAge()));
    }

    private static void assertRedacted(
            ProductionProviderEvidence evidence, String endpoint, String credential, String owner,
            String recipient, String key, String providerReference) {
        assertThat(evidence.liveVerified()).isTrue();
        assertThat(evidence.expiredAt(Instant.now())).isFalse();
        assertThat(evidence.toString())
                .doesNotContain(endpoint)
                .doesNotContain(credential)
                .doesNotContain(owner)
                .doesNotContain(recipient)
                .doesNotContain(key)
                .doesNotContain(providerReference);
    }
}