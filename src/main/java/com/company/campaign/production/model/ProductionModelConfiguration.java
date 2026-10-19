package com.company.campaign.production.model;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

/**
 * The one opt-in configuration of the default-off production structured-model adapter.
 *
 * <p>It is inert unless the host sets <em>both</em> {@code campaign.production.enabled=true} and
 * {@code campaign.production.model.enabled=true}. When either is off — the default — the application creates no
 * HTTP client, no adapter and no evidence bean at all, so the accepted T35A1 identity boundary and the T35A2
 * durable audit ledger can be enabled on their own.
 *
 * <p>When it is on it publishes exactly one adapter over the validated configuration, the one bounded JDK HTTP
 * client (or a host-supplied client), the one injected backoff strategy (or a host-supplied strategy) and the
 * one bounded, redacted provider-evidence record. The adapter is created only from validated host
 * configuration: no endpoint, credential, model id or allowlist is committed here, and a missing or
 * out-of-bounds setting aborts the start with one bounded, redacted code.
 *
 * <p>The evidence record is derived from the effective configuration alone and is therefore
 * {@code NOT_VERIFIED} unless exactly one host-supplied {@link ProductionModelLiveVerification} describes the
 * exact configuration hash — that is, unless an environment-gated live run actually exercised it. Configuration
 * alone is never production evidence.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductionModelProperties.class)
@ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "campaign.production.model", name = "enabled", havingValue = "true")
public class ProductionModelConfiguration {

    /** The one production structured-model adapter. */
    public static final String MODEL_BEAN_NAME = "campaignProductionStructuredModel";

    /** The one production structured-model backoff strategy. */
    public static final String BACKOFF_BEAN_NAME = "campaignProductionModelBackoff";

    /** The one bounded, redacted provider-evidence record. */
    public static final String EVIDENCE_BEAN_NAME = "campaignProductionModelEvidence";

    /**
     * The one production structured-model adapter.
     *
     * <p>A host-supplied {@link HttpClient} and a host-supplied {@link ProductionModelBackoff} each have
     * documented precedence; more than one of either is an ambiguous wiring and aborts startup. The adapter's
     * declared type is the concrete implementation, so one exact instance resolves as the neutral
     * {@link com.company.campaign.model.StructuredModelPort} and as the accepted
     * {@link com.company.campaign.builder.segment.SegmentIntentModelPort} at once: the same object serves
     * Campaign routing, every Skill input resolver and Segment intent extraction, and there is no second model.
     */
    @Bean(name = MODEL_BEAN_NAME)
    @Qualifier(MODEL_BEAN_NAME)
    public ProductionAnthropicModelAdapter campaignProductionStructuredModel(
            ProductionModelProperties properties,
            ObjectProvider<HttpClient> hostClients,
            ObjectProvider<ProductionModelBackoff> hostBackoffs) {
        properties.validate();
        HttpClient client = single(hostClients, "httpClient");
        if (client == null) {
            client = ProductionAnthropicModelAdapter.httpClient(properties);
        }
        ProductionModelBackoff backoff = single(hostBackoffs, "backoff");
        if (backoff == null) {
            backoff = defaultBackoff(properties);
        }
        return new ProductionAnthropicModelAdapter(properties, client, backoff);
    }

    /**
     * The one bounded, redacted provider-evidence record of the production model.
     *
     * <p>Its status is {@code NOT_VERIFIED} with no instant unless exactly one host-supplied
     * {@link ProductionModelLiveVerification} describes the exact configuration hash, so a fixture, a scripted
     * server or a committed configuration value cannot make it look live-verified. More than one host
     * verification record is an ambiguous wiring and aborts startup.
     */
    @Bean(name = EVIDENCE_BEAN_NAME)
    @Qualifier(EVIDENCE_BEAN_NAME)
    public ProductionModelEvidence campaignProductionModelEvidence(
            ProductionModelProperties properties,
            ObjectProvider<ProductionModelLiveVerification> hostVerifications) {
        properties.validate();
        ProductionModelLiveVerification verification = single(hostVerifications, "liveVerification");
        ProductionModelEvidenceStatus status = ProductionModelEvidenceStatus.NOT_VERIFIED;
        Instant verifiedAt = null;
        if (verification != null && verification.describes(properties.configurationHash())) {
            status = verification.status();
            verifiedAt = status == ProductionModelEvidenceStatus.NOT_VERIFIED ? null : verification.verifiedAt();
        }
        return new ProductionModelEvidence(
                ProductionModelProperties.PROVIDER_KIND,
                properties.modelIdFingerprint().value(),
                ProductionModelProperties.CONTRACT_VERSION,
                properties.configurationHash(),
                status,
                verifiedAt,
                List.copyOf(properties.getGatePins()));
    }

    /**
     * The one bounded production backoff strategy.
     *
     * <p>It is the only place a wait is performed: the adapter asks this strategy for a bounded delay before an
     * already-decided retry, and the adapter's own core logic holds no sleep, no clock and no randomness. The
     * delay is derived deterministically from the retry attempt, and an interrupt fails the retry closed by
     * restoring the interrupt flag.
     */
    @Bean(name = BACKOFF_BEAN_NAME)
    @Qualifier(BACKOFF_BEAN_NAME)
    @ConditionalOnMissingBean(ProductionModelBackoff.class)
    public ProductionModelBackoff campaignProductionModelBackoff(ProductionModelProperties properties) {
        properties.validate();
        return defaultBackoff(properties);
    }

    private static ProductionModelBackoff defaultBackoff(ProductionModelProperties properties) {
        return attempt -> {
            long millis = Math.min(
                    properties.getMaxBackoff().toMillis(),
                    properties.getInitialBackoff().toMillis() * (1L << Math.min(attempt - 1, 16)));
            try {
                Thread.sleep(millis);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw ProductionModelException.of(
                        ProductionModelErrorCodes.MODEL_TRANSPORT_FAILED,
                        "The production model retry backoff was interrupted.");
            }
        };
    }

    private static <T> T single(ObjectProvider<T> provider, String port) {
        List<T> supplied = provider.orderedStream().limit(2).toList();
        if (supplied.size() > 1) {
            throw new ProductionModelException(
                    ProductionModelErrorCodes.MODEL_CONFIGURATION_INVALID,
                    "The production model requires at most one host " + port + '.');
        }
        return supplied.isEmpty() ? null : supplied.getFirst();
    }
}
