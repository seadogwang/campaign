package com.company.campaign.production.runtime;

import com.company.campaign.action.ActionVersions;
import com.company.campaign.runtime.RuntimeFactRequest;
import com.company.campaign.runtime.RuntimeVersions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T35C2: the bounded, default-off configuration of the production provider runtime host.
 *
 * <p>Every bound is proved to be inside the frozen domain limits, every out-of-bounds value is proved to fail
 * closed, and the redacted diagnostics are proved to disclose no value that could be a secret. Nothing here
 * starts a worker, an executor or a thread.
 */
class ProductionProviderRuntimePropertiesTest {

    @Test
    void theDefaultOffConfigurationValidatesNothingAndStartsNothing() {
        ProductionProviderRuntimeProperties properties = new ProductionProviderRuntimeProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isSchedulerEnabled()).isFalse();
    }

    @Test
    void everyDefaultBoundLiesInsideTheFrozenDomainLimits() {
        ProductionProviderRuntimeProperties properties = new ProductionProviderRuntimeProperties();

        assertThat(properties.getLeaseDuration())
                .isBetween(RuntimeVersions.MIN_LEASE_DURATION, RuntimeVersions.MAX_LEASE_DURATION)
                .isLessThanOrEqualTo(ActionVersions.MAX_LEASE_DURATION);
        assertThat(properties.getFactTimeoutMillis())
                .isBetween(1L, RuntimeFactRequest.MAX_TIMEOUT_MILLIS);
        assertThat(properties.getMaterializationFreshness())
                .isBetween(RuntimeVersions.MIN_FRESHNESS_AGE, RuntimeVersions.MAX_FRESHNESS_AGE);
        assertThat(properties.getClaimScanLimit())
                .isBetween(1, RuntimeVersions.MAX_CLAIM_CANDIDATES);
        assertThat(properties.getIngestLimit())
                .isBetween(1, ActionVersions.MAX_CLAIM_CANDIDATES);
        assertThat(properties.getSchedulerCadence())
                .isBetween(ProductionProviderRuntimeProperties.MIN_SCHEDULER_CADENCE,
                        ProductionProviderRuntimeProperties.MAX_SCHEDULER_CADENCE);
        assertThat(properties.getMaxTenants()).isBetween(1, ProductionProviderRuntimeProperties.MAX_TENANTS);
        assertThat(properties.getMaxRuntimeItemsPerCycle())
                .isBetween(1, ProductionProviderRuntimeProperties.MAX_ITEMS_PER_CYCLE);
        assertThat(properties.getMaxActionItemsPerCycle())
                .isBetween(1, ProductionProviderRuntimeProperties.MAX_ITEMS_PER_CYCLE);
    }

    @Test
    void aDisabledConfigurationIgnoresEvenOutOfBoundsValues() {
        ProductionProviderRuntimeProperties properties = new ProductionProviderRuntimeProperties();
        properties.setMaxTenants(0);
        properties.setLeaseDuration(Duration.ofDays(1));

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void everyBoundIsEnforcedWhenTheHostIsEnabled() {
        List<Consumer<ProductionProviderRuntimeProperties>> violations = new ArrayList<>();
        violations.add(properties -> properties.setLeaseDuration(null));
        violations.add(properties -> properties.setLeaseDuration(Duration.ofMillis(500)));
        violations.add(properties -> properties.setLeaseDuration(Duration.ofMinutes(6)));
        violations.add(properties -> properties.setFactTimeoutMillis(0L));
        violations.add(properties -> properties.setFactTimeoutMillis(RuntimeFactRequest.MAX_TIMEOUT_MILLIS + 1));
        violations.add(properties -> properties.setMaterializationFreshness(null));
        violations.add(properties -> properties.setMaterializationFreshness(Duration.ofHours(25)));
        violations.add(properties -> properties.setClaimScanLimit(0));
        violations.add(properties -> properties.setClaimScanLimit(RuntimeVersions.MAX_CLAIM_CANDIDATES + 1));
        violations.add(properties -> properties.setIngestLimit(0));
        violations.add(properties -> properties.setIngestLimit(ActionVersions.MAX_CLAIM_CANDIDATES + 1));
        violations.add(properties -> properties.setRuntimeAttempts(0));
        violations.add(properties -> properties.setRuntimeAttempts(ActionVersions.MAX_POLICY_ATTEMPTS + 1));
        violations.add(properties -> properties.setRuntimeBackoff(List.of()));
        violations.add(properties -> properties.setRuntimeBackoff(List.of(Duration.ZERO)));
        violations.add(properties -> properties.setSubmissionAttempts(1));
        violations.add(properties -> properties.setSubmissionBackoff(List.of(Duration.ofSeconds(1))));
        violations.add(properties -> properties.setVerificationAttempts(0));
        violations.add(properties -> properties.setVerificationBackoff(List.of()));
        violations.add(properties -> properties.setSchedulerCadence(null));
        violations.add(properties -> properties.setSchedulerCadence(Duration.ofMillis(10)));
        violations.add(properties -> properties.setSchedulerCadence(Duration.ofHours(2)));
        violations.add(properties -> properties.setMaxTenants(0));
        violations.add(properties -> properties.setMaxTenants(ProductionProviderRuntimeProperties.MAX_TENANTS + 1));
        violations.add(properties -> properties.setMaxRuntimeItemsPerCycle(0));
        violations.add(properties -> properties.setMaxActionItemsPerCycle(
                ProductionProviderRuntimeProperties.MAX_ITEMS_PER_CYCLE + 1));

        for (Consumer<ProductionProviderRuntimeProperties> violation : violations) {
            ProductionProviderRuntimeProperties properties = enabled();
            violation.accept(properties);
            ProductionProviderRuntimeConfigurationException failure =
                    catchThrowableOfType(ProductionProviderRuntimeConfigurationException.class,
                            properties::validate);
            assertThat(failure).as("every out-of-bounds bound fails closed").isNotNull();
            assertThat(failure.code())
                    .isEqualTo(ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID);
        }
    }

    @Test
    void theConfigurationHashIsStableAndChangesWithAnyBound() {
        ProductionProviderRuntimeProperties first = enabled();
        ProductionProviderRuntimeProperties second = enabled();
        assertThat(first.configurationHash()).isEqualTo(second.configurationHash());

        second.setMaxActionItemsPerCycle(first.getMaxActionItemsPerCycle() + 1);
        assertThat(second.configurationHash()).isNotEqualTo(first.configurationHash());
    }

    @Test
    void theWorkerIdentityIsStableBoundedAndServerGenerated() {
        ProductionProviderRuntimeProperties properties = enabled();

        String workerId = properties.workerId();
        assertThat(workerId).matches("^prw-[0-9a-f]{32}$");
        assertThat(workerId).isEqualTo(enabled().workerId());
        assertThat(workerId.length()).isLessThanOrEqualTo(128);
    }

    @Test
    void theDiagnosticsDiscloseNoValueThatCouldBeASecret() {
        ProductionProviderRuntimeProperties properties = enabled();
        assertThat(properties.toString())
                .contains("maxTenants=")
                .doesNotContain("password")
                .doesNotContain("credential")
                .doesNotContain("url");
    }

    private static ProductionProviderRuntimeProperties enabled() {
        ProductionProviderRuntimeProperties properties = new ProductionProviderRuntimeProperties();
        properties.setEnabled(true);
        return properties;
    }
}