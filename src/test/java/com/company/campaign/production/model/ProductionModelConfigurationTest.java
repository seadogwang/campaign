package com.company.campaign.production.model;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.model.StructuredModelPort;
import com.company.campaign.production.testsupport.ProductionFailureCodes;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35B: the production structured-model configuration is default-off, fails closed on a missing configuration,
 * publishes exactly one adapter for routing, Skills and Segment, and never reports configuration alone as
 * production evidence.
 *
 * <p>Every runner below drops the ambient process environment, because the host's own
 * {@code CAMPAIGN_PRODUCTION_MODEL_*} variables are ordinary relaxed-binding configuration. A machine that
 * exports them would otherwise silently complete a configuration the test means to be absent, so the fail-closed
 * contract could never be observed. Only the ambient environment is removed; system properties, the runner's own
 * property values and the production binding rules are untouched, so nothing about production fail-closed
 * behavior is weakened.
 */
class ProductionModelConfigurationTest {

    /** A runner whose environment holds only the values this test supplies, never the ambient process ones. */
    private static ApplicationContextRunner isolatedRunner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));
    }

    private static final String[] ENABLED = {
            "campaign.production.enabled=true",
            "campaign.production.model.enabled=true",
    };

    private static final String[] VALID_CONFIGURATION = {
            "campaign.production.enabled=true",
            "campaign.production.model.enabled=true",
            "campaign.production.model.endpoint=https://model.example.invalid",
            "campaign.production.model.allowed-hosts=model.example.invalid",
            "campaign.production.model.model-id=claude-example-model",
            "campaign.production.model.credential=a-credential-that-is-never-printed",
    };

    @Test
    void theDefaultApplicationCreatesNoModelClientAtAll() {
        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(StructuredModelPort.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(ProductionAnthropicModelAdapter.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(ProductionModelEvidence.class)).isEmpty();
                });
    }

    @Test
    void theModelStaysInertWhenTheProductionBoundaryIsOff() {
        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class)
                .withPropertyValues("campaign.production.model.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(StructuredModelPort.class)).isEmpty();
                });
    }

    @Test
    void anEnabledModelWithoutConfigurationFailsClosed() {
        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class)
                .withPropertyValues(ENABLED)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionModelErrorCodes.MODEL_CONFIGURATION_INVALID);
                });
    }

    @Test
    void theEnabledModelPublishesExactlyOneAdapterForRoutingSkillsAndSegment() {
        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class)
                .withPropertyValues(VALID_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(ProductionAnthropicModelAdapter.class)).hasSize(1);
                    Object model = context.getBean(StructuredModelPort.class);
                    assertThat(model).isInstanceOf(SegmentIntentModelPort.class);
                    assertThat(model.toString())
                            .doesNotContain("claude-example-model")
                            .doesNotContain("a-credential-that-is-never-printed")
                            .doesNotContain("model.example.invalid");
                });
    }

    @Test
    void anAmbiguousHostHttpClientFailsClosed() {
        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class, TwoClients.class)
                .withPropertyValues(VALID_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionModelErrorCodes.MODEL_CONFIGURATION_INVALID);
                });
    }

    @Test
    void configurationAloneIsNeverLiveVerified() {
        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class)
                .withPropertyValues(VALID_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionModelEvidence evidence = context.getBean(ProductionModelEvidence.class);
                    assertThat(evidence.liveVerified()).isFalse();
                    assertThat(evidence.lastLiveVerificationStatus())
                            .isEqualTo(ProductionModelEvidenceStatus.NOT_VERIFIED);
                    assertThat(evidence.configurationHash()).isNotNull();
                    assertThat(evidence.modelIdFingerprint()).startsWith("sha256:");
                });
    }

    @Test
    void aHostLiveVerificationOfTheExactConfigurationIsRecorded() {
        ProductionModelProperties same = new ProductionModelProperties();
        same.setEnabled(true);
        same.setEndpoint("https://model.example.invalid");
        same.setAllowedHosts(java.util.List.of("model.example.invalid"));
        same.setModelId("claude-example-model");
        same.setCredential("a-credential-that-is-never-printed");
        ProductionModelLiveVerification verification = new ProductionModelLiveVerification(
                ProductionModelEvidenceStatus.VERIFIED_LIVE,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                same);

        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class)
                .withBean(ProductionModelLiveVerification.class, () -> verification)
                .withPropertyValues(VALID_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProductionModelEvidence evidence = context.getBean(ProductionModelEvidence.class);
                    assertThat(evidence.liveVerified()).isTrue();
                    assertThat(evidence.lastLiveVerificationInstant())
                            .isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
                });
    }

    @Test
    void aHostVerificationOfADifferentConfigurationIsNotTrusted() {
        ProductionModelProperties other = new ProductionModelProperties();
        other.setEnabled(true);
        other.setEndpoint("https://other.example.invalid");
        other.setAllowedHosts(java.util.List.of("other.example.invalid"));
        other.setModelId("claude-example-model");
        other.setCredential("a-credential-that-is-never-printed");
        ProductionModelLiveVerification verification = new ProductionModelLiveVerification(
                ProductionModelEvidenceStatus.VERIFIED_LIVE,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                other);

        isolatedRunner()
                .withUserConfiguration(ProductionModelConfiguration.class)
                .withBean(ProductionModelLiveVerification.class, () -> verification)
                .withPropertyValues(VALID_CONFIGURATION)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionModelEvidence.class).liveVerified()).isFalse();
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