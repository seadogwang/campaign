package com.company.campaign.production.provider;

import com.company.campaign.action.ProviderCapability;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T35C1: the production Provider configuration is default-off, fails closed on an incomplete or malformed
 * configuration, and only ever declares production capability for a validated HTTPS configuration that asserts
 * business-key idempotency and reliable query-by-key.
 */
class ProductionProviderPropertiesTest {

    private static final String ENDPOINT = "https://provider.example.invalid";
    private static final String HOST = "provider.example.invalid";
    private static final String CREDENTIAL = "a-credential-that-is-never-printed";

    /** One fully populated but still disabled configuration. */
    private static ProductionProviderProperties populated(ProductionProviderKind kind) {
        ProductionProviderProperties properties = new ProductionProviderProperties();
        ProductionProviderProperties.Adapter adapter = properties.adapter(kind);
        adapter.setEndpoint(ENDPOINT);
        adapter.setAllowedHosts(List.of(HOST));
        adapter.setProviderId("secret-provider-id-4711");
        adapter.setOwnerTenantId("tenant-1");
        adapter.setCredential(CREDENTIAL);
        adapter.setAssertBusinessKeyIdempotency(true);
        adapter.setAssertReliableQueryByKey(true);
        return properties;
    }

    /** One populated, enabled message configuration. */
    private static ProductionProviderProperties enabledMessage() {
        ProductionProviderProperties properties = populated(ProductionProviderKind.MESSAGE);
        properties.getMessage().setEnabled(true);
        return properties;
    }

    private static String codeOf(ProductionProviderProperties properties) {
        ProductionProviderException failure =
                catchThrowableOfType(ProductionProviderException.class, properties::validate);
        return failure == null ? null : failure.code();
    }

    @Test
    void bothAdaptersAreOffAndTheDefaultConfigurationIsValid() {
        ProductionProviderProperties properties = new ProductionProviderProperties();
        properties.validate();

        assertThat(properties.anyEnabled()).isFalse();
        assertThat(properties.enabled(ProductionProviderKind.MESSAGE)).isFalse();
        assertThat(properties.enabled(ProductionProviderKind.REWARD)).isFalse();
        assertThat(properties.getQueryMaxAttempts()).isPositive();
    }

    @Test
    void anEnabledAdapterWithoutConfigurationFailsClosed() {
        ProductionProviderProperties properties = new ProductionProviderProperties();
        properties.getMessage().setEnabled(true);

        assertThat(codeOf(properties)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void anEndpointHostOutsideTheAllowlistIsRefused() {
        ProductionProviderProperties properties = enabledMessage();
        properties.getMessage().setAllowedHosts(List.of("another.example.invalid"));

        assertThat(codeOf(properties)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void aPlaintextNonLoopbackEndpointIsRefusedEvenWithoutHttps() {
        ProductionProviderProperties properties = enabledMessage();
        ProductionProviderProperties.Adapter adapter = properties.getMessage();
        adapter.setRequireHttps(false);
        adapter.setEndpoint("http://provider.example.invalid");

        assertThat(codeOf(properties)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void aLoopbackPlaintextEndpointIsRefusedWhenHttpsIsRequired() {
        ProductionProviderProperties properties = enabledMessage();
        ProductionProviderProperties.Adapter adapter = properties.getMessage();
        adapter.setEndpoint("http://127.0.0.1:8080");
        adapter.setAllowedHosts(List.of("127.0.0.1"));

        assertThat(codeOf(properties)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void aLoopbackPlaintextEndpointIsRepresentableButNeverProduction() {
        ProductionProviderProperties properties = enabledMessage();
        ProductionProviderProperties.Adapter adapter = properties.getMessage();
        adapter.setRequireHttps(false);
        adapter.setEndpoint("http://127.0.0.1:54321");
        adapter.setAllowedHosts(List.of("127.0.0.1"));
        properties.validate();

        assertThat(adapter.productionCapable()).isFalse();
        ProviderCapability capability = properties.capability(ProductionProviderKind.MESSAGE);
        assertThat(capability.production()).isFalse();
        assertThat(capability.businessKeyIdempotency()).isTrue();
        assertThat(capability.reliableQueryByKey()).isTrue();
        assertThat(capability.satisfiesSubmission()).isTrue();
    }

    @Test
    void aValidatedHttpsConfigurationDeclaresProductionCapability() {
        ProductionProviderProperties properties = enabledMessage();
        properties.validate();

        ProviderCapability capability = properties.capability(ProductionProviderKind.MESSAGE);
        assertThat(properties.getMessage().productionCapable()).isTrue();
        assertThat(capability.production()).isTrue();
        assertThat(capability.businessKeyIdempotency()).isTrue();
        assertThat(capability.reliableQueryByKey()).isTrue();
        assertThat(capability.ownerTenantId()).isEqualTo("tenant-1");
        assertThat(capability.actionKind().code()).isEqualTo("MESSAGE");
    }

    @Test
    void anEnabledAdapterMustExplicitlyAssertIdempotencyAndReconciliation() {
        ProductionProviderProperties properties = enabledMessage();
        properties.getMessage().setAssertBusinessKeyIdempotency(false);

        assertThat(codeOf(properties)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void anExactContractVersionIsRequired() {
        ProductionProviderProperties properties = enabledMessage();
        properties.getMessage().setApiVersion("some-other-version/9");

        assertThat(codeOf(properties)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void outOfBoundsTimeoutsAndSizesAreRefused() {
        ProductionProviderProperties timeout = enabledMessage();
        timeout.getMessage().setRequestTimeout(Duration.ofHours(2));

        assertThat(codeOf(timeout)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);

        ProductionProviderProperties oversized = enabledMessage();
        oversized.getMessage()
                .setMaxResponseBytes(ProductionProviderProperties.MAX_RESPONSE_BYTES_LIMIT + 1);

        assertThat(codeOf(oversized)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);

        ProductionProviderProperties unbounded = enabledMessage();
        unbounded.setQueryMaxAttempts(ProductionProviderProperties.MAX_QUERY_ATTEMPTS_LIMIT + 1);

        assertThat(codeOf(unbounded)).isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
    }

    @Test
    void anEndpointWithAQueryFragmentOrUserInfoIsRefused() {
        for (String endpoint : List.of(
                "https://provider.example.invalid?x=1",
                "https://provider.example.invalid#fragment",
                "https://user:secret@provider.example.invalid")) {
            ProductionProviderProperties properties = enabledMessage();
            properties.getMessage().setEndpoint(endpoint);

            assertThat(codeOf(properties))
                    .as(endpoint)
                    .isEqualTo(ProductionProviderErrorCodes.PROVIDER_CONFIGURATION_INVALID);
        }
    }

    @Test
    void theTwoAdaptersEnableIndependently() {
        ProductionProviderProperties properties = enabledMessage();
        properties.validate();

        assertThat(properties.enabled(ProductionProviderKind.MESSAGE)).isTrue();
        assertThat(properties.enabled(ProductionProviderKind.REWARD)).isFalse();
    }

    @Test
    void aRewardOnlyConfigurationIsValidOnItsOwn() {
        ProductionProviderProperties properties = populated(ProductionProviderKind.REWARD);
        properties.getReward().setEnabled(true);
        properties.validate();

        assertThat(properties.enabled(ProductionProviderKind.REWARD)).isTrue();
        assertThat(properties.enabled(ProductionProviderKind.MESSAGE)).isFalse();
    }

    @Test
    void theConfigurationHashIsStableAndChangesWithTheCredential() {
        ProductionProviderProperties properties = enabledMessage();
        properties.validate();

        var first = properties.configurationHash(ProductionProviderKind.MESSAGE);
        assertThat(properties.configurationHash(ProductionProviderKind.MESSAGE)).isEqualTo(first);
        assertThat(properties.providerIdFingerprint(ProductionProviderKind.MESSAGE).value()).startsWith("sha256:");

        properties.getMessage().setCredential("a-rotated-credential");
        assertThat(properties.configurationHash(ProductionProviderKind.MESSAGE)).isNotEqualTo(first);
    }

    @Test
    void theDiagnosticRedactsEverySecretAndEndpoint() {
        ProductionProviderProperties properties = enabledMessage();

        assertThat(properties.toString())
                .doesNotContain(ENDPOINT)
                .doesNotContain(HOST)
                .doesNotContain(CREDENTIAL)
                .doesNotContain("secret-provider-id-4711")
                .doesNotContain("tenant-1");
    }
}
