package com.company.campaign.production.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T35B: the production model configuration is inert by default, bounded when enabled, and never prints a
 * value that could be a secret.
 */
class ProductionModelPropertiesTest {

    private static ProductionModelProperties configured() {
        ProductionModelProperties properties = new ProductionModelProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://model.example.invalid");
        properties.setAllowedHosts(List.of("model.example.invalid"));
        properties.setModelId("claude-example-model");
        properties.setCredential("a-credential-that-must-never-be-printed");
        return properties;
    }

    @Test
    void theDisabledDefaultValidatesNothingAndStaysInert() {
        ProductionModelProperties properties = new ProductionModelProperties();
        assertThat(properties.isEnabled()).isFalse();
        properties.validate();
        assertThat(properties.toString()).contains("enabled=false");
    }

    @Test
    void aMissingEndpointFailsClosed() {
        ProductionModelProperties properties = configured();
        properties.setEndpoint(null);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(ProductionModelException.class)
                .extracting(thrown -> ((ProductionModelException) thrown).code())
                .isEqualTo(ProductionModelErrorCodes.MODEL_CONFIGURATION_INVALID);
    }

    @Test
    void aPlaintextEndpointIsRejectedWhenHttpsIsRequired() {
        ProductionModelProperties properties = configured();
        properties.setEndpoint("http://model.example.invalid");
        assertThatThrownBy(properties::validate).isInstanceOf(ProductionModelException.class);
    }

    @Test
    void aHostOutsideTheAllowlistFailsClosed() {
        ProductionModelProperties properties = configured();
        properties.setAllowedHosts(List.of("another.example.invalid"));
        assertThatThrownBy(properties::validate).isInstanceOf(ProductionModelException.class);
    }

    @Test
    void anUnacceptedApiVersionFailsClosed() {
        ProductionModelProperties properties = configured();
        properties.setApiVersion("2019-01-01");
        assertThatThrownBy(properties::validate).isInstanceOf(ProductionModelException.class);
    }

    @Test
    void anOutOfBoundsRetryPolicyFailsClosed() {
        ProductionModelProperties properties = configured();
        properties.setMaxAttempts(9);
        assertThatThrownBy(properties::validate).isInstanceOf(ProductionModelException.class);

        ProductionModelProperties backoff = configured();
        backoff.setInitialBackoff(Duration.ofSeconds(10));
        backoff.setMaxBackoff(Duration.ofSeconds(1));
        assertThatThrownBy(backoff::validate).isInstanceOf(ProductionModelException.class);
    }

    @Test
    void theDiagnosticPrintsNoEndpointCredentialOrModelId() {
        String described = configured().toString();
        assertThat(described)
                .doesNotContain("model.example.invalid")
                .doesNotContain("a-credential-that-must-never-be-printed")
                .doesNotContain("claude-example-model");
    }

    @Test
    void theConfigurationHashIsStableAndChangesWithTheConfiguration() {
        ProductionModelProperties properties = configured();
        ProductionModelProperties same = configured();
        assertThat(properties.configurationHash()).isEqualTo(same.configurationHash());

        ProductionModelProperties rotated = configured();
        rotated.setCredential("a-different-credential");
        assertThat(rotated.configurationHash()).isNotEqualTo(properties.configurationHash());

        ProductionModelProperties moved = configured();
        moved.setEndpoint("https://other.example.invalid");
        moved.setAllowedHosts(List.of("other.example.invalid"));
        assertThat(moved.configurationHash()).isNotEqualTo(properties.configurationHash());
    }

    @Test
    void theModelIdFingerprintIsAOneWayContentHash() {
        assertThat(configured().modelIdFingerprint().value()).startsWith("sha256:");
        assertThat(configured().modelIdFingerprint().value())
                .doesNotContain("claude-example-model");
    }

    @Test
    void theMessagesEndpointIsDerivedFromTheBaseUrl() {
        assertThat(configured().messagesEndpoint().toString())
                .isEqualTo("https://model.example.invalid/v1/messages");
    }

    @Test
    void anAmbiguousStatementIsRejectedThroughTheConfigurationCode() {
        ProductionModelProperties properties = configured();
        properties.setGatePins(List.of(""));
        ProductionModelException failure =
                catchThrowableOfType(ProductionModelException.class, properties::validate);
        assertThat(failure.code()).isEqualTo(ProductionModelErrorCodes.MODEL_CONFIGURATION_INVALID);
    }
}