package com.company.campaign.production.provider;

import com.company.campaign.action.ActionProviderException;
import com.company.campaign.shared.canonical.ContentHash;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

/**
 * The one opt-in configuration of the default-off production Provider adapters.
 *
 * <p>It is inert unless the host sets {@code campaign.production.enabled=true}; the message and the reward
 * adapter are then enabled independently by {@code campaign.production.provider.message.enabled} and
 * {@code campaign.production.provider.reward.enabled}. When an adapter is off — the default — no HTTP client, no
 * adapter and no evidence bean is created for it, so either Provider can be enabled on its own.
 *
 * <p>When an adapter is on, it publishes exactly one adapter over the validated configuration — the one bounded
 * JDK HTTP client (or a host-supplied client) and the one bounded, redacted provider-evidence record. The adapter
 * is created only from validated host configuration: no endpoint, credential, provider id, owner or allowlist is
 * committed here, and a missing or out-of-bounds setting aborts the start with one bounded, redacted code.
 *
 * <p>The evidence record is derived from the effective configuration alone and is therefore {@code NOT_VERIFIED}
 * unless exactly one host-supplied {@link ProductionProviderLiveVerification} describes the exact configuration
 * hash, capability hash, provider kind and action kind — that is, unless an environment-gated live run actually
 * exercised it. Configuration alone is never production evidence.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductionProviderProperties.class)
@ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
public class ProductionProviderConfiguration {

    /** The one production message Provider bean. */
    public static final String MESSAGE_BEAN_NAME = "campaignProductionMessageProvider";

    /** The one production reward Provider bean. */
    public static final String REWARD_BEAN_NAME = "campaignProductionRewardProvider";

    /** The one bounded, redacted message Provider evidence bean. */
    public static final String MESSAGE_EVIDENCE_BEAN_NAME = "campaignProductionMessageProviderEvidence";

    /** The one bounded, redacted reward Provider evidence bean. */
    public static final String REWARD_EVIDENCE_BEAN_NAME = "campaignProductionRewardProviderEvidence";

    /**
     * The one production message Provider adapter.
     *
     * <p>A host-supplied {@link HttpClient} and a host-supplied {@link ProductionProviderBackoff} each have
     * documented precedence; more than one of either is an ambiguous wiring and aborts startup.
     */
    @Bean(name = MESSAGE_BEAN_NAME)
    @Qualifier(MESSAGE_BEAN_NAME)
    @ConditionalOnProperty(prefix = "campaign.production.provider.message", name = "enabled", havingValue = "true")
    public ProductionProviderMessageAdapter campaignProductionMessageProvider(
            ProductionProviderProperties properties,
            ObjectProvider<HttpClient> hostClients,
            ObjectProvider<ProductionProviderBackoff> hostBackoffs) {
        properties.validate();
        return new ProductionProviderMessageAdapter(
                properties,
                client(properties, ProductionProviderKind.MESSAGE, hostClients),
                backoff(properties, hostBackoffs));
    }

    /** The one production reward Provider adapter, enabled independently of the message adapter. */
    @Bean(name = REWARD_BEAN_NAME)
    @Qualifier(REWARD_BEAN_NAME)
    @ConditionalOnProperty(prefix = "campaign.production.provider.reward", name = "enabled", havingValue = "true")
    public ProductionProviderRewardAdapter campaignProductionRewardProvider(
            ProductionProviderProperties properties,
            ObjectProvider<HttpClient> hostClients,
            ObjectProvider<ProductionProviderBackoff> hostBackoffs) {
        properties.validate();
        return new ProductionProviderRewardAdapter(
                properties,
                client(properties, ProductionProviderKind.REWARD, hostClients),
                backoff(properties, hostBackoffs));
    }

    /**
     * The one bounded, redacted message Provider evidence record.
     *
     * <p>Its status is {@code NOT_VERIFIED} with no instant and no expiry unless exactly one host-supplied
     * {@link ProductionProviderLiveVerification} describes the exact configuration, so a fixture, a scripted
     * loopback server or a committed configuration value cannot make it look live-verified.
     */
    @Bean(name = MESSAGE_EVIDENCE_BEAN_NAME)
    @Qualifier(MESSAGE_EVIDENCE_BEAN_NAME)
    @ConditionalOnProperty(prefix = "campaign.production.provider.message", name = "enabled", havingValue = "true")
    public ProductionProviderEvidence campaignProductionMessageProviderEvidence(
            ProductionProviderProperties properties,
            ObjectProvider<ProductionProviderLiveVerification> hostVerifications) {
        return evidence(properties, ProductionProviderKind.MESSAGE, hostVerifications);
    }

    /** The one bounded, redacted reward Provider evidence record, enabled independently. */
    @Bean(name = REWARD_EVIDENCE_BEAN_NAME)
    @Qualifier(REWARD_EVIDENCE_BEAN_NAME)
    @ConditionalOnProperty(prefix = "campaign.production.provider.reward", name = "enabled", havingValue = "true")
    public ProductionProviderEvidence campaignProductionRewardProviderEvidence(
            ProductionProviderProperties properties,
            ObjectProvider<ProductionProviderLiveVerification> hostVerifications) {
        return evidence(properties, ProductionProviderKind.REWARD, hostVerifications);
    }

    // ------------------------------------------------------------------ internals

    private static ProductionProviderEvidence evidence(
            ProductionProviderProperties properties,
            ProductionProviderKind kind,
            ObjectProvider<ProductionProviderLiveVerification> hostVerifications) {
        properties.validate();
        ProductionProviderProperties.Adapter settings = properties.adapter(kind);
        ContentHash configurationHash = properties.configurationHash(kind);
        ContentHash capabilityHash = properties.capabilityHash(kind);
        ProductionProviderLiveVerification match = null;
        for (ProductionProviderLiveVerification verification : hostVerifications.orderedStream().toList()) {
            if (verification.describes(kind, kind.actionKind(), configurationHash, capabilityHash)) {
                if (match != null) {
                    throw new ProductionProviderException(
                            ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID,
                            "The production Provider requires at most one host live verification of one "
                                    + "configuration.");
                }
                match = verification;
            }
        }
        ProductionProviderVerificationStatus status = ProductionProviderVerificationStatus.NOT_VERIFIED;
        Instant verifiedAt = null;
        Instant expiry = null;
        if (match != null && match.status().ran()) {
            status = match.status();
            verifiedAt = match.verifiedAt();
            expiry = match.expiry();
        }
        return new ProductionProviderEvidence(
                kind,
                ProductionProviderEvidence.fingerprint(settings.getProviderId()),
                kind.actionKind(),
                configurationHash,
                ProductionProviderProperties.CONTRACT_VERSION,
                capabilityHash,
                List.copyOf(properties.getGatePins()),
                status,
                verifiedAt,
                expiry);
    }

    private static HttpClient client(
            ProductionProviderProperties properties,
            ProductionProviderKind kind,
            ObjectProvider<HttpClient> hostClients) {
        HttpClient supplied = single(hostClients, "httpClient");
        return supplied != null ? supplied : ProductionProviderTransport.httpClient(properties.adapter(kind));
    }

    private static ProductionProviderBackoff backoff(
            ProductionProviderProperties properties,
            ObjectProvider<ProductionProviderBackoff> hostBackoffs) {
        ProductionProviderBackoff supplied = single(hostBackoffs, "backoff");
        return supplied != null ? supplied : defaultBackoff(properties);
    }

    /**
     * The one bounded default backoff strategy.
     *
     * <p>It is the only place a wait is performed: the adapter core holds no sleep, no clock and no randomness, so
     * no other class in this package can wait at all. The delay is derived deterministically from the retry
     * attempt, and an interrupt fails the retry closed by restoring the interrupt flag.
     */
    private static ProductionProviderBackoff defaultBackoff(ProductionProviderProperties properties) {
        return attempt -> {
            long millis = Math.min(
                    properties.getMaxBackoff().toMillis(),
                    properties.getInitialBackoff().toMillis() * (1L << Math.min(attempt - 1, 16)));
            try {
                Thread.sleep(millis);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new ActionProviderException(ProductionProviderErrorCodes.PROVIDER_TRANSPORT_FAILED);
            }
        };
    }

    private static <T> T single(ObjectProvider<T> provider, String port) {
        List<T> supplied = provider.orderedStream().limit(2).toList();
        if (supplied.size() > 1) {
            throw new ProductionProviderException(
                    ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID,
                    "The production Provider requires at most one host " + port + '.');
        }
        return supplied.isEmpty() ? null : supplied.getFirst();
    }
}