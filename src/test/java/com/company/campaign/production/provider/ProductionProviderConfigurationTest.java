package com.company.campaign.production.provider;

import com.company.campaign.action.MessageProvider;
import com.company.campaign.action.RewardProvider;
import com.company.campaign.production.testsupport.ProductionFailureCodes;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35C1: the production Provider configuration is default-off, enables the message and reward adapters
 * independently, fails closed on a missing configuration and never reports configuration alone as production
 * evidence.
 */
class ProductionProviderConfigurationTest {

    private static final String ENDPOINT = "https://provider.example.invalid";
    private static final String HOST = "provider.example.invalid";
    private static final String PROVIDER_ID = "production-message-provider";
    private static final String CREDENTIAL = "a-credential-that-is-never-printed";

    private static final String[] MESSAGE_CONFIGURATION = {
            "campaign.production.enabled=true",
            "campaign.production.provider.message.enabled=true",
            "campaign.production.provider.message.endpoint=" + ENDPOINT,
            "campaign.production.provider.message.allowed-hosts=" + HOST,
            "campaign.production.provider.message.provider-id=" + PROVIDER_ID,
            "campaign.production.provider.message.owner-tenant-id=tenant-1",
            "campaign.production.provider.message.credential=" + CREDENTIAL,
            "campaign.production.provider.message.assert-business-key-idempotency=true",
            "campaign.production.provider.message.assert-reliable-query-by-key=true",
    };

    private static final String[] REWARD_CONFIGURATION = {
            "campaign.production.enabled=true",
            "campaign.production.provider.reward.enabled=true",
            "campaign.production.provider.reward.endpoint=" + ENDPOINT,
            "campaign.production.provider.reward.allowed-hosts=" + HOST,
            "campaign.production.provider.reward.provider-id=production-reward-provider",
            "campaign.production.provider.reward.owner-tenant-id=tenant-1",
            "campaign.production.provider.reward.credential=" + CREDENTIAL,
            "campaign.production.provider.reward.assert-business-key-idempotency=true",
            "campaign.production.provider.reward.assert-reliable-query-by-key=true",
    };

    /** The exact message configuration of {@link #MESSAGE_CONFIGURATION} as a validated value object. */
    private static ProductionProviderProperties messageProperties() {
        ProductionProviderProperties properties = new ProductionProviderProperties();
        ProductionProviderProperties.Adapter adapter = properties.getMessage();
        adapter.setEnabled(true);
        adapter.setEndpoint(ENDPOINT);
        adapter.setAllowedHosts(List.of(HOST));
        adapter.setProviderId(PROVIDER_ID);
        adapter.setOwnerTenantId("tenant-1");
        adapter.setCredential(CREDENTIAL);
        adapter.setAssertBusinessKeyIdempotency(true);
        adapter.setAssertReliableQueryByKey(true);
        return properties;
    }

    @Test
    void theDefaultApplicationCreatesNoProviderAtAll() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(MessageProvider.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(RewardProvider.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(ProductionProviderEvidence.class)).isEmpty();
                });
    }

    @Test
    void theAdaptersStayInertWhenTheProductionBoundaryIsOff() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withPropertyValues("campaign.production.provider.message.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(MessageProvider.class)).isEmpty();
                });
    }

    @Test
    void anEnabledAdapterWithoutConfigurationFailsClosed() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withPropertyValues(
                        "campaign.production.enabled=true",
                        "campaign.production.provider.message.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
                });
    }

    @Test
    void theMessageAdapterEnablesIndependentlyOfTheRewardAdapter() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withPropertyValues(MESSAGE_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(MessageProvider.class)).hasSize(1);
                    assertThat(context.getBeanNamesForType(RewardProvider.class)).isEmpty();
                    assertThat(context.getBean(MessageProvider.class).toString())
                            .doesNotContain(CREDENTIAL)
                            .doesNotContain(ENDPOINT)
                            .doesNotContain(PROVIDER_ID);
                });
    }

    @Test
    void theRewardAdapterEnablesIndependentlyOfTheMessageAdapter() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withPropertyValues(REWARD_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(RewardProvider.class)).hasSize(1);
                    assertThat(context.getBeanNamesForType(MessageProvider.class)).isEmpty();
                });
    }

    @Test
    void bothAdaptersEnableTogetherWithoutAmbiguity() {
        String[] both = new String[MESSAGE_CONFIGURATION.length + REWARD_CONFIGURATION.length - 1];
        System.arraycopy(MESSAGE_CONFIGURATION, 0, both, 0, MESSAGE_CONFIGURATION.length);
        System.arraycopy(REWARD_CONFIGURATION, 1, both, MESSAGE_CONFIGURATION.length,
                REWARD_CONFIGURATION.length - 1);

        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withPropertyValues(both)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(MessageProvider.class)).hasSize(1);
                    assertThat(context.getBeanNamesForType(RewardProvider.class)).hasSize(1);
                });
    }

    @Test
    void anAmbiguousHostHttpClientFailsClosed() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class, TwoClients.class)
                .withPropertyValues(MESSAGE_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
                });
    }

    @Test
    void configurationAloneIsNeverLiveVerified() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withPropertyValues(MESSAGE_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionProviderEvidence evidence = context.getBean(ProductionProviderEvidence.class);
                    assertThat(evidence.liveVerified()).isFalse();
                    assertThat(evidence.lastLiveVerificationStatus())
                            .isEqualTo(ProductionProviderVerificationStatus.NOT_VERIFIED);
                    assertThat(evidence.providerIdFingerprint()).startsWith("sha256:");
                    assertThat(evidence.capabilityHash()).isNotNull();
                });
    }

    @Test
    void aHostLiveVerificationOfTheExactConfigurationIsRecorded() {
        ProductionProviderProperties same = messageProperties();
        ContentHash configurationHash = same.configurationHash(ProductionProviderKind.MESSAGE);
        ContentHash capabilityHash = same.capabilityHash(ProductionProviderKind.MESSAGE);
        ProductionProviderLiveVerification verification = new ProductionProviderLiveVerification(
                ProductionProviderKind.MESSAGE, com.company.campaign.action.ActionKind.MESSAGE,
                ProductionProviderVerificationStatus.VERIFIED_LIVE,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-08T00:00:00Z"),
                configurationHash, capabilityHash);

        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withBean(ProductionProviderLiveVerification.class, () -> verification)
                .withPropertyValues(MESSAGE_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionProviderEvidence evidence = context.getBean(ProductionProviderEvidence.class);
                    assertThat(evidence.liveVerified()).isTrue();
                    assertThat(evidence.lastLiveVerificationInstant())
                            .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
                    assertThat(evidence.expiry()).isEqualTo(Instant.parse("2026-01-08T00:00:00Z"));
                });
    }

    @Test
    void aHostVerificationOfADifferentConfigurationIsNotTrusted() {
        ProductionProviderProperties other = messageProperties();
        other.getMessage().setCredential("a-different-credential");
        ProductionProviderLiveVerification verification = new ProductionProviderLiveVerification(
                ProductionProviderKind.MESSAGE, com.company.campaign.action.ActionKind.MESSAGE,
                ProductionProviderVerificationStatus.VERIFIED_LIVE,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-08T00:00:00Z"),
                other.configurationHash(ProductionProviderKind.MESSAGE),
                other.capabilityHash(ProductionProviderKind.MESSAGE));

        new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderConfiguration.class)
                .withBean(ProductionProviderLiveVerification.class, () -> verification)
                .withPropertyValues(MESSAGE_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionProviderEvidence.class).liveVerified()).isFalse();
                });
    }

    /** Two host HTTP clients are an ambiguous wiring. */
    @Configuration(proxyBeanMethods = false)
    static class TwoClients {

        @Bean
        HttpClient firstClient() {
            return HttpClient.newHttpClient();
        }

        @Bean
        HttpClient secondClient() {
            return HttpClient.newHttpClient();
        }
    }
}